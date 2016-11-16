import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.JavaServerAppPackaging
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import com.typesafe.sbt.packager.docker._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import sbt.Keys._
import sbt._

object DockerPackage {

  lazy val dockerLoginTask = TaskKey[Unit]("dockerLogin", "Log in to Amazon ECR")
  lazy val dockerConfigTask = TaskKey[Unit]("dockerConfig", "Pull config from S3")
  lazy val awsAccountNumber = sys.env.getOrElse("AWS_ACCOUNT_ID", "NOT_SET")

  private lazy val setupAlpine = Seq(
    Cmd("RUN", "apk --update add openjdk8-jre"),
    Cmd("RUN", "apk --update add bash")
  )

  private lazy val settings = Seq(
    packageName in Docker := "delivery-service",
    dockerRepository := Some(s"$awsAccountNumber.dkr.ecr.eu-west-1.amazonaws.com"),
    dockerUpdateLatest := false,
    dockerExposedPorts := Seq(8080),
    dockerBaseImage := "alpine",
    dockerCommands := dockerCommands.value.head +: setupAlpine ++: dockerCommands.value.tail,
    mappings in Universal += file("src/main/resources/application.conf")      ->  "conf/local/application.conf",
    mappings in Universal += file("src/main/resources/logback.xml")           ->  "conf/local/logback.xml",
    mappings in Universal += file("target/src_managed/resources/uat/application.conf")  ->  "conf/uat/application.conf",
    mappings in Universal += file("target/src_managed/resources/uat/logback.xml")       ->  "conf/uat/logback.xml",
    bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/${ENV,,}/application.conf"""",
    bashScriptExtraDefines += """addJava "-Dlogback.configurationFile=${app_home}/../conf/${ENV,,}/logback.xml"""",
    bashScriptExtraDefines += """addJava "-Xms256M"""",
    bashScriptExtraDefines += """addJava "-Xmx1536M""""
  )

  implicit class DockerProject(project: Project) {

    def withDocker: Project = {
      project
        .settings(settings: _*)
        .enablePlugins(JavaServerAppPackaging, DockerPlugin)
        .settings(
          dockerConfigTask := {
            import sys.process._
            "aws s3 sync s3://ovo-comms-platform-config/service-config/uat/delivery-service ./target/src_managed/resources/uat" !
          })
        .settings(
          dockerLoginTask := {
            import sys.process._
            "aws --region eu-west-1 ecr get-login" #| "bash" !
          })
        .settings(publish in Docker <<= (publish in Docker)
          .dependsOn(dockerLoginTask)
          .dependsOn(dockerConfigTask)
        )
    }
  }
}