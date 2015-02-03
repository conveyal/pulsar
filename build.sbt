name := """pulsar2"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  javaJdbc,
  javaEbean,
  cache,
  javaWs,
  "org.opentripplanner" % "otp" % "0.13.0",
  "org.mapdb" % "mapdb" % "1.0.6
)

resolvers += "Conveyal Maven Repository" at "http://maven.conveyal.com"
