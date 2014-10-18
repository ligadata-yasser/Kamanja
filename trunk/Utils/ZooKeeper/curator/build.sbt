name := "ZooKeeper"

version := "1.0"

scalaVersion := "2.10.4"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.2.0"

libraryDependencies += "log4j" % "log4j" % "1.2.17"

libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.5"

libraryDependencies ++= Seq(
"org.apache.commons" % "commons-collections4" % "4.0",
"commons-configuration" % "commons-configuration" % "1.7",
"commons-logging" % "commons-logging" % "1.1.1",
"org.apache.curator" % "curator-client" % "2.6.0",
"org.apache.curator" % "curator-framework" % "2.6.0",
"org.apache.curator" % "curator-recipes" % "2.6.0",
"com.googlecode.json-simple" % "json-simple" % "1.1"
)

scalacOptions += "-deprecation"
