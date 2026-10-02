// Validates the app descriptors of the Main and Contrib channels
// (apps/resources/*.json, apps-contrib/resources/*.json).
//
// Descriptors are read with coursier's own descriptor model (coursier-install),
// so that they're interpreted the same way as by 'cs install' / 'cs launch'.
//
// Offline checks (OfflineChecks.scala):
// - the descriptor is a JSON object, that coursier can parse and validate
//   (dependencies, repositories, launcher types, version ranges, no overlapping
//   version overrides, …)
// - it has no keys unknown to coursier (coursier silently ignores those, so that
//   typos go unnoticed), except "descriptor-comments", that can hold comments
// - its main classes are valid class names, and prebuiltBinaries only has
//   platforms coursier knows about
// - when the launcher type is "prebuilt", prebuilt launcher URLs are specified,
//   for the base descriptor and every version override, and prebuilt launcher
//   URLs aren't specified along with launcher types that ignore them
// What coursier expects (known keys, platforms, how version overrides apply, …)
// is read from coursier itself, rather than duplicated here, so that bumping
// the coursier version is enough to follow its changes.
//
// Online checks (OnlineChecks.scala, unless --offline is passed):
// - lists the published versions of the app, and splits them according to the
//   version override that applies to them (versions matched by no override use
//   the base descriptor). Every version override must match at least one
//   published version.
// - for a sample of the versions handled by each version override and by the
//   base descriptor (the oldest one, the newest one, and the newest stable one,
//   ignoring development builds like nightlies):
//   - fetches the app dependencies (unless the app uses a "prebuilt" launcher)
//   - checks that the main class exists in the class path (or that one can be
//     found in the JAR manifests, if the descriptor doesn't specify one). For
//     apps with prebuilt launchers, issues with dependencies or main classes are
//     only warnings, as those only matter on platforms without a prebuilt launcher.
//   - checks that prebuilt launchers exist, for all platforms, finding them with
//     coursier's own logic (coursier.install.internal.PrebuiltApp). Launchers are
//     only checked with HEAD requests, but archives that launchers are extracted
//     from are downloaded, to check that the launchers are in them.
// - warns about repositories that provide none of the artifacts of the sampled
//   versions
//
// Known problems of former versions (missing launchers, …) are listed in
// validator/known-issues/<app name>.json, as a version range ("versionRange")
// or a list of versions ("versions"), with a "reason". Versions listed there are
// excluded from the samples or, if "platforms" are specified, their prebuilt
// launchers for those platforms aren't checked.
//
// The Mill build (build.mill) has one module per app, whose 'check' command runs
// this on the app, so that Mill's selective execution only validates the apps
// whose descriptor or known issues changed. This can also be run directly:
//   ./mill validator.run                                         # validate all apps
//   ./mill validator.run --offline                               # offline checks only
//   ./mill validator.run scala apps-contrib/resources/bfg.json   # validate some apps only
//   ./mill validator.run --all-versions cs                       # check all versions rather than a sample
//
// --all-versions is slow, but helps finding the versions affected by an issue,
// before adding it to the known issues.

package validateapps

import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object ValidateApps extends CaseApp[Options] {

  private def usageError(msg: String): Nothing = {
    System.err.println(msg)
    sys.exit(2)
  }

  def run(options: Options, remainingArgs: RemainingArgs): Unit = {

    val selection = remainingArgs.all
    if (options.parallelism <= 0)
      usageError(s"Invalid parallelism: ${options.parallelism}")

    val selectedApps =
      if (selection.isEmpty) Apps.all
      else Apps.fromArgs(selection).fold(usageError, identity)

    val what = if (options.offline) "offline checks" else "offline and online checks"
    if (selectedApps.length == Apps.all.length)
      Report.log(s"Running $what on all ${selectedApps.length} app descriptors")
    else
      Report.log(
        s"Running $what on ${selectedApps.length} app descriptor(s): " +
          selectedApps.map(_.name).mkString(", ")
      )

    // when validating all apps, also check that known issues files correspond to apps
    val orphanKnownIssues = if (selection.isEmpty) KnownIssues.orphans() else Nil
    for (path <- orphanKnownIssues)
      Report.log(s"error: ${path.relativeTo(Apps.root)}: known issues of an app that doesn't exist")

    val onlineChecksOpt =
      if (options.offline) None
      else Some(new OnlineChecks(options.allVersions))

    // apps are validated in parallel, so they can start and finish in different orders
    val total    = selectedApps.length
    val started  = new AtomicInteger
    val finished = new AtomicInteger

    def validate(app: App): Report = {
      val report = new Report(app)
      val index  = started.incrementAndGet()
      // offline checks are fast, only say which apps are being checked for online ones
      if (onlineChecksOpt.nonEmpty)
        report.progress(s"validating ${app.relPath} (starting $index/$total)")
      try {
        val descOpt = OfflineChecks(app, report)
        val issues = KnownIssues.load(app) match {
          case Right(issues) => issues
          case Left(errors) =>
            for (err <- errors)
              report.error(s"${KnownIssues.path(app).relativeTo(Apps.root)}: $err")
            Nil
        }
        for (desc <- descOpt; onlineChecks <- onlineChecksOpt)
          onlineChecks(app, desc, issues, report)
      }
      catch {
        case NonFatal(e) =>
          report.error(s"unexpected error: ${Report.errorMessage(e)}")
      }
      report
    }

    val pool = Executors.newFixedThreadPool(
      options.parallelism,
      { (r: Runnable) =>
        val t = new Thread(r)
        t.setDaemon(true)
        t
      }
    )
    val reports = {
      given ExecutionContext = ExecutionContext.fromExecutorService(pool)
      val f = Future.traverse(selectedApps) { app =>
        Future(validate(app)).map { report =>
          // in offline mode, only list apps with issues
          val done = finished.incrementAndGet()
          Report.print(report, onlyIfIssues = options.offline, headerSuffix = s" (validated $done/$total)")
          report
        }
      }
      Await.result(f, Duration.Inf)
    }
    pool.shutdown()

    val errorCount   = reports.map(_.count(Severity.Error)).sum + orphanKnownIssues.length
    val warningCount = reports.map(_.count(Severity.Warning)).sum
    val mode         = if (options.offline) "offline checks only" else "offline and online checks"
    println()
    println(
      s"Validated ${reports.length} app descriptor(s) ($mode): $errorCount error(s), $warningCount warning(s)"
    )
    sys.exit(if (errorCount > 0) 1 else 0)
  }
}
