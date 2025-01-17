name := "MigrateBase"

version := "1.0"

// scalaVersion := "2.11.7"

// Enables publishing to maven repo
// publishMavenStyle := true

// Do not append Scala versions to the generated artifacts
crossPaths := false

// This forbids including Scala related libraries into the dependency
autoScalaLibrary := false

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies += "org.apache.logging.log4j" % "log4j-api" % "2.4.1"

libraryDependencies += "org.apache.logging.log4j" % "log4j-core" % "2.4.1"

// EclipseKeys.projectFlavor := EclipseProjectFlavor.Java
