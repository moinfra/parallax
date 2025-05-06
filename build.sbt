ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "org.example"
ThisBuild / useCoursier := false

val spinalRoot = file("../SpinalHDL")

lazy val spinalIdslPlugin = ProjectRef(spinalRoot, "idslplugin")
lazy val spinalSim = ProjectRef(spinalRoot, "sim")
lazy val spinalCore = ProjectRef(spinalRoot, "core")
lazy val spinalLib = ProjectRef(spinalRoot, "lib")
lazy val spinalTester = ProjectRef(spinalRoot, "tester")

lazy val boson = (project in file("."))
  .dependsOn(spinalIdslPlugin, spinalSim, spinalCore, spinalLib, spinalTester)  // 必须包含所有依赖
  .settings(
    name := "boson",
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal",
    Test / scalaSource := baseDirectory.value / "test",
    
    scalacOptions += (spinalIdslPlugin / Compile / packageBin / artifactPath).map { file =>
      s"-Xplugin:${file.getAbsolutePath}"
    }.value,
    
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      // "-Ymacro-annotations"  // 适用于Scala 2.13
    ),
    fork := true,
    
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.17" % Test
  )
