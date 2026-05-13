package playground

import org.scalatest.funsuite.AnyFunSuite
import prefix.topology.library.{PrefixModuleKind, PrefixTopology, TopologyArtifacts}

final class MainSpec extends AnyFunSuite {
  test("rtl writes topology-backed SystemVerilog without a prelayout report") {
    withWorkspace("playground-build-") { workspace =>
      val frontierDir = workspace / "library"
      val outputDir = workspace / "generated"
      val frontierWidthDir = frontierDir / "PrefixAdder" / "width_4"
      val topologyFile = frontierWidthDir / "area_0" / "topology.json"
      val config = workspace / "configs" / "area.json"

      writeJson(topologyFile, PrefixTopology.ripple(4).toJson)
      writeConfig(config, "PrefixAdder", 4, "area_0")

      val generatedDir = runMain(workspace)("rtl", "configs/area.json")
      val implementationDir = outputDir / "PrefixAdder" / "width_4" / "area_0"
      val rtlDir = implementationDir / "rtl"

      assert(generatedDir == implementationDir.toString)
      assert(os.read(rtlDir / "filelist.f") == "PrefixAdder.sv\n")
      assert(os.read(rtlDir / "PrefixAdder.sv").contains("module PrefixAdder"))
      assert(!os.exists(implementationDir / "sc" / "prelayout.json"))
    }
  }

  test("prelayout resolves timing topology alias and writes topology metadata") {
    withWorkspace("playground-prelayout-") { workspace =>
      val frontierDir = workspace / "library"
      val outputDir = workspace / "generated"
      val frontierWidthDir = frontierDir / "PrefixAbsDiff" / "width_4"
      val topologyFile = frontierWidthDir / "area_1" / "topology.json"
      val config = workspace / "configs" / "timing.json"

      writeJson(topologyFile, PrefixTopology.ripple(4).toJson)
      writeJson(
        frontierWidthDir / "pareto_frontier.json",
        ujson.Obj(
          "frontier" -> Seq(
            ("area_0", "timing_1"),
            ("area_1", "timing_0")
          ).map { case (area, timing) =>
            ujson.Obj("area" -> area, "timing" -> timing)
          }
        )
      )
      writeConfig(config, "PrefixAbsDiff", 4, "timing_0")

      runMain(workspace, fakeRunnerEnv(workspace))("prelayout", config.toString)

      val reportPath = outputDir / "PrefixAbsDiff" / "width_4" / "timing_0" / "sc" / "prelayout.json"
      val report = ujson.read(os.read(reportPath))

      assert(report("module").str == "PrefixAbsDiff")
      assert(report("topology") == curatedMetadata("PrefixAbsDiff", 4, "timing_0", topologyFile))
      assert(report("rtlFiles").arr.map(_.str) == Seq("timing_0/rtl/PrefixAbsDiff.sv"))
    }
  }

  test("prelayout supports behavioral implementations for both modules") {
    withWorkspace("prefix-prelayout-behavioral-") { workspace =>
      val outputDir = workspace / "generated"

      PrefixModuleKind.all.foreach { moduleKind =>
        val config = workspace / "configs" / s"${moduleKind.name}.json"
        writeConfig(config, moduleKind.name, 4, "behavioral")

        runMain(workspace, fakeRunnerEnv(workspace))("prelayout", config.toString)

        val implementationDir = outputDir / moduleKind.name / "width_4" / "behavioral"
        val rtl = os.read(implementationDir / "rtl" / s"${moduleKind.name}.sv")
        val report = ujson.read(os.read(implementationDir / "sc" / "prelayout.json"))

        assert(rtl.contains(s"module ${moduleKind.name}"))
        assert(report("topology") == behavioralMetadata(moduleKind.name, 4))
        assert(report("rtlFiles").arr.map(_.str) == Seq(s"behavioral/rtl/${moduleKind.name}.sv"))
      }
    }
  }

  test("rtl supports hard-int companion factories and parameterized desired names") {
    withWorkspace("hard-int-rtl-") { workspace =>
      val config = workspace / "configs" / "booth.json"
      writeHardConfig(
        config,
        "Radix4BoothMultiplier",
        Seq("dataWidth" -> 8, "initHeight" -> 2, "implementation" -> "behavioral")
      )

      val generatedDir = runMain(workspace)("rtl", config.toString)
      val implementationDir =
        workspace / "generated" / "Radix4BoothMultiplier" / "dataWidth_8__initHeight_2__adder_behavioral"
      val rtlDir = implementationDir / "rtl"

      assert(generatedDir == implementationDir.toString)
      assert(os.read(rtlDir / "filelist.f").linesIterator.toSeq.contains("Radix4BoothMultiplier_dw8_initHeight2.sv"))
      assert(os.exists(rtlDir / "Radix4BoothMultiplier_dw8_initHeight2.sv"))
    }
  }

  test("rtl emits ALU with behavioral adder inlined") {
    withWorkspace("alu-prefix-default-") { workspace =>
      val config = workspace / "configs" / "alu.json"
      writeHardConfig(
        config,
        "ALU",
        Seq("dataWidth" -> 32, "implementation" -> "behavioral"),
        extra = Seq("frontierDir" -> ujson.Str("library"))
      )

      val generatedDir = runMain(workspace)("rtl", config.toString)
      val implementationDir = workspace / "generated" / "ALU" / "dataWidth_32__adder_behavioral"
      val rtlDir = implementationDir / "rtl"
      val filelist = os.read.lines(rtlDir / "filelist.f")

      assert(generatedDir == implementationDir.toString)
      assert(filelist.contains("ALU.sv"))
      assert(!filelist.contains("PrefixAdder.sv"))
      assert(!os.exists(rtlDir / "PrefixAdder.sv"))
      assert(!os.read(rtlDir / "ALU.sv").contains("PrefixAdder"))
    }
  }

  test("rtl selects ALU PrefixAdder implementation") {
    withWorkspace("alu-prefix-selection-") { workspace =>
      val topologyFile = workspace / "library" / "PrefixAdder" / "width_32" / "area_0" / "topology.json"
      writeJson(topologyFile, PrefixTopology.ripple(32).toJson)

      val selectedConfig = workspace / "configs" / "alu-area.json"
      writeHardConfig(
        selectedConfig,
        "ALU",
        Seq("dataWidth" -> 32, "implementation" -> "area_0"),
        extra = Seq("frontierDir" -> ujson.Str("library"))
      )

      val selectedDir = workspace / "generated" / "ALU" / "dataWidth_32__adder_area_0"
      assert(runMain(workspace)("rtl", selectedConfig.toString) == selectedDir.toString)
      assert(os.exists(selectedDir / "rtl" / "ALU.sv"))
      assert(!os.exists(selectedDir / "rtl" / "PrefixAdder.sv"))
    }
  }

  test("prelayout records ALU PrefixAdder metadata") {
    withWorkspace("alu-prefix-prelayout-") { workspace =>
      val topologyFile = workspace / "library" / "PrefixAdder" / "width_32" / "area_0" / "topology.json"
      val config = workspace / "configs" / "alu-area.json"
      writeJson(topologyFile, PrefixTopology.ripple(32).toJson)
      writeHardConfig(
        config,
        "ALU",
        Seq("dataWidth" -> 32, "implementation" -> "area_0"),
        ujson.Obj("clockPeriodNs" -> 1),
        Seq("frontierDir" -> ujson.Str("library"))
      )

      runMain(workspace, fakeRunnerEnv(workspace))("prelayout", config.toString)

      val report = readJson(workspace / "generated" / "ALU" / "dataWidth_32__adder_area_0" / "sc" / "prelayout.json")
      val rtlFiles = report("rtlFiles").arr.map(_.str)

      assert(rtlFiles.contains("dataWidth_32__adder_area_0/rtl/ALU.sv"))
      assert(!rtlFiles.contains("dataWidth_32__adder_area_0/rtl/PrefixAdder.sv"))
      assert(report("design")("adder") == curatedMetadata("PrefixAdder", 32, "area_0", topologyFile))
    }
  }

  test("prelayout records AddRecFN internal prefix metadata") {
    withWorkspace("addfn-absdiff-prelayout-") { workspace =>
      val frontierDir = workspace / "library"
      val expTopologyFile = frontierDir / "PrefixAbsDiff" / "width_9" / "area_0" / "topology.json"
      val closeSigTopologyFile = frontierDir / "PrefixAbsDiff" / "width_26" / "area_0" / "topology.json"
      val expAdderTopologyFile = frontierDir / "PrefixAdder" / "width_9" / "area_0" / "topology.json"
      val sigAdderTopologyFile = frontierDir / "PrefixAdder" / "width_28" / "area_0" / "topology.json"
      writeJson(expTopologyFile, PrefixTopology.ripple(9).toJson)
      writeJson(closeSigTopologyFile, PrefixTopology.ripple(26).toJson)
      writeJson(expAdderTopologyFile, PrefixTopology.ripple(9).toJson)
      writeJson(sigAdderTopologyFile, PrefixTopology.ripple(28).toJson)

      val env = fakeRunnerEnv(workspace)
      val selectedSlug = "expWidth_8__sigWidth_24__absDiff_area_0__adder_area_0"
      val moduleName = "AddRecFN"
      val selectedConfig = workspace / "configs" / "AddRecFN-area.json"

      writeHardConfig(
        selectedConfig,
        moduleName,
        Seq("expWidth" -> 8, "sigWidth" -> 24, "implementation" -> "area_0"),
        ujson.Obj("clockPeriodNs" -> 1),
        Seq("frontierDir" -> ujson.Str("library"))
      )

      runMain(workspace, env)("prelayout", selectedConfig.toString)

      val selectedReport = readJson(workspace / "generated" / moduleName / selectedSlug / "sc" / "prelayout.json")
      val selectedRtlFiles = selectedReport("rtlFiles").arr.map(_.str)

      assert(selectedRtlFiles.contains(s"$selectedSlug/rtl/$moduleName.sv"))
      assert(!selectedRtlFiles.exists(_.contains("/rtl/PrefixAbsDiff")))
      assert(!selectedRtlFiles.exists(_.contains("/rtl/PrefixAdder")))
      assert(
        selectedReport("design")("absDiff").arr.toSeq == Seq(
          curatedMetadata("PrefixAbsDiff", 9, "area_0", expTopologyFile),
          curatedMetadata("PrefixAbsDiff", 26, "area_0", closeSigTopologyFile)
        )
      )
      assert(
        selectedReport("design")("adder").arr.toSeq == Seq(
          curatedMetadata("PrefixAdder", 9, "area_0", expAdderTopologyFile),
          curatedMetadata("PrefixAdder", 28, "area_0", sigAdderTopologyFile)
        )
      )
    }
  }

  test("prelayout records FinalAdder PrefixAdder metadata") {
    withWorkspace("final-adder-prefix-prelayout-") { workspace =>
      val frontierDir = workspace / "library"
      val boothTopologyFile = frontierDir / "PrefixAdder" / "width_16" / "area_0" / "topology.json"
      val mulTopologyFile = frontierDir / "PrefixAdder" / "width_48" / "area_0" / "topology.json"
      val boothConfig = workspace / "configs" / "booth-area.json"
      val mulConfig = workspace / "configs" / "mul-area.json"

      writeJson(boothTopologyFile, PrefixTopology.ripple(16).toJson)
      writeJson(mulTopologyFile, PrefixTopology.ripple(48).toJson)
      writeHardConfig(
        boothConfig,
        "Radix4BoothMultiplier",
        Seq("dataWidth" -> 8, "initHeight" -> 2, "implementation" -> "area_0"),
        ujson.Obj("clockPeriodNs" -> 1),
        Seq("frontierDir" -> ujson.Str("library"))
      )
      writeHardConfig(
        mulConfig,
        "MulRecFN",
        Seq("expWidth" -> 8, "sigWidth" -> 24, "initHeight" -> 3, "implementation" -> "area_0"),
        ujson.Obj("clockPeriodNs" -> 1),
        Seq("frontierDir" -> ujson.Str("library"))
      )

      runMain(workspace, fakeRunnerEnv(workspace))("prelayout", boothConfig.toString)
      runMain(workspace, fakeRunnerEnv(workspace))("prelayout", mulConfig.toString)

      val boothSlug = "dataWidth_8__initHeight_2__adder_area_0"
      val mulSlug = "expWidth_8__sigWidth_24__initHeight_3__adder_area_0"
      val boothReport =
        readJson(workspace / "generated" / "Radix4BoothMultiplier" / boothSlug / "sc" / "prelayout.json")
      val mulReport = readJson(workspace / "generated" / "MulRecFN" / mulSlug / "sc" / "prelayout.json")
      val boothRtlFiles = boothReport("rtlFiles").arr.map(_.str)
      val mulRtlFiles = mulReport("rtlFiles").arr.map(_.str)

      assert(boothRtlFiles.contains(s"$boothSlug/rtl/Radix4BoothMultiplier_dw8_initHeight2.sv"))
      assert(!boothRtlFiles.exists(_.contains("/rtl/PrefixAdder")))
      assert(
        boothReport("design")("adder").arr.toSeq == Seq(
          curatedMetadata("PrefixAdder", 16, "area_0", boothTopologyFile)
        )
      )

      assert(mulRtlFiles.contains(s"$mulSlug/rtl/MulRecFN_ew8_sw24_initHeight3.sv"))
      assert(!mulRtlFiles.exists(_.contains("/rtl/PrefixAdder")))
      assert(
        mulReport("design")("adder").arr.toSeq == Seq(
          curatedMetadata("PrefixAdder", 48, "area_0", mulTopologyFile)
        )
      )
    }
  }

  test("prelayout writes hard-int and hard-float metadata with config constraints") {
    withWorkspace("hard-module-prelayout-") { workspace =>
      val outputDir = workspace / "generated"
      val hardIntConfig = workspace / "configs" / "alu.json"
      val hardFloatConfig = workspace / "configs" / "compare.json"

      writeHardConfig(
        hardIntConfig,
        "ALU",
        Seq("dataWidth" -> 32, "implementation" -> "behavioral"),
        ujson.Obj("clockName" -> "alu_clk", "clockPeriodNs" -> 2),
        Seq("frontierDir" -> ujson.Str("library"))
      )
      writeHardConfig(
        hardFloatConfig,
        "CompareRecFN",
        Seq("expWidth" -> 5, "sigWidth" -> 11),
        ujson.Obj("clockName" -> "fp_clk", "clockPeriodNs" -> 3, "inputDelayNs" -> 1)
      )

      runMain(workspace, fakeRunnerEnv(workspace))("prelayout", hardIntConfig.toString)
      runMain(workspace, fakeRunnerEnv(workspace))("prelayout", hardFloatConfig.toString)

      val aluReport = readJson(outputDir / "ALU" / "dataWidth_32__adder_behavioral" / "sc" / "prelayout.json")
      val compareReport = readJson(outputDir / "CompareRecFN" / "expWidth_5__sigWidth_11" / "sc" / "prelayout.json")

      assert(aluReport("module").str == "ALU")
      assert(aluReport("design")("family").str == "hard-int")
      assert(aluReport("design")("params")("dataWidth").num == 32)
      assert(aluReport("design")("adder") == behavioralMetadata("PrefixAdder", 32))
      assert(aluReport("constraints")("clockName").str == "alu_clk")
      assert(aluReport("constraints")("clockPeriodNs").num == 2)
      assert(aluReport("constraints")("inputDelayNs").num == 0.0)
      val aluRtlFiles = aluReport("rtlFiles").arr.map(_.str)
      assert(aluRtlFiles.contains("dataWidth_32__adder_behavioral/rtl/ALU.sv"))
      assert(!aluRtlFiles.contains("dataWidth_32__adder_behavioral/rtl/PrefixAdder.sv"))

      assert(compareReport("module").str == "CompareRecFN")
      assert(compareReport("design")("family").str == "hard-float")
      assert(compareReport("design")("params")("expWidth").num == 5)
      assert(compareReport("design")("params")("sigWidth").num == 11)
      assert(compareReport("constraints")("clockName").str == "fp_clk")
      assert(compareReport("constraints")("clockPeriodNs").num == 3)
      assert(compareReport("constraints")("inputDelayNs").num == 1)
      assert(compareReport("constraints")("outputDelayNs").num == 0.0)
      assert(compareReport("rtlFiles").arr.map(_.str).contains("expWidth_5__sigWidth_11/rtl/CompareRecFN.sv"))
    }
  }

  private def runMain(workspace: os.Path, extraEnv: Map[String, String] = Map.empty)(args: String*): String =
    Main.run(args, cwd = workspace, env = sys.env ++ extraEnv)

  private def withWorkspace[A](prefix: String)(testCode: os.Path => A): A = {
    val workspace = os.temp.dir(prefix = prefix)
    try testCode(workspace)
    finally os.remove.all(workspace)
  }

  private def writeJson(path: os.Path, value: ujson.Value): Unit =
    os.write.over(path, ujson.write(value) + "\n", createFolders = true)

  private def readJson(path: os.Path): ujson.Value =
    ujson.read(os.read(path))

  private def writeConfig(
    config:         os.Path,
    module:         String,
    width:          Int,
    implementation: String
  ): Unit =
    writeJson(
      config,
      ujson.Obj(
        "module" -> module,
        "width" -> width,
        "implementation" -> implementation,
        "frontierDir" -> "library",
        "outputDir" -> "generated"
      )
    )

  private def writeHardConfig(
    config:      os.Path,
    module:      String,
    params:      Seq[(String, Any)],
    constraints: ujson.Obj = ujson.Obj("clockPeriodNs" -> 1),
    extra:       Seq[(String, ujson.Value)] = Seq.empty
  ): Unit =
    writeJson(
      config,
      ujson.Obj.from(
        Seq[(String, ujson.Value)](
          "module" -> ujson.Str(module),
          "params" -> ujson.Obj.from(params.map { case (name, value) => name -> hardParamJson(value) }),
          "constraints" -> constraints,
          "outputDir" -> ujson.Str("generated")
        ) ++ extra
      )
    )

  private def hardParamJson(value: Any): ujson.Value = value match {
    case int:    Int    => ujson.Num(int)
    case string: String => ujson.Str(string)
  }

  private def curatedMetadata(module: String, width: Int, implementation: String, file: os.Path): ujson.Obj =
    prefixMetadata(module, width, "curated", implementation, ujson.Str(file.toString))

  private def behavioralMetadata(module: String, width: Int): ujson.Obj =
    prefixMetadata(module, width, "behavioral", "behavioral", ujson.Null)

  private def prefixMetadata(
    module:         String,
    width:          Int,
    source:         String,
    implementation: String,
    file:           ujson.Value
  ): ujson.Obj =
    ujson.Obj(
      "module" -> module,
      "width" -> width,
      "source" -> source,
      "implementation" -> implementation,
      "file" -> file
    )

  private def fakeRunnerEnv(workspace: os.Path): Map[String, String] = {
    val payloadFile = workspace / "runner-payload.json"
    writeJson(
      payloadFile,
      ujson.Obj(
        "metrics" -> ujson.Obj(
          "areaUm2" -> 12.3456,
          "clockPeriodNs" -> 0.25
        ),
        "toolchain" -> ujson.Obj(
          "siliconcompiler" -> "test-sc",
          "lambdapdk" -> "test-lambdapdk"
        )
      )
    )
    Map(TopologyArtifacts.FakeRunnerJsonEnv -> payloadFile.toString)
  }
}
