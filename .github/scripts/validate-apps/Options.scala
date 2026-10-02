package validateapps

import caseapp.*

@ProgName("scala-cli run .github/scripts/validate-apps --")
@ArgsName("app name or descriptor path")
final case class Options(
  @HelpMessage("Only run offline checks")
    offline: Boolean = false,
  @HelpMessage("Check all versions of the apps, rather than a sample of them (slow)")
    allVersions: Boolean = false,
  @HelpMessage("Only validate the apps whose descriptor or known issues changed since this git reference")
  @ValueDescription("git reference")
    changedSince: Option[String] = None,
  @HelpMessage("Number of apps validated in parallel")
    parallelism: Int = 4
)
