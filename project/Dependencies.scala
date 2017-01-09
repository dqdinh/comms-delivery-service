import sbt._

object Dependencies {

  val kafkaMessagesVersion = "0.0.14"

  def all() = Seq(
    "com.typesafe.akka"           %% "akka-stream-kafka"              % "0.12",
    "com.typesafe.akka"           %% "akka-slf4j"                     % "2.3.14",
    "ch.qos.logback"               % "logback-classic"                % "1.1.7",
    "io.circe"                    %% "circe-core"                     % "0.6.0",
    "io.circe"                    %% "circe-generic-extras"           % "0.6.0",
    "io.circe"                    %% "circe-parser"                   % "0.6.0",
    "io.circe"                    %% "circe-generic"                  % "0.6.0",
    "me.moocar"                    % "logback-gelf"                   % "0.2",
    "net.cakesolutions"           %% "scala-kafka-client"             % "0.10.0.0",
    "io.logz.logback"              % "logzio-logback-appender"        % "1.0.11",
    "com.ovoenergy"               %% "comms-kafka-messages"           % kafkaMessagesVersion,
    "com.ovoenergy"               %% "comms-kafka-serialisation"      % kafkaMessagesVersion,
    "org.typelevel"               %% "cats-core"                      % "0.8.0",
    "com.squareup.okhttp3"         % "okhttp"                         % "3.4.2",
    "eu.timepit"                  %% "refined"                        % "0.6.1",
    "org.mockito"                  % "mockito-all"                    % "1.10.19"     %   Test,
    "com.github.alexarchambault"  %% "scalacheck-shapeless_1.13"      % "1.1.4"       %   Test,
    "org.scalatest"               %% "scalatest"                      % "3.0.1"       %   Test,
    "org.scalacheck"              %% "scalacheck"                     % "1.13.4"      %   Test,
    "org.apache.kafka"            %% "kafka"                          % "0.10.0.1"    %   Test,
    "org.mock-server" % "mockserver-client-java" % "3.10.4" % Test
  )
}
