package validateapps

import coursier.install.AppDescriptor
import coursier.version.{Version, VersionInterval, VersionParse}

import scala.collection.mutable
import scala.util.control.NonFatal

// Known issues apply either to a version range, or to a list of versions
//
// unboundedRangeOpt: where the issue is and its version range, if that range has no upper bound
final case class KnownIssue(
  matches: Version => Boolean,
  platforms: Option[Set[String]],
  unboundedRangeOpt: Option[String] = None
) {
  def excludesVersion(version: Version): Boolean =
    platforms.isEmpty && matches(version)
  def excludesPlatform(version: Version, platform: String): Boolean =
    platforms.exists(_.contains(platform)) && matches(version)
}

/** Known issues of former app versions, in one file per app, so that changing those of an app
  * only re-validates that app
  */
object KnownIssues {

  val dir = Apps.root / "validator/known-issues"

  def path(app: App): os.Path = dir / s"${app.name}.json"

  /** Known issues files that don't correspond to any app */
  def orphans(): Seq[os.Path] =
    if (os.isDir(dir))
      os.list(dir).filter(p => !Apps.byName.contains(p.last.stripSuffix(".json")))
    else
      Nil

  def load(app: App): Either[Seq[String], Seq[KnownIssue]] = {
    val path0 = path(app)
    if (os.exists(path0)) read(os.read(path0)) else Right(Nil)
  }

  def read(content: String): Either[Seq[String], Seq[KnownIssue]] = {
    val errors = mutable.ListBuffer.empty[String]
    val entries =
      try
        ujson.read(content).arr.toSeq.zipWithIndex.flatMap { case (issue, idx) =>
          val where = s"[$idx]"
          val obj   = issue.obj
          for (key <- obj.keys if !Set("versionRange", "versions", "platforms", "reason").contains(key))
            errors += s"$where: unknown key $key"
          if (obj.get("reason").flatMap(_.strOpt).forall(_.trim.isEmpty))
            errors += s"$where: missing reason"
          val platformsOpt = obj.get("platforms").map(_.arr.map(_.str).toSet)
          for (p <- platformsOpt.toSeq.flatten if !Descriptors.platforms.contains(p))
            errors += s"$where: unknown platform $p (expected one of ${Descriptors.platforms.mkString(", ")})"
          (obj.get("versionRange").map(_.str), obj.get("versions").map(_.arr.map(_.str).toSet)) match {
            case (Some(range), None) =>
              VersionParse.versionInterval(range) match {
                case Some(itv) =>
                  val unboundedRangeOpt =
                    if (itv.to.isEmpty) Some(s"""$where: versionRange "$range"""") else None
                  Seq(KnownIssue(itv.contains, platformsOpt, unboundedRangeOpt))
                case None =>
                  errors += s"""$where: invalid versionRange "$range""""
                  Nil
              }
            case (None, Some(versions)) =>
              val versions0 = versions.map(Version(_))
              Seq(KnownIssue(versions0.contains, platformsOpt))
            case _ =>
              errors += s"$where: expected either versionRange or versions"
              Nil
          }
        }
      catch {
        case NonFatal(e) =>
          errors += s"malformed file: ${Report.errorMessage(e)}"
          Nil
      }
    if (errors.isEmpty) Right(entries) else Left(errors.toList)
  }

  /** Checks that known issues with no upper bound only apply to apps installing a fixed version
    *
    * Such issues also apply to versions not published yet, which apps installing a latest.*
    * version by default would install once they're published.
    */
  def checkUnboundedRanges(app: App, desc: AppDescriptor, issues: Seq[KnownIssue], report: Report): Unit = {
    val constraintOpt = desc.dependencies.headOption.map(_.versionConstraint)
    val fixedVersion = constraintOpt.exists { c =>
      c.latest.isEmpty && c.preferred.nonEmpty && c.interval == VersionInterval.zero
    }
    if (!fixedVersion)
      for (issue <- issues; range <- issue.unboundedRangeOpt) {
        val versionDesc = constraintOpt.fold("")(c => s" (${c.asString})")
        report.error(
          s"${path(app).relativeTo(Apps.root)}: $range has no upper bound, so the app descriptor " +
            s"must install a fixed version, rather than a version range or a latest.* one$versionDesc"
        )
      }
  }
}
