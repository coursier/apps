import mill._, scalalib._, publish._

trait AppsModule extends JavaModule with PublishModule {
  def publishVersion = T {
    appsVersion()
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

def appsVersion(): String = {
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

object apps extends AppsModule
object `apps-contrib` extends AppsModule
