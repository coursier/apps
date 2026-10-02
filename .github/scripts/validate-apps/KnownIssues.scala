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

object KnownIssues {

  val path = Apps.root / ".github/scripts/validate-apps-known-issues.json"

  /** Known issues, by app name */
  def load(): Either[Seq[String], Map[String, Seq[KnownIssue]]] =
    read(if (os.exists(path)) os.read(path) else "{}")

  def read(content: String): Either[Seq[String], Map[String, Seq[KnownIssue]]] = {
    val errors = mutable.ListBuffer.empty[String]
    val entries =
      try
        ujson.read(content).obj.toSeq.map { case (appName, issues) =>
          if (!Apps.byName.contains(appName))
            errors += s"unknown app: $appName"
          appName -> issues.arr.toSeq.zipWithIndex.flatMap { case (issue, idx) =>
            val where = s"$appName[$idx]"
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
        }
      catch {
        case NonFatal(e) =>
          errors += s"malformed file: ${Report.errorMessage(e)}"
          Nil
      }
    if (errors.isEmpty) Right(entries.toMap) else Left(errors.toList)
  }
}
