package mill.scalaplugin.publish

import java.util.Base64

import ammonite.ops._
import mill.util.Logger

import scalaj.http._

object Sonatype {

  val uri = "https://oss.sonatype.org/service/local"

  val snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots"

  def publish(file: Path, artifact: Artifact, dependencies: Seq[Dependency])(
      log: Logger): Unit = {
    val settings =
      PomSettings(artifact.group, "url", Seq.empty, SCM("", ""), Seq.empty)
    val pom = PomFile.generatePom(artifact, dependencies, settings)
    val credentials =
      Option(System.getenv("SONATYPE_CREDENTIALS")).getOrElse(
        throw new RuntimeException(
          "Please provide sonatype credentials via SONATYPE_CREDENTIALS env variable"))

    val baseFileName = s"${artifact.id}-${artifact.version}"

    val artifacts = Seq(
      s"${baseFileName}.jar" -> read.bytes(file),
      s"${baseFileName}.pom" -> pom.getBytes
    )

    val artifactPath = Seq(
      artifact.group.replace(".", "/"),
      artifact.id,
      artifact.version
    ).mkString("/")

    if (artifact.version.endsWith("-SNAPSHOT")) {
      artifacts.foreach {
        case (fileName, data) =>
          val fullUri = snapshotUri + "/" + artifactPath + "/" + fileName
          log.info(s"Uploading ${fileName}")
          val resp = upload(fullUri, credentials, data)
          if (resp.code == 201) {
            log.info("Published snapshot to Sonatype")
          } else {
            log.error(
              s"Failed to publish snapshot to Sonatype. Code: ${resp.code}, message: ${resp.body}")
          }
      }
    } else {
      throw new RuntimeException(
        "publish non-snapshot versions is not yet supported")
    }
  }

  private def upload(uri: String,
                     credentials: String,
                     data: Array[Byte]): HttpResponse[String] = {
    Http(uri)
      .method("PUT")
      .headers(
        "Content-Type" -> "application/binary",
        "Authorization" -> s"Basic ${base64(credentials)}"
      )
      .put(data)
      .asString
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))

}
