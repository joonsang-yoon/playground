package playground

import chisel3.RawModule
import prefix.topology.library.{PrefixImplementations, PrefixModuleKind, TopologyArtifacts}

object Main {
  def main(args: Array[String]): Unit =
    println(run(args.toSeq))

  private[playground] def run(
    args: Seq[String],
    cwd:  os.Path = os.pwd,
    env:  Map[String, String] = sys.env
  ): String = {
    val Seq(command, config) = args

    val settings = ujson.read(os.read(os.Path(config, cwd)))
    if (settings.obj.contains("params")) runHardModule(command, settings, cwd, env)
    else runPrefixModule(command, settings, cwd, env)
  }

  private def runPrefixModule(
    command:  String,
    settings: ujson.Value,
    cwd:      os.Path,
    env:      Map[String, String]
  ): String = {
    def settingPath(name: String) = os.Path(settings(name).str, cwd)

    val module = PrefixModuleKind.all.find(_.name == settings("module").str).get
    val width = settings("width").num.toInt
    val implementation = PrefixImplementations.select(
      settings("implementation").str,
      module,
      width,
      settingPath("frontierDir")
    )
    val implementationDir = settingPath("outputDir") / module.name / s"width_$width" / implementation.name

    writeImplementation(command, env, settings, implementationDir, module.elaborate(width, implementation.topology)) {
      rtlArtifact =>
        ujson.Obj(
          "rtlFiles" -> rtlArtifact.rtlFiles.map(path => s"${implementation.name}/rtl/${path.last}"),
          "topology" -> implementation.metadata(module, width)
        )
    }
  }

  private def runHardModule(
    command:  String,
    settings: ujson.Value,
    cwd:      os.Path,
    env:      Map[String, String]
  ): String = {
    val moduleName = settings("module").str
    val spec = HardModuleFactory(moduleName)
    val params = HardModuleFactory.Params(settings("params").obj.toMap)
    val build = hardModuleBuild(moduleName, spec, params, settings, cwd)
    val implementationDir = os.Path(settings("outputDir").str, cwd) / moduleName / build.slug

    writeImplementation(command, env, settings, implementationDir, build.design()) { rtlArtifact =>
      ujson.Obj(
        "rtlFiles" -> rtlArtifact.rtlFiles.map(path => s"${build.slug}/rtl/${path.last}"),
        "design" -> hardModuleSummary(spec, moduleName, rtlArtifact.topModule, params, build.metadata: _*)
      )
    }
  }

  private def writeImplementation(
    command:           String,
    env:               Map[String, String],
    settings:          ujson.Value,
    implementationDir: os.Path,
    design:            => RawModule
  )(summary: TopologyArtifacts.RtlArtifact => ujson.Obj): String = {
    if (os.exists(implementationDir)) os.remove.all(implementationDir)

    val rtlArtifact = TopologyArtifacts.writeRtlArtifact(design, implementationDir / "rtl")
    if (command == "prelayout") {
      TopologyArtifacts.writePrelayoutReport(
        env,
        implementationDir / "sc" / "prelayout.json",
        rtlArtifact,
        summary(rtlArtifact),
        constraints(settings)
      )
    }

    implementationDir.toString
  }

  private final case class HardModuleBuild(
    slug:     String,
    design:   () => RawModule,
    metadata: Seq[(String, ujson.Value)] = Seq.empty
  )

  private final case class InternalPrefix(
    name:           String,
    moduleKind:     PrefixModuleKind,
    widths:         Seq[Int],
    collapseSingle: Boolean = false
  ) {
    def selections(implementation: String, frontierDir: os.Path): Seq[(Int, PrefixImplementations.Selection)] =
      widths.distinct.sorted.map(width =>
        width -> PrefixImplementations.select(implementation, moduleKind, width, frontierDir)
      )

    def metadata(selections: Seq[(Int, PrefixImplementations.Selection)]): ujson.Value =
      if (collapseSingle) {
        val (width, selection) = selections.head
        selection.metadata(moduleKind, width)
      } else ujson.Arr(selections.map { case (width, selection) => selection.metadata(moduleKind, width) }: _*)
  }

  private def hardModuleBuild(
    moduleName: String,
    spec:       HardModuleFactory.Spec,
    params:     HardModuleFactory.Params,
    settings:   ujson.Value,
    cwd:        os.Path
  ): HardModuleBuild = {
    val prefixes = internalPrefixes(moduleName, params)

    if (prefixes.isEmpty) HardModuleBuild(spec.slug(params), () => spec.build(params))
    else {
      val implementation = params.string("implementation")
      val frontierDir = internalPrefixFrontierDir(settings, cwd)
      val selected = prefixes.map(prefix => prefix -> prefix.selections(implementation, frontierDir))
      val prefixSlug = selected.map { case (prefix, selections) =>
        s"${prefix.name}_${prefixSelectionsSlug(selections)}"
      }

      HardModuleBuild(
        (spec.slug(params, exclude = Set("implementation")) +: prefixSlug).mkString("__"),
        () => PrefixImplementations.withFrontierDir(frontierDir)(spec.build(params)),
        selected.map { case (prefix, selections) => prefix.name -> prefix.metadata(selections) }
      )
    }
  }

  private def internalPrefixes(moduleName: String, params: HardModuleFactory.Params): Seq[InternalPrefix] =
    moduleName match {
      case "ALU" =>
        Seq(InternalPrefix("adder", PrefixModuleKind.Adder, Seq(params.int("dataWidth")), collapseSingle = true))
      case "AddRecFN" =>
        val expWidth = params.int("expWidth")
        val sigWidth = params.int("sigWidth")
        Seq(
          InternalPrefix("absDiff", PrefixModuleKind.AbsDiff, Seq(expWidth + 1, sigWidth + 2)),
          InternalPrefix("adder", PrefixModuleKind.Adder, Seq(expWidth + 1, sigWidth + 4))
        )
      case "Radix4BoothMultiplier" | "RISCVMultiplier" =>
        adderPrefixes(2 * params.int("dataWidth"))
      case "Radix4SRTDivider" | "RISCVDivider" =>
        adderPrefixes(params.int("dataWidth") + 1)
      case "MulRecFN" =>
        adderPrefixes(2 * params.int("sigWidth"))
      case "MulAddRecFN" =>
        adderPrefixes(2 * params.int("sigWidth") + 1)
      case "DivSqrtRecFN" =>
        val sigWidth = params.int("sigWidth")
        adderPrefixes((sigWidth & ~1) + 2)
      case _ => Seq.empty
    }

  private def adderPrefixes(widths: Int*): Seq[InternalPrefix] =
    Seq(InternalPrefix("adder", PrefixModuleKind.Adder, widths))

  private def prefixSelectionsSlug(orderedSelections: Seq[(Int, PrefixImplementations.Selection)]): String = {
    val implementations = orderedSelections.map { case (_, selection) => selection.name }.distinct
    if (implementations.size == 1) implementations.head
    else orderedSelections.map { case (width, selection) => s"w${width}_${selection.name}" }.mkString("__")
  }

  private def hardModuleSummary(
    spec:      HardModuleFactory.Spec,
    module:    String,
    topModule: String,
    params:    HardModuleFactory.Params,
    extra:     (String, ujson.Value)*
  ): ujson.Obj =
    ujson.Obj.from(
      Seq[(String, ujson.Value)](
        "family" -> ujson.Str(spec.family),
        "module" -> ujson.Str(module),
        "topModule" -> ujson.Str(topModule),
        "params" -> spec.paramsJson(params)
      ) ++ extra
    )

  private def constraints(settings: ujson.Value): ujson.Obj =
    settings.obj.getOrElse("constraints", ujson.Obj()).asInstanceOf[ujson.Obj]

  private def internalPrefixFrontierDir(settings: ujson.Value, cwd: os.Path): os.Path =
    settings.obj.get("frontierDir").fold(cwd / "pareto_frontier_topologies")(value => os.Path(value.str, cwd))
}
