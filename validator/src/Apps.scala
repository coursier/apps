package validateapps

final case class App(channel: String, name: String, path: os.Path) {
  def relPath: String = path.relativeTo(Apps.root).toString
}

object Apps {

  val root     = os.pwd
  val channels = Seq("apps", "apps-contrib")

  lazy val all: Seq[App] = channels.flatMap { channel =>
    os.list(root / channel / "resources")
      .filter(p => os.isFile(p) && p.last.endsWith(".json"))
      .sortBy(_.last)
      .map(p => App(channel, p.last.stripSuffix(".json"), p))
  }
  lazy val byName: Map[String, App] = all.map(app => app.name -> app).toMap

  /** Apps passed on the command-line, either by name or by descriptor path */
  def fromArgs(args: Seq[String]): Either[String, Seq[App]] =
    args.foldLeft[Either[String, Seq[App]]](Right(Vector.empty)) { (acc, input) =>
      acc.flatMap { apps =>
        val appOpt =
          if (input.endsWith(".json")) {
            val path = os.Path(input, root)
            all.find(_.path == path).toRight(s"Not an app descriptor: $input")
          }
          else
            byName.get(input).toRight(s"Unknown app: $input")
        appOpt.map(apps :+ _)
      }
    }
}
