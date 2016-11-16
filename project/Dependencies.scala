import sbt._

object Dependencies {

  def all() = Seq(
    "com.typesafe.akka"       %% "akka-stream-kafka"            % "0.12",
    "com.typesafe.akka"       %% "akka-slf4j"                   % "2.3.14",
    "ch.qos.logback"           % "logback-classic"              % "1.1.7",
    "io.circe"                %% "circe-core"                   % "0.6.0",
    "io.circe"                %% "circe-generic-extras"         % "0.6.0",
    "io.circe"                %% "circe-parser"                 % "0.6.0",
    "io.circe"                %% "circe-generic"                % "0.6.0",
    "me.moocar"                % "logback-gelf"                 % "0.2",
    "io.logz.logback"          % "logzio-logback-appender"      % "1.0.11",
    "org.scalatest"           %% "scalatest"                    % "2.2.6"   % "test"
  )

}