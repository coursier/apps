
inThisBuild(List(
  organization := "io.get-coursier",
  homepage := Some(uri("https://github.com/coursier/apps")),
  licenses := List(License.Apache2),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "",
      uri("https://github.com/alexarchambault")
    )
  )
))

// Bare settings are common settings in sbt 2.x: they're injected in all
// subprojects (including the root one).
Compile / resourceDirectory := baseDirectory.value / "resources"

// pura Java
crossPaths := false
autoScalaLibrary := false

lazy val apps = project

lazy val `apps-contrib` = project

LocalRootProject / publish / skip := true