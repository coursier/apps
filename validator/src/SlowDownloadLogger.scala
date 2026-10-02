package validateapps

import coursier.cache.CacheLogger
import coursier.util.Artifact

import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledFuture, TimeUnit}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Cache logger that only logs slow downloads
  *
  * Prints "Downloading …" for downloads still running after `threshold`, then "Downloaded …" (or
  * "Failed to download …") once they're done. Quicker downloads aren't logged, so that the output
  * says what's taking time, without listing everything that's downloaded.
  */
final class SlowDownloadLogger(threshold: FiniteDuration = 5.seconds) extends CacheLogger {

  private val scheduler = Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
    val t = new Thread(r, "slow-download-logger")
    t.setDaemon(true)
    t
  }

  private final class Download(val start: Long) {
    // guarded by this
    var logged = false
    var done   = false
    @volatile var task: ScheduledFuture[?] = null
  }

  private val downloads = new ConcurrentHashMap[String, Download]

  override def downloadingArtifact(url: String, artifact: Artifact): Unit = {
    val download = new Download(System.nanoTime())
    downloads.put(url, download)
    val logStart: Runnable = { () =>
      download.synchronized {
        if (!download.done) {
          download.logged = true
          Report.log(s"Downloading $url")
        }
      }
    }
    download.task = scheduler.schedule(logStart, threshold.toMillis, TimeUnit.MILLISECONDS)
  }

  override def downloadedArtifact(url: String, success: Boolean): Unit =
    for (download <- Option(downloads.remove(url))) {
      Option(download.task).foreach(_.cancel(false))
      download.synchronized {
        download.done = true
        if (download.logged) {
          val seconds = (System.nanoTime() - download.start) / 1000000000L
          val what    = if (success) "Downloaded" else "Failed to download"
          Report.log(s"$what $url (${seconds}s)")
        }
      }
    }
}
