import DockerPackage._

name := "delivery-service"

// Make ScalaTest write test reports that CirceCI understands
val testReportsDir = sys.env.getOrElse("CI_REPORTS", "target/reports")
testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF", "-u", testReportsDir, "-l", "DockerComposeTag")

lazy val buildSettings = Seq(
  name                    := "delivery-service",
  organization            := "com.ovoenergy",
  organizationName        := "OVO Energy",
  organizationHomepage    := Some(url("http://www.ovoenergy.com")),
  scalaVersion            := "2.11.8",
  scalacOptions           := Seq("-unchecked", "-deprecation", "-encoding", "utf8")
)

lazy val service = (project in file("."))
  .settings(buildSettings)
  .settings(resolvers += Resolver.bintrayRepo("ovotech", "maven"))
  .settings(resolvers += Resolver.bintrayRepo("cakesolutions", "maven"))
  .settings(libraryDependencies ++= Dependencies.all)
  .settings(testTagsToExecute := "DockerComposeTag")
  .enablePlugins(DockerComposePlugin)
  .withDocker

lazy val ipAddress: String = {
  val addr = "./get_ip_address.sh".!!.trim
  println(s"My IP address appears to be $addr")
  addr
}

variablesForSubstitution := Map("IP_ADDRESS" -> ipAddress)


