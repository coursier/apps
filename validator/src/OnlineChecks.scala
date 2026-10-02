package validateapps

import coursier.cache.FileCache
import coursier.core.{Module, ModuleName, Organization, Repository}
import coursier.install.{AppArtifacts, AppDescriptor, LauncherType, MainClass}
import coursier.ivy.IvyRepository
import coursier.maven.MavenRepository
import coursier.parse.JavaOrScalaDependency
import coursier.util.Task
import coursier.version.Version

import java.io.File
import java.util.zip.ZipFile

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Using
import scala.util.control.NonFatal

/** Checks that need to download things: published versions, dependencies, main class, prebuilt
  * launchers
  *
  * @param allVersions
  *   whether to check all versions of apps, rather than a sample of them
  */
final class OnlineChecks(allVersions: Boolean) {

  // logs slow downloads, so that the CI logs say what's taking time
  private lazy val cache     = FileCache.create[Task]().copy(logger = new SlowDownloadLogger)
  private lazy val prebuilts = new Prebuilts(cache)

  private def await[T](f: Future[T]): T = Await.result(f, Duration.Inf)

  private def listVersions(module: Module, repositories: Seq[Repository]): Future[Seq[Version]] = {
    given ExecutionContext = cache.ec
    coursier.Versions(cache, moduleOpt = Some(module), repositories = repositories)
      .result()
      .future()
      .map(_.versions.available0.filter(!_.asString.endsWith("SNAPSHOT")))
  }

  private lazy val fullScalaVersions: Seq[String] = {
    given ExecutionContext = cache.ec
    val central = Seq(coursier.Repositories.central)
    def mod(name: String) = Module(Organization("org.scala-lang"), ModuleName(name), Map.empty)
    val all = await(Future.sequence(Seq(
      listVersions(mod("scala-library"), central),
      listVersions(mod("scala3-library_3"), central)
    ))).flatten
    all
      .filter(v => v.asString.matches("""\d+\.\d+\.\d+""") && v >= Version("2.10.0"))
      .map(_.asString)
  }

  // The modules the first dependency of an app can correspond to (several of
  // them for Scala dependencies, depending on the Scala version)
  private def mainModules(desc: AppDescriptor): Seq[Module] =
    desc.dependencies.headOption.toSeq.flatMap {
      case dep: JavaOrScalaDependency.JavaDependency =>
        Seq(dep.dependency.module)
      case dep: JavaOrScalaDependency.ScalaDependency =>
        val platformNames =
          if (dep.withPlatformSuffix && desc.launcherType == LauncherType.ScalaNative)
            Seq("native0.4", "native0.5")
          else
            Seq("")
        val scalaVersions =
          if (dep.fullCrossVersion) fullScalaVersions
          else Seq("2.10", "2.11", "2.12", "2.13", "3")
        for (pf <- platformNames; sv <- scalaVersions)
          yield dep.dependency(sv, sv, pf).module
    }.distinct

  // Development builds published along with releases (nightlies, versions
  // computed by git describe, …), that users don't install, and aren't sampled
  private val gitDescribeRegex = """[-+]\d+-g[0-9a-f]{7,}""".r
  // commit hashes, like in 0.1.1-30-0768db or 0.0.0+101-9de31723
  private val commitHashRegex = """(^|[-+.])(?=[0-9a-f]*[a-f])[0-9a-f]{6,}($|[-+.])""".r
  private def isDevBuild(ver: String): Boolean =
    !ver.headOption.exists(_.isDigit) ||
    ver.contains("NIGHTLY") ||
    ver.contains("-bin-") ||
    gitDescribeRegex.findFirstIn(ver).nonEmpty ||
    commitHashRegex.findFirstIn(ver).nonEmpty

  private def isStable(ver: String): Boolean =
    !ver.exists(_.isLetter) && ver.split(Array('.', '-')).forall(_.lengthCompare(5) <= 0)

  private def classExists(jars: Seq[File], className: String): Boolean = {
    val entry = className.replace('.', '/') + ".class"
    jars.exists { jar =>
      jar.getName.endsWith(".jar") && Using.resource(new ZipFile(jar))(_.getEntry(entry) != null)
    }
  }

  private def checkMainClass(
    desc: AppDescriptor,
    artifacts: AppArtifacts,
    version: String,
    reportIssue: String => Unit
  ): Unit = {
    val result = artifacts.fetchResult
    // Same logic as coursier.install.InstallDir
    lazy val foundMainClassOpt = MainClass.retainedMainClassOpt(
      MainClass.mainClasses(result.artifacts.filterNot(artifacts.shared.toSet).map(_._2)),
      result.resolution.rootDependencies.headOption.map { dep =>
        (dep.module.organization.value, dep.module.name.value)
      }
    )
    desc.mainClass.orElse(foundMainClassOpt).orElse(desc.defaultMainClass) match {
      case None =>
        reportIssue(s"$version: no main class specified, and none could be found in the JAR manifests")
      case Some(mainClass) =>
        if (!classExists(result.files, mainClass))
          reportIssue(s"$version: main class $mainClass not found in the class path")
    }
  }

  private def repositoryPrefix(repo: Repository): Option[(String, String)] =
    repo match {
      case m: MavenRepository => Some((m.root, m.root.stripSuffix("/") + "/"))
      case i: IvyRepository =>
        val pattern = i.pattern.string
        Some((pattern, pattern.takeWhile(c => c != '[' && c != '(')))
      case _ => None
    }

  def apply(app: App, desc: AppDescriptor, issues: Seq[KnownIssue], report: Report): Unit = {
    val overrideIntervals = desc.versionOverrides.map(_.versionRange0)
    // repository -> whether it provided at least one artifact
    val repositoryUsage = mutable.LinkedHashMap.empty[(String, String), Boolean]

    for (rule <- Descriptors.rules(desc)) {
      val modules = mainModules(rule.desc)
      report.progress(s"${rule.label}: listing published versions")
      val listed = {
        given ExecutionContext = cache.ec
        await(Future.sequence(modules.map(listVersions(_, rule.desc.repositories)))).flatten.distinct
      }
      val inRange = listed.filter { v =>
        rule.interval match {
          case Some(itv) => itv.contains(v)
          case None      => !overrideIntervals.exists(_.contains(v))
        }
      }
      val releases = inRange.filterNot(v => isDevBuild(v.asString))
      val sorted   = releases.filterNot(v => issues.exists(_.excludesVersion(v))).sorted
      val samples =
        if (allVersions) sorted.map(_.asString)
        else
          Seq(sorted.headOption, sorted.lastOption, sorted.filter(v => isStable(v.asString)).lastOption)
            .flatten.distinct.map(_.asString)
      val devBuildCount = inRange.length - releases.length
      val excludedCount = releases.length - sorted.length
      def modulesDesc =
        if (modules.length == 1) modules.head.toString
        else s"${rule.desc.dependencies.head.module} (for all Scala versions)"

      if (listed.isEmpty)
        report.error(s"${rule.label}: no published version found for $modulesDesc")
      else if (inRange.isEmpty) {
        if (rule.interval.nonEmpty)
          report.error(s"${rule.label}: matches none of the published versions of $modulesDesc")
        else
          report.info(s"${rule.label}: all published versions are handled by version overrides")
      }
      else {
        val details = Seq(
          Some(s"$devBuildCount development build(s)").filter(_ => devBuildCount > 0),
          Some(s"$excludedCount with known issues").filter(_ => excludedCount > 0)
        ).flatten
        val detailsDesc = if (details.isEmpty) "" else details.mkString(" (", ", ", ")")
        val checkingDesc =
          if (samples.isEmpty) "no version left to check" else s"checking ${samples.mkString(", ")}"
        report.info(s"${rule.label}: ${inRange.length} published version(s)$detailsDesc, $checkingDesc")
      }

      var fetchedSome  = false
      val artifactUrls = mutable.HashSet.empty[String]
      for (version <- samples) {
        report.progress(s"${rule.label}: checking $version")
        val versionDesc = desc.overrideVersion(version)
        val hasPrebuilts = versionDesc.launcherType.isNative &&
          (versionDesc.prebuiltLauncher.nonEmpty || versionDesc.prebuiltBinaries.nonEmpty)
        // with prebuilt launchers, the class path is only used on platforms without one
        val reportClassPathIssue: String => Unit =
          if (hasPrebuilts)
            msg => report.warning(s"$msg (only used on platforms without a prebuilt launcher)")
          else report.error
        if (versionDesc.launcherType != LauncherType.Prebuilt) {
          // retry once, to work around transient errors (HTTP 503, …)
          def fetch() = {
            report.progress(s"$version: fetching dependencies")
            versionDesc.artifacts(cache, 0)
          }
          val artifactsOrError =
            try Right(fetch())
            catch {
              case NonFatal(_) =>
                Thread.sleep(5000L)
                try Right(fetch())
                catch { case NonFatal(e) => Left(e) }
            }
          artifactsOrError match {
            case Right(artifacts) =>
              fetchedSome = true
              artifactUrls ++= artifacts.fetchResult.artifacts.map(_._1.url)
              checkMainClass(versionDesc, artifacts, version, reportClassPathIssue)
            case Left(e) =>
              reportClassPathIssue(s"$version: can't fetch dependencies: ${Report.errorMessage(e)}")
          }
        }
        if (versionDesc.launcherType.isNative) {
          report.progress(s"$version: checking prebuilt launchers")
          prebuilts.check(versionDesc, version, issues, report)
        }
      }

      if (fetchedSome)
        for (repo <- rule.desc.repositories; key <- repositoryPrefix(repo)) {
          val used = artifactUrls.exists(_.startsWith(key._2))
          repositoryUsage(key) = repositoryUsage.getOrElse(key, false) || used
        }
    }

    for (((repr, _), used) <- repositoryUsage if !used)
      report.warning(
        s"repository $repr provides none of the artifacts of the sampled versions, it might not be needed"
      )
  }
}
