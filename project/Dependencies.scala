import sbt._

object Dependencies {

  val okHttp      = "com.squareup.okhttp3" % "okhttp"                % "3.4.2"
  val refined     = "eu.timepit"           %% "refined"              % "0.8.0"
  val scanamo     = "com.gu"               %% "scanamo"              % "1.0.0-M3"
  val dynamoDbSdk = "com.amazonaws"        % "aws-java-sdk-dynamodb" % "1.11.225"
  val akkaSlf4J   = "com.typesafe.akka"    %% "akka-slf4j"           % "2.4.18"

  val scalaTest  = "org.scalatest"   %% "scalatest"             % "3.0.1"
  val mockServer = "org.mock-server" % "mockserver-client-java" % "3.12"

  object fs2 {
    private val fs2Version            = "0.10.2"
    private val fs2KafkaClientVersion = "0.1.9"

    lazy val core = "co.fs2" %% "fs2-core" % fs2Version
    lazy val io   = "co.fs2" %% "fs2-io"   % fs2Version

    lazy val kafkaClient = "com.ovoenergy" %% "fs2-kafka-client" % fs2KafkaClientVersion
  }

  object kafka {
    "org.apache.kafka" % "kafka-clients" % "2.0.0"
  }

  object cats {
    val core   = "org.typelevel" %% "cats-core"     % "1.0.1"
    val effect = "org.typelevel" %% "cats-effect"   % "0.10"
    val mtl    = "org.typelevel" %% "cats-mtl-core" % "0.2.1"
  }

  object circe {
    private val circeVersion = "0.9.0"

    val core          = "io.circe" %% "circe-core"           % circeVersion
    val genericExtras = "io.circe" %% "circe-generic-extras" % circeVersion
    val parser        = "io.circe" %% "circe-parser"         % circeVersion
    val generic       = "io.circe" %% "circe-generic"        % circeVersion
  }

  object kafkaSerialization {
    private val kafkaSerializationVersion = "0.3.6"

    lazy val core   = "com.ovoenergy" %% "kafka-serialization-core"   % kafkaSerializationVersion
    lazy val cats   = "com.ovoenergy" %% "kafka-serialization-cats"   % kafkaSerializationVersion
    lazy val avro   = "com.ovoenergy" %% "kafka-serialization-avro"   % kafkaSerializationVersion
    lazy val avro4s = "com.ovoenergy" %% "kafka-serialization-avro4s" % kafkaSerializationVersion
  }

  object ovoEnergy {
    private val kafkaMessagesVersion      = "1.75"
    private val kafkaHelpersVersion       = "3.18"
    private val commsDockerTestkitVersion = "1.8"
    private val commsTemplatesVersion     = "0.25"

    val commsKafkaMessages      = "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion
    val commsKafkaMessagesTests = "com.ovoenergy" %% "comms-kafka-messages" % kafkaMessagesVersion classifier "tests"

    val commsKafkaHelpers     = "com.ovoenergy" %% "comms-kafka-helpers"      % kafkaHelpersVersion
    val commsKafkaTestHelpers = "com.ovoenergy" %% "comms-kafka-test-helpers" % kafkaHelpersVersion
    val commsTemplates        = "com.ovoenergy" %% "comms-templates"          % commsTemplatesVersion
    val dockerKit             = "com.ovoenergy" %% "comms-docker-testkit"     % commsDockerTestkitVersion
  }

  object logging {
    val log4Cats               ="io.chrisdavenport" %% "log4cats-slf4j"         % "0.1.0"
    val logbackClassic        = "ch.qos.logback"    % "logback-classic"         % "1.1.7"
    val logbackGelf           = "me.moocar"         % "logback-gelf"            % "0.2"
    val logzIoLogbackAppender = "io.logz.logback"   % "logzio-logback-appender" % "1.0.11"
  }

  object pureConfig {
    val core    = "com.github.pureconfig" %% "pureconfig"         % "0.7.2"
    val refined = "eu.timepit"            %% "refined-pureconfig" % "0.8.0"
  }

  object scalaCheck {
    val scalacheck = "org.scalacheck"             %% "scalacheck"                % "1.13.4"
    val shapeless  = "com.github.alexarchambault" %% "scalacheck-shapeless_1.13" % "1.1.4"
  }

  object whisk {
    private val version = "0.9.6"

    lazy val scalaTest = "com.whisk" %% "docker-testkit-scalatest"        % version
    lazy val javaImpl  = "com.whisk" %% "docker-testkit-impl-docker-java" % version
  }
}
