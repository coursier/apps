# Coursier Apps

[![Build Status](https://travis-ci.org/coursier/apps.svg?branch=master)](https://travis-ci.org/coursier/apps)
[![Maven Central](https://img.shields.io/maven-central/v/io.get-coursier/apps.svg)](https://maven-badges.herokuapp.com/maven-central/io.get-coursier/apps)

This repository holds the apps in the Main and Contrib channels for Coursier. You can find information about
creating your own application to be installed with `cs` [here on the website](https://get-coursier.io/docs/cli-install.html#creating-your-own-applications).

## Main
These are the apps in the default main JAR-based channel, `io.get-coursier:app` which is used with `cs install`

 - almond
 - ammonite
 - bloop
 - coursier
 - cs
 - dotty-repl
 - echo-graalvm
 - echo-java
 - echo-native
 - giter8
 - mdoc
 - metac
 - metals-emacs
 - metap-native
 - metap
 - mill-interactive
 - mill
 - sbt-launcher
 - scala
 - scalac
 - scaladoc
 - scalafix
 - scalafmt
 - scalap
 - scalapbc

## Contrib
These apps are available by passing `--contrib` to the `cs install` command.
Feel free to send in a PR to add your application here!

 - authors
 - bfg
 - clojure
 - frege
 - groovy-shell
 - groovy
 - jfiglet
 - jruby
 - jython
 - kafka-console-consumer
 - proguard-retrace
 - proguard
 - rhino
 - spark-repl
 - sqlline
 - wiremock

### Updating the readme
This readme is auto-generated by the `generate-readme.sc` Ammonite script in the root of this directory. Please don't
update the readme directy, but rather update the script.
