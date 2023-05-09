//> using scala "2.13.10"
//> using lib "org.scalameta::mdoc:2.3.7"
//> using lib "com.lihaoyi::os-lib:0.9.1"

import mdoc.{Reporter, StringModifier}

import java.nio.file.Paths

import scala.meta.inputs.Input

class ListModifier() extends StringModifier {
  override val name: String = "list"

  override def process(
      topic: String,
      code: Input,
      reporter: Reporter
  ): String = {
    val targetPaths = os.list(os.pwd / topic/ "resources")
    val targetNames = targetPaths.map(_.last.stripSuffix(".json")).toList
    targetNames.mkString(" - ", "\n - ", "\n")
  }
}

val mdocSettings = mdoc
  .MainSettings()
  .withIn(Paths.get("README.template.md"))
  .withOut(Paths.get("README.md"))
  .withStringModifiers(List(new ListModifier))
  .withNoLinkHygiene(true)

mdoc.Main.process(mdocSettings)
