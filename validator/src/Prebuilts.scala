package validateapps

import coursier.cache.{ArchiveCache, ArtifactError, Cache, FileCache}
import coursier.install.AppDescriptor
import coursier.install.internal.PrebuiltApp
import coursier.util.{Artifact, EitherT, Task}
import coursier.version.Version

import java.io.{File, IOException}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

/** Checks that prebuilt launchers exist, letting coursier find them the same way as cs does */
final class Prebuilts(cache: FileCache[Task]) {

  // downloads archives with cache, so that its logger says what's being downloaded
  private lazy val archiveCache = ArchiveCache.create[Task]().copy(cache = cache)
  private lazy val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

  private val urlExistsCache = new ConcurrentHashMap[String, Either[String, Boolean]]

  private def urlExists(url: String): Either[String, Boolean] = {
    def attempt(remaining: Int): Either[String, Boolean] = {
      val request = HttpRequest.newBuilder(URI.create(url))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .timeout(java.time.Duration.ofSeconds(60))
        .build()
      Report.log(s"Checking $url")
      val res =
        try {
          val code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
          if (code / 100 == 2) Right(true)
          else if (code == 404 || code == 410) Right(false)
          else Left(s"HTTP $code")
        }
        catch {
          case e: IOException => Left(e.toString)
        }
      val resDesc = res match {
        case Right(true)  => "found"
        case Right(false) => "not found"
        case Left(err)    => err
      }
      Report.log(s"Checked $url ($resDesc)")
      if (res.isLeft && remaining > 0) {
        Thread.sleep(2000L)
        attempt(remaining - 1)
      }
      else res
    }
    urlExistsCache.computeIfAbsent(url, _ => attempt(2))
  }

  // Stands for launchers that exist, but that aren't downloaded
  private lazy val placeholder: File = {
    val f = File.createTempFile("validate-apps", ".placeholder")
    f.deleteOnExit()
    f
  }

  // Only checks that launchers exist (with HEAD requests), rather than downloading them.
  // Archives that launchers are extracted from are still downloaded, by archiveCache.
  private lazy val headCache: Cache[Task] = new Cache[Task] {
    def fetch: Cache.Fetch[Task]               = cache.fetch
    override def fetchs: Seq[Cache.Fetch[Task]] = cache.fetchs
    def ec                                     = cache.ec
    def file(artifact: Artifact): EitherT[Task, ArtifactError, File] =
      EitherT(Task.delay {
        urlExists(artifact.url) match {
          case Right(true)  => Right(placeholder)
          case Right(false) => Left(new ArtifactError.NotFound(artifact.url))
          case Left(err)    => Left(new ArtifactError.DownloadError(s"${artifact.url}: $err", None))
        }
      })
  }

  private enum Failure {
    case NotFound(urls: Seq[String])
    case Error(message: String)
  }

  // Looks for the prebuilt launcher of desc on a platform, the same way as cs does
  private def find(desc: AppDescriptor, target: Target): Option[Failure] =
    try
      PrebuiltApp.get(
        desc,
        headCache,
        archiveCache,
        verbosity = 0,
        platform = Some(target.platform),
        platformExtensions = target.launcherExtensions,
        preferPrebuilt = false
      ) match {
        case Right(_)   => None
        case Left(Nil)  => None // no prebuilt launcher for this platform
        case Left(urls) => Some(Failure.NotFound(urls.distinct))
      }
    catch {
      case NonFatal(e) => Some(Failure.Error(Report.errorMessage(e)))
    }

  /** Checks the prebuilt launchers of a version, on all platforms
    *
    * @param fallback
    *   if version is the one cs installs when no version is specified, the base descriptor and the
    *   versions cs falls back to (in order) on platforms that version has no launcher for
    */
  def check(
    desc: AppDescriptor,
    version: String,
    issues: Seq[KnownIssue],
    report: Report,
    fallback: Option[(AppDescriptor, Seq[String])] = None
  ): Unit = {
    val ver = Version(version)
    val results = Descriptors.targets.flatMap { target =>
      val platform = target.platform
      if (issues.exists(_.excludesPlatform(ver, platform))) Nil
      else
        find(desc, target).toSeq.map {
          case Failure.NotFound(urls) =>
            // Like cs, fall back to the previous versions, but only if their launchers are
            // missing too (errors stop the search, like in cs)
            val fallbackVersionOpt = fallback.flatMap { (baseDesc, candidates) =>
              candidates.iterator
                .map(v => v -> find(baseDesc.overrideVersion(v), target))
                .takeWhile(_._2.forall(_.isInstanceOf[Failure.NotFound]))
                .collectFirst { case (v, None) => v }
            }
            fallbackVersionOpt match {
              case Some(v) =>
                (platform, Severity.Warning, s"no prebuilt launcher found, cs falls back to $v")
              case None =>
                (platform, Severity.Error, s"no prebuilt launcher found (looked for ${urls.mkString(", ")})")
            }
          case Failure.Error(msg) =>
            (platform, Severity.Error, s"error checking prebuilt launchers: $msg")
        }
    }
    for (((severity, msg), l) <- results.groupBy(r => (r._2, r._3)).toSeq.sortBy(_._2.head._1)) {
      val line = s"$version (${l.map(_._1).mkString(", ")}): $msg"
      severity match {
        case Severity.Error   => report.error(line)
        case Severity.Warning => report.warning(line)
      }
    }
  }
}
