package validateapps

final case class App(channel: String, name: String, path: os.Path) {
  def relPath: String = path.relativeTo(Apps.root).toString
}

object Apps {

  val root         = os.pwd
  val channels     = Seq("apps", "apps-contrib")
  val validatorDir = root / ".github/scripts/validate-apps"

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

  /** Apps whose descriptor or known issues changed since a git reference (all apps if the
    * validator itself changed)
    */
  def changedSince(ref: String): Seq[App] = {
    val mergeBase = git("merge-base", ref, "HEAD").head
    // compares the merge base to the working tree, so that uncommitted changes are taken into account
    val changed =
      (git("diff", "--name-only", mergeBase) ++ git("ls-files", "--others", "--exclude-standard"))
        .map(p => root / os.RelPath(p))
        .toSet
    if (changed.exists(_.startsWith(validatorDir)))
      all
    else {
      // re-validate the apps whose known issues changed
      val knownIssuesApps =
        if (changed.contains(KnownIssues.path)) {
          val relPath = KnownIssues.path.relativeTo(root).toString
          val before =
            os.proc("git", "show", s"$mergeBase:$relPath")
              .call(cwd = root, check = false, stderr = os.Pipe) match {
              case r if r.exitCode == 0 => ujson.read(r.out.text()).obj.toMap
              case _                    => Map.empty[String, ujson.Value]
            }
          val after = ujson.read(os.read(KnownIssues.path)).obj.toMap
          (before.keySet ++ after.keySet).filter(k => before.get(k) != after.get(k))
        }
        else
          Set.empty[String]
      all.filter(app => changed.contains(app.path) || knownIssuesApps.contains(app.name))
    }
  }

  private def git(args: String*): Seq[String] =
    os.proc("git" +: args).call(cwd = root).out.lines()
}
