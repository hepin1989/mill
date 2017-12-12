package mill.scalaplugin.publish

import java.math.BigInteger
import java.security.MessageDigest

import ammonite.ops._
import mill.util.Logger

object SonatypeLib {

  val uri = "https://oss.sonatype.org/service/local"

  val snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots"

  def publish(artifacts: Seq[Path], artifact: Artifact)(log: Logger): Unit = {
    val credentials =
      Option(System.getenv("SONATYPE_CREDENTIALS")).getOrElse(
        throw new RuntimeException(
          "Please provide sonatype credentials via SONATYPE_CREDENTIALS env variable"))
    val gpgPassphrase =
      Option(System.getenv("GPG_PASSPHRASE")).getOrElse(
        throw new RuntimeException(
          "Please provide gpg password via GPG_PASSPHRASE env variable"))

    val files = artifacts ++ artifacts.map(poorMansSign(_, gpgPassphrase))

    val content = files.map { file =>
      file.name -> read.bytes(file)
    }

    val contentWithDigest = content ++ content.flatMap {
      case (name, content) =>
        Seq(
          (name + ".md5") -> md5hex(content),
          (name + ".sha1") -> sha1hex(content)
        )
    }

    val artifactPath = Seq(
      artifact.group.replace(".", "/"),
      artifact.id,
      artifact.version
    ).mkString("/")

    val api = new SonatypeHttpApi(uri, credentials)
    if (artifact.version.endsWith("-SNAPSHOT")) {
      val baseUri: String = snapshotUri + "/" + artifactPath

      contentWithDigest.foreach {
        case (fileName, data) =>
          log.info(s"Uploading ${fileName}")
          val resp = api.upload(s"${baseUri}/${fileName}", data)
          if (resp.code == 201) {
            log.info("Published snapshot to Sonatype")
          } else {
            log.error(
              s"Failed to publish snapshot to Sonatype. Code: ${resp.code}, message: ${resp.body}")
          }
      }
    } else {
      val profileUri = api.getStagingProfileUri(artifact.group)
      val stagingRepoId =
        api.createStagingRepo(profileUri, artifact.group)
      val baseUri =
        s"${uri}/staging/deployByRepositoryId/${stagingRepoId}/${artifactPath}"

      // result should reflect success/failure
      val result = contentWithDigest.foreach {
        case (fileName, data) =>
          log.info(s"Uploading ${fileName}")
          val resp = api.upload(s"${baseUri}/${fileName}", data)
          if (resp.code == 201) {
            log.info("Published artifact to staging profile")
          } else {
            log.error(
              s"Failed publish ${fileName} to staging profile. Code: ${resp.code}, message: ${resp.body}")
          }
      }

      log.info("Closing staging repository")
      api.closeStagingRepo(profileUri, stagingRepoId)

      log.info("Waiting for staging repository to close")
      awaitRepoStatus("closed", stagingRepoId)(api)

      log.info("Promoting staging repository ")
      api.promoteStagingRepo(profileUri, stagingRepoId)

      log.info("Waiting for staging repository to release")
      awaitRepoStatus("released", stagingRepoId)(api)

      log.info("Dropping staging repository")
      api.dropStagingRepo(profileUri, stagingRepoId)

      log.info(s"Published ${artifact.name} successfully")
    }
  }

  def awaitRepoStatus(status: String, stagingRepoId: String)(
      api: SonatypeHttpApi,
      attempts: Int = 20): Unit = {
    if (api.getStagingRepoState(stagingRepoId).equalsIgnoreCase(status)) {
      ()
    } else if (attempts > 0) {
      Thread.sleep(3000)
      awaitRepoStatus(status, stagingRepoId)(api, attempts - 1)
    } else {
      throw new RuntimeException(
        s"Couldn't wait for staging repository to be ${status}. Failing")
    }
  }

  // http://central.sonatype.org/pages/working-with-pgp-signatures.html#signing-a-file
  private def poorMansSign(file: Path, passphrase: String): Path = {
    val fileName = file.toString
    import ammonite.ops.ImplicitWd._
    %("gpg", "--yes", "-a", "-b", "--passphrase", passphrase, fileName)
    Path(fileName + ".asc")
  }

  private val md5 = MessageDigest.getInstance("md5")

  private val sha1 = MessageDigest.getInstance("sha1")

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

}
