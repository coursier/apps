
inThisBuild(List(
  organization := "io.get-coursier",
  homepage := Some(url("https://github.com/coursier/apps")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "",
      url("https://github.com/alexarchambault")
    )
  )
))

lazy val shared = Def.settings(
  Compile / resourceDirectory := baseDirectory.value / "resources",

  // pura Java
  crossPaths := false,
  autoScalaLibrary := false
)

lazy val apps = project
  .settings(shared)

lazy val `apps-contrib` = project
  .settings(shared)

shared
skip.in(publish) := true
