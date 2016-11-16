import DockerPackage._

// Make ScalaTest write test reports that CirceCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", testReportsDir)

lazy val buildSettings = Seq(
  name                  := "delivery-service",
  organization          := "com.ovoenergy",
  organizationName      := "OVO Energy",
  organizationHomepage  := Some(url("http://www.ovoenergy.com")),
  scalaVersion          := "2.11.8",
  scalacOptions         := Seq("-unchecked", "-deprecation", "-encoding", "utf8")
)

lazy val service = (project in file("."))
  .settings(buildSettings)
  .settings(libraryDependencies ++= Dependencies.all)
  .withDocker


