package validateapps

import scala.collection.mutable

enum Severity {
  case Error, Warning
}

/** Messages about an app, printed once it's been validated */
final class Report(val app: App) {
  private val lines0 = mutable.ListBuffer.empty[(Option[Severity], String)]
  def info(msg: String): Unit    = synchronized(lines0 += None -> msg)
  def error(msg: String): Unit   = synchronized(lines0 += Some(Severity.Error) -> msg)
  def warning(msg: String): Unit = synchronized(lines0 += Some(Severity.Warning) -> msg)
  def lines: Seq[(Option[Severity], String)] = synchronized(lines0.toList)
  def count(severity: Severity): Int         = lines.count(_._1.contains(severity))

  /** Prints straightaway what's being checked */
  def progress(msg: String): Unit = Report.log(s"[${app.name}] $msg")
}

object Report {

  private val onGitHubActions = sys.env.get("GITHUB_ACTIONS").contains("true")

  private def escapeAnnotation(msg: String): String =
    msg.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

  private def formatLine(file: String, severity: Option[Severity], msg: String): String =
    severity match {
      case None => "  " + msg.linesIterator.mkString("\n  ")
      case Some(sev) =>
        val label = sev match {
          case Severity.Error   => "error"
          case Severity.Warning => "warning"
        }
        if (onGitHubActions) s"::$label file=$file::${escapeAnnotation(msg)}"
        else s"  $label: " + msg.linesIterator.mkString("\n    ")
    }

  // shared by apps validated in parallel, so that their output isn't mixed up
  def log(msg: String): Unit = synchronized(println(msg))

  def print(report: Report, onlyIfIssues: Boolean, headerSuffix: String = ""): Unit = {
    val lines = report.lines
    if (!onlyIfIssues || lines.exists(_._1.nonEmpty)) {
      val output = ((report.app.relPath + headerSuffix) +: lines.map { case (sev, msg) =>
        formatLine(report.app.relPath, sev, msg)
      }).mkString("\n")
      log(output)
    }
  }

  def errorMessage(t: Throwable): String = {
    val msg = Option(t.getMessage).getOrElse(t.toString)
    if (msg.length > 2000) msg.take(2000) + "…" else msg
  }
}
