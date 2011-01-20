import sbt._

class MimaProject(info: ProjectInfo) extends DefaultProject(info) {

  // Add Maven Local repository for SBT to search for (disable if this doesn't suit you)
  val mavenLocal = "Local Maven Repository" at "file://"+Path.userHome+"/.m2/repository"

  val scalaSwing = "org.scala-lang" % "scala-swing" % "2.8.1"

  override def managedStyle = ManagedStyle.Maven
  
}
