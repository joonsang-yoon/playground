# playground

`playground` builds one selected hardware artifact from one JSON config. Use this repository root when you already know which module, parameters, and implementation point you want to generate.

Use [`prefix-topology-library`](prefix-topology-library/README.md) when the task is to enumerate, measure, inspect, or curate prefix topology data. This root consumes that data; it does not create or refresh it.

| Need | Use |
| --- | --- |
| Build wrapper RTL for one selected config | this repository root |
| Build one prelayout report for one selected config | this repository root |
| Enumerate or inspect prefix topologies | [`prefix-topology-library`](prefix-topology-library/README.md) |
| Refresh checked-in sample or Pareto topology roots | [`prefix-topology-library`](prefix-topology-library/README.md) |

Checked-in topology data supports widths $1$ through $128$ for `PrefixAdder` and `PrefixAbsDiff`. Widths $1$ through $4$ are fully enumerated by default. Wider checked-in widths are ripple-derived unless the topology library is refreshed with a wider maintainer interval.

This root:

- parses one config and builds one selected implementation
- rewrites only that selected implementation directory
- resolves `area_<i>` and `timing_<i>` requests from an existing module-first frontier root
- does not enumerate source topologies, refresh checked-in topology data, or choose a Pareto point automatically

## Quick Start

Run parent-flow commands from this repository root.

```bash
make rtl
make prelayout
```

The default config is [`configs/default.json`](configs/default.json). It builds behavioral `PrefixAdder` RTL for width $4$.

Select another config with `CONFIG`.

```bash
make rtl CONFIG=configs/hard-int/RISCVMultiplier.json
make prelayout CONFIG=configs/hard-float/AddRecFN.json
```

If dependency caches are not writable, point them at writable locations.

```bash
XDG_CACHE_HOME=/tmp/xdg-cache COURSIER_CACHE=/tmp/coursier-cache make rtl
```

Default outputs:

| Command | Default output |
| --- | --- |
| `make rtl` | `generated/PrefixAdder/width_4/behavioral/rtl/` |
| `make prelayout` | `generated/PrefixAdder/width_4/behavioral/rtl/` and `generated/PrefixAdder/width_4/behavioral/sc/prelayout.json` |
| `make help` | available targets and current defaults |

## Output Contract

Before generation, the selected implementation directory is removed and recreated. Each run rewrites exactly one directory:

```text
<outputDir>/<module>/<selected-point>/
```

For direct prefix modules, `<selected-point>` is `width_<width>/<implementation>`. For hard modules, it is a deterministic parameter slug.

| Target | Writes |
| --- | --- |
| `make rtl` | RTL files and `rtl/filelist.f` |
| `make prelayout` | RTL files, `rtl/filelist.f`, and `sc/prelayout.json` |

`prelayout.json` records metrics, constraints, toolchain versions, analyzed RTL files, and design metadata.

| Flow | Metadata |
| --- | --- |
| prefix behavioral | `topology.source = "behavioral"` and `topology.file = null` |
| prefix curated | requested implementation name and resolved topology file |
| hard module | `design.family`, `design.module`, `design.topModule`, and normalized `design.params` |
| hard module with internal prefixes | additional `design.adder` or `design.absDiff` metadata |

Paths in `CONFIG`, `frontierDir`, and `outputDir` resolve relative to this repository root when using the Makefile flow. Keep `outputDir` outside checked-in source roots unless replacing those artifacts is intentional.

## Config Model

The top-level JSON shape selects the flow.

| Config shape | Flow | Use it for |
| --- | --- | --- |
| no `params` field | direct prefix module | `PrefixAdder` or `PrefixAbsDiff` |
| has `params` field | hard module | supported hard-int and hard-float generators |

Config checklist:

- choose exactly one supported `module`
- set `outputDir` to the destination root you are willing to rewrite under
- use top-level `implementation` only for direct prefix configs
- use `params.implementation` for hard modules that expose internal prefix selection
- provide `frontierDir` when a selected implementation is `area_<i>` or `timing_<i>`

`constraints` is optional and affects only `make prelayout`.

| Constraint | Default |
| --- | --- |
| `clockName` | `virtual_clk` |
| `clockPeriodNs` | $1.0$ |
| `inputDelayNs` | $0.0$ |
| `outputDelayNs` | $0.0$ |

## Direct Prefix Modules

A direct prefix config selects one prefix module, one positive width, and one implementation name.

```json
{
  "module": "PrefixAdder",
  "width": 4,
  "implementation": "behavioral",
  "frontierDir": "prefix-topology-library/pareto_frontier_topologies",
  "outputDir": "generated"
}
```

| Field | Meaning |
| --- | --- |
| `module` | `PrefixAdder` or `PrefixAbsDiff` |
| `width` | positive prefix width |
| `implementation` | `behavioral`, `area_<i>`, or `timing_<i>` |
| `frontierDir` | module-first frontier root for curated implementations |
| `outputDir` | destination root for generated artifacts |

`behavioral` reads no topology file. Curated implementations read topology data from a module-first frontier root.

| Implementation | Resolution |
| --- | --- |
| `behavioral` | no topology lookup; output directory is `behavioral` |
| `area_<i>` | reads `<frontierDir>/<module>/width_<width>/area_<i>/topology.json` |
| `timing_<i>` | resolves through `pareto_frontier.json` to an `area_<j>/topology.json` |

`area_<i>` is a materialized directory. `timing_<i>` is an alias stored in `pareto_frontier.json`; it is not a directory in the frontier root. Source topology names such as `ripple` and `variant_<k>` belong to full width-first topology roots and are not parent-flow implementation names.

Curated requests must resolve to valid topology JSON. Missing aliases, missing files, and malformed topology files fail the build instead of silently changing the requested implementation.

Direct prefix outputs use this path:

```text
<outputDir>/<module>/width_<width>/<implementation>/
```

Behavioral and topology-backed builds emit the same top-level module names and ports. Vector ports use the configured width. `io_cout` and `io_lt` are one-bit outputs.

`PrefixAdder` ports:

- inputs: `io_a`, `io_b`, `io_cin`
- outputs: `io_sum`, `io_cout`
- behavior: $\{io\_cout, io\_sum\} = io\_a + io\_b + io\_cin$

`PrefixAbsDiff` ports:

- inputs: `io_a`, `io_b`
- outputs: `io_lt`, `io_absDiff`
- behavior: $io\_lt = io\_a < io\_b$ and $io\_absDiff = abs(io\_a - io\_b)$

## Hard Modules

A hard-module config instantiates one supported hard-int or hard-float generator with explicit parameters.

```json
{
  "module": "RISCVMultiplier",
  "params": {
    "dataWidth": 64,
    "numXPRs": 32,
    "initHeight": 3,
    "implementation": "behavioral"
  },
  "constraints": {
    "clockName": "clock",
    "clockPeriodNs": 1.0
  },
  "outputDir": "generated"
}
```

| Field | Meaning |
| --- | --- |
| `module` | supported hard-int or hard-float module name |
| `params` | JSON object read by the selected module factory |
| `constraints` | optional prelayout timing constraints |
| `outputDir` | destination root for generated artifacts |
| `frontierDir` | required for curated internal prefix selection; optional for `behavioral` |

Provide every required key with the expected type. Extra keys in `params` are ignored by the current factory.

Hard-module implementation selection, when supported, belongs inside `params.implementation`; a top-level hard-module `implementation` field is not supported.

Hard-module outputs use this path:

```text
<outputDir>/<module>/<paramSlug>/
```

Supported hard-int modules:

| Module | Required `params` |
| --- | --- |
| `ALU` | `dataWidth`, `implementation` |
| `Radix4BoothMultiplier` | `dataWidth`, `initHeight`, `implementation` |
| `RISCVMultiplier` | `dataWidth`, `numXPRs`, `initHeight`, `implementation` |
| `Radix4SRTDivider` | `dataWidth`, `implementation` |
| `RISCVDivider` | `dataWidth`, `numXPRs`, `implementation` |

Supported hard-float modules:

| Module | Required `params` |
| --- | --- |
| `AddRecFN` | `expWidth`, `sigWidth`, `implementation` |
| `CompareRecFN` | `expWidth`, `sigWidth` |
| `INToRecFN` | `intWidth`, `expWidth`, `sigWidth` |
| `RecFNToIN` | `expWidth`, `sigWidth`, `intWidth` |
| `RecFNToRecFN` | `inExpWidth`, `inSigWidth`, `outExpWidth`, `outSigWidth` |
| `RoundAnyRawFNToRecFN` | `inExpWidth`, `inSigWidth`, `outExpWidth`, `outSigWidth`, `options` |
| `RoundRawFNToRecFN` | `expWidth`, `sigWidth`, `options` |
| `MulRecFN` | `expWidth`, `sigWidth`, `initHeight`, `implementation` |
| `MulAddRecFN` | `expWidth`, `sigWidth`, `initHeight`, `implementation` |
| `DivSqrtRecFN` | `expWidth`, `sigWidth`, `implementation` |

`AddRawFN` is an internal class instantiated by `AddRecFN`; it is not currently registered as a top-level config module.

## Internal Prefix Selection

Some hard modules use `params.implementation` to select internal prefix modules. They accept the same `behavioral`, `area_<i>`, and `timing_<i>` names used by direct prefix configs.

| Hard module | Internal prefix | Selected widths |
| --- | --- | --- |
| `ALU` | `PrefixAdder` | `dataWidth` |
| `Radix4BoothMultiplier`, `RISCVMultiplier` | `PrefixAdder` | $2 * dataWidth$ |
| `Radix4SRTDivider`, `RISCVDivider` | `PrefixAdder` | $dataWidth + 1$ |
| `AddRecFN` | `PrefixAbsDiff` | $expWidth + 1$, $sigWidth + 2$, deduplicated |
| `AddRecFN` | `PrefixAdder` | $expWidth + 1$, $sigWidth + 4$, deduplicated |
| `MulRecFN` | `PrefixAdder` | $2 * sigWidth$ |
| `MulAddRecFN` | `PrefixAdder` | $2 * sigWidth + 1$ |
| `DivSqrtRecFN` | `PrefixAdder` | $(sigWidth & ~1) + 2$ |

Curated internal prefix selections use top-level `frontierDir`. Every selected internal prefix width must resolve to a valid curated topology unless the implementation is `behavioral`.

Slug suffixes summarize selected internal implementations:

- `ALU` with `params.implementation = "behavioral"`: `generated/ALU/dataWidth_32__adder_behavioral/`
- `ALU` with `params.implementation = "area_0"`: `generated/ALU/dataWidth_32__adder_area_0/`
- `AddRecFN` with `params.implementation = "area_0"`: `generated/AddRecFN/expWidth_8__sigWidth_24__absDiff_area_0__adder_area_0/`
- `RISCVMultiplier` with `params.implementation = "behavioral"`: `generated/RISCVMultiplier/dataWidth_64__numXPRs_32__initHeight_3__adder_behavioral/`

When a selected family resolves to different implementation names at different internal widths, the slug records each width as `w<width>_<implementation>`.

## Command Reference

| Command | Description |
| --- | --- |
| `make help` | show targets and current defaults |
| `make rtl` | write wrapper SystemVerilog from `CONFIG` |
| `make prelayout` | write wrapper SystemVerilog and a prelayout report from `CONFIG` |
| `make check` | run formatting checks and tests |
| `make test` | build TestFloat, then run tests |
| `make testfloat` | build the hard-float test vector generator |
| `make lint` | check source formatting |
| `make format` | format source files |
| `make clean` | remove generated wrapper files |
| `make clean-all` | remove generated wrapper files and Mill state |

| Variable | Default | Meaning |
| --- | --- | --- |
| `CONFIG` | `<repo>/configs/default.json` | config file passed to `rtl` and `prelayout` |
| `TEST_OUTPUT_DIR` | `<repo>/test-output` | output root used by tests |
| `MILL_JOBS` | detected CPU count, capped at $4$ | Mill parallelism |
| `MILL` | `<repo>/mill` | checked-in Mill launcher |
| `MILL_FLAGS` | `--no-daemon -j $(MILL_JOBS)` | flags passed to Mill |

## Requirements

Required for wrapper generation, tests, and formatting checks:

- JDK `17`
- `make`
- `bash`
- the checked-in `./mill` launcher
- writable Coursier and XDG cache locations, or explicit cache variables

Additional requirements for `make test`:

- initialized hard-float SoftFloat and TestFloat submodules
- a C toolchain capable of building TestFloat

Additional requirements for real prelayout reports:

- `$HOME/siliconcompiler/.venv/bin/python`
- Python packages `siliconcompiler` and `lambdapdk`
- `yosys` on `PATH`
- `sta` on `PATH`
- network access on first run if the Lambda PDK archive is not cached

Tests can exercise report paths without the real prelayout toolchain by setting `PREFIX_TOPOLOGY_LIBRARY_FAKE_RUNNER_JSON` to a JSON payload with `metrics` and `toolchain` fields.

## Troubleshooting

Checks by symptom:

- `area_<i>` does not resolve: confirm `frontierDir` contains the requested `area_<i>/topology.json`.
- `timing_<i>` does not resolve: confirm `pareto_frontier.json` contains the timing alias and referenced `area_<j>` topology.
- internal adder selection fails: confirm `frontierDir` contains the selected `PrefixAdder` width, or use `behavioral`.
- `AddRecFN` prefix selection fails: confirm all selected `PrefixAbsDiff` and `PrefixAdder` widths exist, or use `behavioral`.
- hard-module config fails: confirm `module` is supported and every required `params` key is present.
- output appears stale: rerun the target; the selected output directory is removed first.
- prelayout report fails: confirm Python, `yosys`, `sta`, constraints, and cache permissions.
- command fails in a restricted environment: set `XDG_CACHE_HOME` and `COURSIER_CACHE` to writable directories.

## Related Docs

- [`prefix-topology-library/README.md`](prefix-topology-library/README.md): topology generation, artifact refresh, and Pareto curation
- [Topology JSON Specification](prefix-topology-library/docs/topology-json-spec.md): authoritative `topology.json` format
- [Dyck JSON Specification](prefix-topology-library/docs/dyck-json-spec.md): derived `dyck.json` format
- [`arith-primitives/docs/LowMask.md`](arith-primitives/docs/LowMask.md): threshold-space mask helper
