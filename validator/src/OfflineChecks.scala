package validateapps

import coursier.install.{AppDescriptor, LauncherType, RawAppDescriptor}

import javax.lang.model.SourceVersion

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

object OfflineChecks {

  private inline def fieldNames[T](using m: Mirror.ProductOf[T]): Set[String] =
    constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toSet

  // The keys coursier reads, which are the fields of its descriptor classes
  private val commentKey   = "descriptor-comments"
  private val topLevelKeys = fieldNames[RawAppDescriptor] + commentKey
  private val overrideKeys = fieldNames[RawAppDescriptor.RawVersionOverride]

  /** Checks an app descriptor, and returns it if coursier can read it */
  def apply(app: App, report: Report): Option[AppDescriptor] = {
    val content = os.read(app.path)
    val jsonOpt =
      try Some(ujson.read(content))
      catch {
        case e: ujson.ParseException =>
          report.error(s"invalid JSON: ${e.getMessage}")
          None
      }
    jsonOpt.flatMap {
      case obj: ujson.Obj =>
        checkKeys(obj, topLevelKeys, "", report)
        for {
          arr         <- obj.value.get("versionOverrides").collect { case a: ujson.Arr => a }
          (elem, idx) <- arr.value.zipWithIndex
          overrideObj <- Some(elem).collect { case o: ujson.Obj => o }
        } checkKeys(overrideObj, overrideKeys, s"versionOverrides[$idx]: ", report)

        RawAppDescriptor.parse(content) match {
          case Left(err) =>
            report.error(s"coursier can't decode this descriptor: $err")
            None
          case Right(raw) =>
            checkRawDescriptor(raw, report)
            raw.appDescriptor.toEither match {
              case Left(errors) =>
                for (err <- errors.toList)
                  report.error(err)
                None
              case Right(desc) =>
                checkDescriptor(desc, report)
                Some(desc)
            }
        }
      case _ =>
        report.error("expected a JSON object at the root")
        None
    }
  }

  private def checkKeys(obj: ujson.Obj, allowed: Set[String], where: String, report: Report): Unit =
    for (key <- obj.value.keys if !allowed(key))
      report.error(
        s"${where}unknown key $key, that coursier ignores " +
          s"(expected one of ${allowed.toSeq.sorted.mkString(", ")})"
      )

  private def checkRawDescriptor(raw: RawAppDescriptor, report: Report): Unit = {
    def checkLevel(
      where: String,
      launcherType: String,
      prebuilt: Option[String],
      prebuiltBinaries: Map[String, String]
    ): Unit = {
      if (prebuilt.exists(_.nonEmpty) || prebuiltBinaries.nonEmpty)
        for (lt <- LauncherType.parse(launcherType).toOption if !lt.isNative)
          report.warning(
            s"$where: prebuilt launchers are ignored with launcher type $launcherType " +
              "(use graalvm-native-image, scala-native, or prebuilt)"
          )
      for (platform <- prebuiltBinaries.keys.toSeq.sorted if !Descriptors.platforms.contains(platform))
        report.error(
          s"$where: unknown platform $platform in prebuiltBinaries " +
            s"(expected one of ${Descriptors.platforms.mkString(", ")})"
        )
    }

    if (raw.dependencies.isEmpty)
      report.error("no dependencies (the first dependency is the one the app version applies to)")
    checkLevel("base descriptor", raw.launcherType, raw.prebuilt, raw.prebuiltBinaries)
    for ((o, idx) <- raw.versionOverrides.zipWithIndex) {
      val where = s"""versionOverrides[$idx] ("${o.versionRange}")"""
      checkLevel(
        where,
        o.launcherType.getOrElse(raw.launcherType),
        o.prebuilt,
        o.prebuiltBinaries.getOrElse(Map.empty)
      )
      if (o == RawAppDescriptor.RawVersionOverride(o.versionRange))
        report.warning(s"$where: doesn't override anything")
      if (o.dependencies.exists(_.isEmpty))
        report.error(s"$where: empty dependencies")
    }
    for (repo <- raw.repositories ++ raw.versionOverrides.flatMap(_.repositories.toSeq.flatten))
      if (repo.startsWith("bintray:") || repo.contains("dl.bintray.com"))
        report.error(s"repository $repo: Bintray was shut down in 2021")
  }

  private def checkDescriptor(desc: AppDescriptor, report: Report): Unit =
    for (rule <- Descriptors.rules(desc)) {
      // main classes, as parsed by coursier (that handles the 'Foo?' syntax)
      for (mainClass <- rule.desc.mainClass ++ rule.desc.defaultMainClass if !SourceVersion.isName(mainClass))
        report.error(s"""${rule.label}: invalid main class "$mainClass"""")
      if (
        rule.desc.launcherType == LauncherType.Prebuilt &&
        rule.desc.prebuiltLauncher.isEmpty &&
        rule.desc.prebuiltBinaries.isEmpty
      )
        report.error(s"${rule.label}: launcher type is prebuilt, but no prebuilt launcher URL is specified")
    }
}
