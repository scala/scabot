import com.typesafe.sbt.SbtNativePackager._

name := "scabot"

organization in ThisBuild := "com.typesafe"
version      in ThisBuild := "0.0.0"
scalaVersion in ThisBuild := "2.11.5"

// common dependencies
libraryDependencies in ThisBuild ++= Seq(
  "com.typesafe.akka" %% "akka-actor"                        % "2.3.8",
  "com.typesafe.akka" %% "akka-kernel"                       % "2.3.8",
//  "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "1.0-M2",
//  "com.typesafe.akka" %% "akka-http-experimental"            % "1.0-M2",
  "io.spray"          %%  "spray-can"             % "1.3.2",
  "io.spray"          %%  "spray-client"          % "1.3.2",
  "io.spray"          %%  "spray-routing"         % "1.3.2",
  "io.spray"          %%  "spray-http"         % "1.3.2",
  "io.spray"          %%  "spray-httpx"         % "1.3.2",
  "io.spray"          %%  "spray-json"          % "1.3.1"
)

// TODO: why do we need to define this explicitly
// this is the root project, aggregating all sub projects
lazy val root = Project(
    id = "root",
    base = file("."),
    // configure your native packaging settings here
    settings = packageArchetype.akka_application ++ Seq(
        maintainer := "Adriaan Moors <adriaan@typesafe.com>",
        packageDescription := "Scala Bot",
        packageSummary := "Automates stuff on Github",
        // entrypoint
        mainClass in Compile := Some("scabot.server.Scabot")
    ),
    // always run all commands on each sub project
    aggregate = Seq(core, github, jenkins, server)
) dependsOn(core, github, jenkins, server) // this does the actual aggregation

// enablePlugins(AkkaAppPackaging)

lazy val core    = project
lazy val github  = project dependsOn (core)
lazy val jenkins = project dependsOn (core)
lazy val server  = project dependsOn (github, jenkins)

