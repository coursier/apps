import $ivy.`org.scalameta::mdoc:2.1.5`
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
  .withIn(Paths.get("temp"))
  .withOut(Paths.get(""))
  .withStringModifiers(List(new ListModifier))
  .withNoLinkHygiene(true)

// NOTE: (ckipp01) This is sort of a hack, but I don't know a different way to
// this with mdoc having the template at the root and needed it to be renamed
// and then put back into the root. This is a hack around that :/
mkdir! pwd/'temp
mv(pwd/"README.md.template", pwd/'temp/"README.md")
mdoc.Main.process(mdocSettings)
mv(pwd/'temp/"README.md", pwd/"README.md.template")
rm! pwd/'temp
