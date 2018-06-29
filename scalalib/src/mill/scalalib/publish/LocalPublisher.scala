package mill.scalalib.publish

import ammonite.ops._

object LocalPublisher {

  private val root: Path = home / ".ivy2" / "local"

  def publish(jar: Path,
              sourcesJar: Option[Path],
              docJar: Option[Path],
              pom: Path,
              ivy: Path,
              artifact: Artifact): Unit = {
    val releaseDir = root / artifact.group / artifact.id / artifact.version
    writeFiles(
      Some(jar) -> releaseDir / "jars" / s"${artifact.id}.jar",
      sourcesJar -> releaseDir / "srcs" / s"${artifact.id}-sources.jar",
      docJar -> releaseDir / "docs" / s"${artifact.id}-javadoc.jar",
      Some(pom) -> releaseDir / "poms" / s"${artifact.id}.pom",
      Some(ivy) -> releaseDir / "ivys" / "ivy.xml"
    )
  }

  private def writeFiles(fromTo: (Option[Path], Path)*): Unit = {
    fromTo.foreach {
      case (Some(from), to) =>
        mkdir(to / up)
        cp.over(from, to)
      case (None, _) =>
    }
  }

}
