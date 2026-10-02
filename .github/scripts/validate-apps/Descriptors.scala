package validateapps

import coursier.install.{AppDescriptor, InstallDir, Platform}
import coursier.version.VersionInterval

/** The base descriptor of an app, or one of its version overrides, applied to the base descriptor */
final case class Rule(label: String, interval: Option[VersionInterval], desc: AppDescriptor)

/** A system cs runs on, as described by the os.name and os.arch Java properties */
final case class Target(osName: String, osArch: String) {
  // computed by coursier, the same way as when cs installs an app
  def platform: String =
    Platform.get(osName, osArch).getOrElse {
      sys.error(s"coursier doesn't support $osName / $osArch")
    }
  def launcherExtensions: Seq[String] =
    InstallDir.platformExtensions(osName)
}

object Descriptors {

  val targets: Seq[Target] =
    for {
      osName <- Seq("Linux", "Mac OS X", "Windows 11")
      osArch <- Seq("amd64", "aarch64")
    } yield Target(osName, osArch)

  // Platforms coursier looks for prebuilt launchers for (x86_64-pc-linux, aarch64-apple-darwin, …)
  val platforms: Seq[String] = targets.map(_.platform)

  def rules(desc: AppDescriptor): Seq[Rule] =
    Rule("base descriptor", None, desc.copy(versionOverrides = Nil)) +:
      desc.versionOverrides.map { o =>
        // Lets coursier apply the override, by making it match any version. The version
        // passed to overrideVersion only ends up in the first dependency, whose version
        // isn't used here.
        val desc0 = desc
          .copy(versionOverrides = Seq(o.copy(versionRange0 = VersionInterval.zero)))
          .overrideVersion("0")
          .copy(versionOverrides = Nil)
        Rule(s"version override ${o.versionRange0.repr}", Some(o.versionRange0), desc0)
      }
}
