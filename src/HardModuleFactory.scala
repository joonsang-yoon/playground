package playground

import chisel3.RawModule
import playground.hardfloat._
import playground.hardint._

private[playground] object HardModuleFactory {
  final case class Params(json: Map[String, ujson.Value]) {
    def int(name:    String): Int = json(name).num.toInt
    def string(name: String): String = json(name).str
    def slug(name: String): String =
      json(name) match {
        case ujson.Num(value) => value.toInt.toString
        case value            => value.str
      }
  }

  final case class Spec(family: String, params: Seq[String], build: Params => RawModule) {
    def slug(values: Params, exclude: Set[String] = Set.empty): String =
      params.filterNot(exclude).map(name => s"${name}_${values.slug(name)}").mkString("__")

    def paramsJson(values: Params): ujson.Obj =
      ujson.Obj.from(params.map(name => name -> values.json(name)))
  }

  private def hardInt(params: String*)(build: Params => RawModule): Spec =
    Spec("hard-int", params, build)

  private def hardFloat(params: String*)(build: Params => RawModule): Spec =
    Spec("hard-float", params, build)

  private val specs = Map(
    "ALU" -> hardInt("dataWidth", "implementation")(p => new ALU(p.int("dataWidth"), p.string("implementation"))),
    "Radix4BoothMultiplier" -> hardInt("dataWidth", "initHeight", "implementation")(p =>
      Radix4BoothMultiplier(p.int("dataWidth"), p.int("initHeight"), p.string("implementation"))
    ),
    "RISCVMultiplier" -> hardInt("dataWidth", "numXPRs", "initHeight", "implementation")(p =>
      new RISCVMultiplier(p.int("dataWidth"), p.int("numXPRs"), p.int("initHeight"), p.string("implementation"))
    ),
    "Radix4SRTDivider" -> hardInt("dataWidth", "implementation")(p =>
      Radix4SRTDivider(p.int("dataWidth"), p.string("implementation"))
    ),
    "RISCVDivider" -> hardInt("dataWidth", "numXPRs", "implementation")(p =>
      new RISCVDivider(p.int("dataWidth"), p.int("numXPRs"), p.string("implementation"))
    ),
    "AddRecFN" -> hardFloat("expWidth", "sigWidth", "implementation")(p =>
      new AddRecFN(p.int("expWidth"), p.int("sigWidth"), p.string("implementation"))
    ),
    "CompareRecFN" -> hardFloat("expWidth", "sigWidth")(p => new CompareRecFN(p.int("expWidth"), p.int("sigWidth"))),
    "INToRecFN" -> hardFloat("intWidth", "expWidth", "sigWidth")(p =>
      new INToRecFN(p.int("intWidth"), p.int("expWidth"), p.int("sigWidth"))
    ),
    "RecFNToIN" -> hardFloat("expWidth", "sigWidth", "intWidth")(p =>
      new RecFNToIN(p.int("expWidth"), p.int("sigWidth"), p.int("intWidth"))
    ),
    "RecFNToRecFN" -> hardFloat("inExpWidth", "inSigWidth", "outExpWidth", "outSigWidth")(p =>
      new RecFNToRecFN(p.int("inExpWidth"), p.int("inSigWidth"), p.int("outExpWidth"), p.int("outSigWidth"))
    ),
    "RoundAnyRawFNToRecFN" -> hardFloat("inExpWidth", "inSigWidth", "outExpWidth", "outSigWidth", "options")(p =>
      new RoundAnyRawFNToRecFN(
        p.int("inExpWidth"),
        p.int("inSigWidth"),
        p.int("outExpWidth"),
        p.int("outSigWidth"),
        p.int("options")
      )
    ),
    "RoundRawFNToRecFN" -> hardFloat("expWidth", "sigWidth", "options")(p =>
      new RoundRawFNToRecFN(p.int("expWidth"), p.int("sigWidth"), p.int("options"))
    ),
    "MulRecFN" -> hardFloat("expWidth", "sigWidth", "initHeight", "implementation")(p =>
      new MulRecFN(p.int("expWidth"), p.int("sigWidth"), p.int("initHeight"), p.string("implementation"))
    ),
    "MulAddRecFN" -> hardFloat("expWidth", "sigWidth", "initHeight", "implementation")(p =>
      new MulAddRecFN(p.int("expWidth"), p.int("sigWidth"), p.int("initHeight"), p.string("implementation"))
    ),
    "DivSqrtRecFN" -> hardFloat("expWidth", "sigWidth", "implementation")(p =>
      new DivSqrtRecFN(p.int("expWidth"), p.int("sigWidth"), p.string("implementation"))
    )
  )

  def apply(moduleName: String): Spec =
    specs(moduleName)
}
