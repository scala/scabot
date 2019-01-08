name := "scabot"

organization in ThisBuild := "com.lightbend"
version      in ThisBuild := "0.1.0"
scalaVersion in ThisBuild := "2.11.8"

scalacOptions in ThisBuild ++=
  Seq("-feature", "-deprecation", "-Xfatal-warnings")

lazy val deps: Seq[sbt.Def.Setting[_]] =  Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor"     % "2.3.16",
    "io.spray"          %% "spray-client"   % "1.3.4",
    "io.spray"          %% "spray-json"     % "1.3.4"
  ))

lazy val amazonDeps: Seq[sbt.Def.Setting[_]] =  Seq(
  libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.9.13")


lazy val core      = project settings (deps: _*)
lazy val github    = project dependsOn (core)
lazy val jenkins   = project dependsOn (core)
lazy val lightbend = project dependsOn (core)
lazy val cli       = project dependsOn (github, lightbend)
lazy val amazon    = project dependsOn (core) settings (amazonDeps: _*)
lazy val server    = project dependsOn (amazon, github, jenkins, lightbend)
