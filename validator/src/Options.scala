package validateapps

import caseapp.*

@ProgName("./mill validator.run")
@ArgsName("app name or descriptor path")
final case class Options(
  @HelpMessage("Only run offline checks")
    offline: Boolean = false,
  @HelpMessage("Check all versions of the apps, rather than a sample of them (slow)")
    allVersions: Boolean = false,
  @HelpMessage("Number of apps validated in parallel")
    parallelism: Int = 4
)
