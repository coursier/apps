import mill._, scalalib._, publish._

object apps extends JavaModule with PublishModule {
  def publishVersion = T{
    import sys.process._
    val desc = Seq("git", "describe", "--tags")
      .!!
      .trim
      .replace("-g", "+")
      .stripPrefix("v")
    if (desc.contains("+"))
      desc + "-SNAPSHOT"
    else
      desc
  }
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "io.get-coursier",
    url = "https://github.com/coursier/apps",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("apps", "apps"),
    developers = Seq(
      Developer("alexarchambault", "Alexandre Archambault","https://github.com/alexarchambault")
    )
  )
}
