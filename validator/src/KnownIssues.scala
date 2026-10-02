package validateapps

import coursier.version.{Version, VersionParse}

import scala.collection.mutable
import scala.util.control.NonFatal

// Known issues apply either to a version range, or to a list of versions
final case class KnownIssue(matches: Version => Boolean, platforms: Option[Set[String]]) {
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
                case Some(itv) => Seq(KnownIssue(itv.contains, platformsOpt))
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
}
