//> using scala 3.3.6
//> using jvm 17
//> using dep com.lihaoyi::os-lib:0.11.8
//> using dep com.lihaoyi::ujson:4.4.3

// Aggregates the app descriptors of each channel (apps/resources/*.json,
// apps-contrib/resources/*.json) into a single JSON file per channel
// (listings/apps.json, listings/apps-contrib.json).
//
// coursier consumes those aggregated files as URL-based channels, see
// https://get-coursier.io/docs/cli-appdescriptors#url-based-channels
//
// Usage:
//   scala-cli run .github/scripts/generate-channels.sc            # (re-)generate the files
//   scala-cli run .github/scripts/generate-channels.sc -- --check # only check they're up-to-date

val check = args.contains("--check")

val root        = os.pwd
val listingsDir = root / "listings"
val channels    = Seq("apps", "apps-contrib")

def channelContent(channel: String): String = {
  val resourcesDir = root / channel / "resources"
  val entries = os.list(resourcesDir)
    .filter(p => os.isFile(p) && p.last.endsWith(".json"))
    .sortBy(_.last)
    .map { path =>
      val name = path.last.stripSuffix(".json")
      val json =
        try ujson.read(os.read(path))
        catch {
          case e: ujson.ParseException =>
            sys.error(s"Error parsing $path: ${e.getMessage}")
        }
      json match {
        case obj: ujson.Obj => name -> (obj: ujson.Value)
        case _              => sys.error(s"$path: expected a JSON object at the root")
      }
    }
  ujson.write(ujson.Obj.from(entries), indent = 2) + System.lineSeparator()
}

var outdated = List.empty[os.Path]

for (channel <- channels) {
  val dest    = listingsDir / s"$channel.json"
  val content = channelContent(channel)
  if (check) {
    val current = if (os.exists(dest)) Some(os.read(dest)) else None
    if (!current.contains(content))
      outdated = dest :: outdated
  }
  else {
    os.write.over(dest, content, createFolders = true)
    System.err.println(s"Wrote $dest")
  }
}

if (outdated.nonEmpty) {
  System.err.println(
    outdated.reverse.map(_.relativeTo(root)).mkString(
      "Outdated channel file(s): ",
      ", ",
      "\nRun 'scala-cli run .github/scripts/generate-channels.sc' to update them."
    )
  )
  sys.exit(1)
}
