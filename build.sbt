import Dependencies._

name := "delivery-service"

// Make ScalaTest write test reports that CirceCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF", "-u", testReportsDir)

lazy val ServiceTest = config("servicetest") extend(Test)
configs(ServiceTest)
inConfig(ServiceTest)(Defaults.testSettings)
(test in ServiceTest) := (test in ServiceTest).dependsOn(publishLocal in Docker).value
inConfig(ServiceTest)(parallelExecution in test := false)

lazy val buildSettings = Seq(
  name                    := "delivery-service",
  organization            := "com.ovoenergy",
  organizationName        := "OVO Energy",
  organizationHomepage    := Some(url("http://www.ovoenergy.com")),
  scalaVersion            := "2.12.4",
  scalacOptions           := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-feature")
)

val dependencies = Seq(
  fs2.core,
  fs2.io,
  fs2.kafkaClient,
  cats.core,
  cats.mtl,
  circe.core,
  circe.generic,
  circe.genericExtras,
  circe.parser,
  ovoEnergy.commsKafkaMessages,
  ovoEnergy.commsKafkaHelpers exclude("com.typesafe.akka", "akka-stream-kafka_2.12"),
  ovoEnergy.dockerKit,
  kafkaSerialization.cats,
  kafkaSerialization.core,
  logging.logbackClassic,
  logging.logbackGelf,
  logging.logzIoLogbackAppender,
  pureConfig.core,
  pureConfig.refined,
  okHttp,
  refined,
  scanamo,
  dynamoDbSdk,
  akkaSlf4J,

  scalaCheck.scalacheck % Test,
  scalaCheck.shapeless % Test,
  whisk.scalaTest % Test,
  whisk.javaImpl % Test,
  ovoEnergy.commsKafkaTestHelpers % Test,
  mockito % Test,
  scalaTest % Test,
  mockServer % Test
)

lazy val service = (project in file("."))
  .settings(
    buildSettings,
    resolvers += Resolver.bintrayRepo("ovotech", "maven"),
    resolvers += Resolver.bintrayRepo("cakesolutions", "maven"),
    resolvers += "confluent-release" at "http://packages.confluent.io/maven/",
    libraryDependencies ++= dependencies,
    commsPackagingHeapSize := 512,
    commsPackagingMaxMetaspaceSize := 128
  ).enablePlugins(JavaServerAppPackaging, DockerPlugin)

startDynamoDBLocal := startDynamoDBLocal.dependsOn(compile in Test).value
test in Test := (test in Test).dependsOn(startDynamoDBLocal).value
testOnly in Test := (testOnly in Test).dependsOn(startDynamoDBLocal).value
testOptions in Test += dynamoDBLocalTestCleanup.value

lazy val ipAddress: String = {
  val addr = "./get_ip_address.sh".!!.trim
  println(s"My IP address appears to be $addr")
  addr
}

val scalafmtAll = taskKey[Unit]("Run scalafmt in non-interactive mode with no arguments")
scalafmtAll := {
  import org.scalafmt.bootstrap.ScalafmtBootstrap
  streams.value.log.info("Running scalafmt ...")
  ScalafmtBootstrap.main(Seq("--non-interactive"))
  streams.value.log.info("Done")
}
(compile in Compile) := (compile in Compile).dependsOn(scalafmtAll).value
