import $ivy.`org.scalameta::mdoc:2.2.0`
import ammonite.ops._
import java.nio.file.Paths
import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class ListModifier() extends StringModifier {
  override val name: String = "list"

  override def process(
      topic: String,
      code: Input,
      reporter: Reporter
  ): String = {
    val targetPaths = ls! pwd/s"${topic}"/'resources
    val targetNames = targetPaths.map(_.baseName).toList
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
