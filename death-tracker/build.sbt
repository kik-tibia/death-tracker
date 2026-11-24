ThisBuild / version := "1.9.1"
ThisBuild / scalaVersion := "2.13.18"

name := "death-tracker"

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)
dockerExposedPorts += 443
dockerBaseImage := "eclipse-temurin:17-jre"

val PekkoVersion = "1.7.1"
val PekkoHttpVersion = "1.4.2"

libraryDependencies += "com.typesafe" % "config" % "1.4.3"
libraryDependencies += "org.apache.pekko" %% "pekko-stream" % PekkoVersion
libraryDependencies += "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion
libraryDependencies += "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.6.5"
libraryDependencies += "net.dv8tion" % "JDA" % "6.7.0"
libraryDependencies += "org.apache.commons" % "commons-text" % "1.9"
