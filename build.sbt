name := "scabot"

lazy val commonSettings = Seq(
  version := "0.0.0",
  organization := "com.typesafe",

  scalaVersion := "2.11.5",

  libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.8",
  libraryDependencies += "com.typesafe.akka" %% "akka-kernel" % "2.3.8",
  libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "1.0-M2",
  libraryDependencies += "io.spray" %%  "spray-json" % "1.3.1",
  libraryDependencies += "com.typesafe.akka" %% "akka-http-experimental" % "1.0-M2",

  mainClass in Compile := Some("scabot.server.Scabot")
)

enablePlugins(AkkaAppPackaging)


lazy val core    = project                             settings (commonSettings: _*)
lazy val github  = project dependsOn (core)            settings (commonSettings: _*)
lazy val jenkins = project dependsOn (core)            settings (commonSettings: _*)
lazy val server  = project dependsOn (github, jenkins) settings (commonSettings: _*)

