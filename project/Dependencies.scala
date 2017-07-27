import sbt._

object Dependencies {

  val circeVersion        = "0.7.0"
  val kafkaHelpersVersion = "2.11"

  val all = Seq(
    "com.typesafe.akka"          %% "akka-stream-kafka"         % "0.16",
    "com.typesafe.akka"          %% "akka-slf4j"                % "2.3.14",
    "ch.qos.logback"             % "logback-classic"            % "1.1.7",
    "io.circe"                   %% "circe-core"                % circeVersion,
    "io.circe"                   %% "circe-generic-extras"      % circeVersion,
    "io.circe"                   %% "circe-parser"              % circeVersion,
    "io.circe"                   %% "circe-generic"             % circeVersion,
    "me.moocar"                  % "logback-gelf"               % "0.2",
    "com.github.pureconfig"      %% "pureconfig"                % "0.7.2",
    "eu.timepit"                 %% "refined-pureconfig"        % "0.8.0",
    "org.apache.kafka"           %% "kafka"                     % "0.10.2.1",
    "io.logz.logback"            % "logzio-logback-appender"    % "1.0.11",
    "com.ovoenergy"              %% "comms-kafka-helpers"       % kafkaHelpersVersion,
    "com.ovoenergy"              %% "comms-kafka-test-helpers"  % kafkaHelpersVersion,
    "org.typelevel"              %% "cats-core"                 % "0.9.0",
    "com.squareup.okhttp3"       % "okhttp"                     % "3.4.2",
    "eu.timepit"                 %% "refined"                   % "0.8.0",
    "org.mockito"                % "mockito-all"                % "1.10.19" % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % "1.1.4" % Test,
    "org.scalatest"              %% "scalatest"                 % "3.0.1" % Test,
    "org.scalacheck"             %% "scalacheck"                % "1.13.4" % Test,
    "org.mock-server"            % "mockserver-client-java"     % "3.10.4" % Test
  )
}
