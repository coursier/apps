package validateapps

import coursier.cache.{ArchiveCache, ArtifactError, Cache, FileCache}
import coursier.install.AppDescriptor
import coursier.install.internal.PrebuiltApp
import coursier.util.{Artifact, EitherT, Task}
import coursier.version.Version

import java.io.{File, IOException}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

/** Checks that prebuilt launchers exist, letting coursier find them the same way as cs does */
final class Prebuilts(cache: FileCache[Task]) {

  private lazy val archiveCache = ArchiveCache()
  private lazy val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

  private val urlExistsCache = new ConcurrentHashMap[String, Either[String, Boolean]]

  private def urlExists(url: String): Either[String, Boolean] = {
    def attempt(remaining: Int): Either[String, Boolean] = {
      val request = HttpRequest.newBuilder(URI.create(url))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .timeout(java.time.Duration.ofSeconds(60))
        .build()
      val res =
        try {
          val code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
          if (code / 100 == 2) Right(true)
          else if (code == 404 || code == 410) Right(false)
          else Left(s"HTTP $code")
        }
        catch {
          case e: IOException => Left(e.toString)
        }
      if (res.isLeft && remaining > 0) {
        Thread.sleep(2000L)
        attempt(remaining - 1)
      }
      else res
    }
    urlExistsCache.computeIfAbsent(url, _ => attempt(2))
  }

  // Stands for launchers that exist, but that aren't downloaded
  private lazy val placeholder: File = {
    val f = File.createTempFile("validate-apps", ".placeholder")
    f.deleteOnExit()
    f
  }

  // Only checks that launchers exist (with HEAD requests), rather than downloading them.
  // Archives that launchers are extracted from are still downloaded, by archiveCache.
  private lazy val headCache: Cache[Task] = new Cache[Task] {
    def fetch: Cache.Fetch[Task]               = cache.fetch
    override def fetchs: Seq[Cache.Fetch[Task]] = cache.fetchs
    def ec                                     = cache.ec
    def file(artifact: Artifact): EitherT[Task, ArtifactError, File] =
      EitherT(Task.delay {
        urlExists(artifact.url) match {
          case Right(true)  => Right(placeholder)
          case Right(false) => Left(new ArtifactError.NotFound(artifact.url))
          case Left(err)    => Left(new ArtifactError.DownloadError(s"${artifact.url}: $err", None))
        }
      })
  }

  /** Checks the prebuilt launchers of a version, on all platforms */
  def check(desc: AppDescriptor, version: String, issues: Seq[KnownIssue], report: Report): Unit = {
    val ver = Version(version)
    val failures = Descriptors.targets.flatMap { target =>
      val platform = target.platform
      if (issues.exists(_.excludesPlatform(ver, platform))) Nil
      else {
        val failureOpt =
          try
            PrebuiltApp.get(
              desc,
              headCache,
              archiveCache,
              verbosity = 0,
              platform = Some(platform),
              platformExtensions = target.launcherExtensions,
              preferPrebuilt = false
            ) match {
              case Right(_)  => None
              case Left(Nil) => None // no prebuilt launcher for this platform
              case Left(urls) =>
                Some(s"no prebuilt launcher found (looked for ${urls.distinct.mkString(", ")})")
            }
          catch {
            case NonFatal(e) =>
              Some(s"error checking prebuilt launchers: ${Report.errorMessage(e)}")
          }
        failureOpt.map(platform -> _).toSeq
      }
    }
    for ((failure, l) <- failures.groupBy(_._2).toSeq.sortBy(_._2.head._1))
      report.error(s"$version (${l.map(_._1).mkString(", ")}): $failure")
  }
}
