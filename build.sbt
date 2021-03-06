import Dependencies._

name := "delivery-service"

// Make ScalaTest write test reports that CirceCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF", "-u", testReportsDir)

lazy val ServiceTest = config("servicetest") extend (Test)
configs(ServiceTest)
inConfig(ServiceTest)(Defaults.testSettings)
(test in ServiceTest) := (test in ServiceTest).dependsOn(publishLocal in Docker).value
inConfig(ServiceTest)(parallelExecution in test := false)

lazy val buildSettings = Seq(
  name := "delivery-service",
  organization := "com.ovoenergy",
  organizationName := "OVO Energy",
  organizationHomepage := Some(url("http://www.ovoenergy.com")),
  scalaVersion := "2.12.4",
  scalafmtOnCompile := true,
  scalacOptions ++= Seq(
    "-target:jvm-1.8",
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-encoding",
    "utf-8", // Specify character encoding used by source files.
    "-explaintypes", // Explain type errors in more detail.
    "-feature", // Emit warning and location for usages of features that should be imported explicitly.
    "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
    "-language:experimental.macros", // Allow macro definition (besides implementation and application)
    "-language:higherKinds", // Allow higher-kinded types
    "-language:implicitConversions", // Allow definition of implicit functions called views
    "-unchecked",  // Enable additional warnings where generated code depends on assumptions.
    "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    //      "-Xfatal-warnings",                  // Fail the compilation if there are any warnings.
    "-Xfuture", // Turn on future language features.
    "-Xlint:adapted-args", // Warn if an argument list is modified to match the receiver.
    "-Xlint:by-name-right-associative", // By-name parameter of right associative operator.
    "-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit.
    "-Xlint:doc-detached", // A Scaladoc comment appears to be detached from its element.
    "-Xlint:inaccessible", // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Xlint:nullary-unit", // Warn when nullary methods return Unit.
    "-Xlint:option-implicit", // Option.apply used implicit view.
    "-Xlint:package-object-classes", // Class or object defined in package object.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
    "-Xlint:unsound-match", // Pattern match may not be typesafe.
    "-Yno-adapted-args", // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
    "-Ypartial-unification", // Enable partial unification in type constructor inference
    "-Ywarn-dead-code", // Warn when dead code is identified.
    "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Ywarn-inaccessible", // Warn about inaccessible types in method signatures.
    "-Ywarn-infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Ywarn-nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Ywarn-nullary-unit", // Warn when nullary methods return Unit.
    "-Ywarn-numeric-widen", // Warn when numerics are widened.
    "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports", // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals", // Warn if a local definition is unused.
    "-Ywarn-unused:params", // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars", // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates", // Warn if a private member is unused.
    "-Ywarn-value-discard" // Warn when non-Unit expression results are unused.
  )
)

val dependencies = Seq(
  fs2.core,
  fs2.io,
  fs2.kafkaClient,
  cats.core,
  cats.effect,
  cats.mtl,
  circe.core,
  circe.generic,
  circe.genericExtras,
  circe.parser,
  ovoEnergy.commsTemplates,
  ovoEnergy.commsKafkaMessages,
  ovoEnergy.commsKafkaHelpers exclude ("com.typesafe.akka", "akka-stream-kafka_2.12"),
  ovoEnergy.dockerKit,
  kafkaSerialization.cats,
  kafkaSerialization.core,
  logging.log4Cats,
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
  scalaCheck.scalacheck             % Test,
  scalaCheck.shapeless              % Test,
  whisk.scalaTest                   % Test,
  whisk.javaImpl                    % Test,
  ovoEnergy.commsKafkaTestHelpers   % Test,
  ovoEnergy.commsKafkaMessagesTests % Test,
  scalaTest                         % Test,
  mockServer                        % Test
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
  )
  .enablePlugins(JavaServerAppPackaging, DockerPlugin)

startDynamoDBLocal := startDynamoDBLocal
  .dependsOn(compile in Test)
  .value // TODO: Only start up for tests which need it!
test in Test := (test in Test).dependsOn(startDynamoDBLocal).value
testOnly in Test := (testOnly in Test).dependsOn(startDynamoDBLocal).evaluated
testOptions in Test += dynamoDBLocalTestCleanup.value
