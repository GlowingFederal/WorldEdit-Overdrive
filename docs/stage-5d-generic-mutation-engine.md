# Stage 5D generic mutation engine and capability audit

## Current shared pipeline and public support

Supported standard paste and streamed-history replay use bounded server-thread
preparation and placement, worker planning/I/O, streamed reorder/history storage
and a shared adaptive tick allowance. World mutation remains on the server
thread. The 64 MiB per-operation and 128 MiB global limits account for live working
data, rather than limiting total paste size at admission. These limits and worker
count are internal; only the shared pacing maximum and safety margin are exposed
in the config file.

The earlier full-snapshot, chunk-collation and repeated native-drain paste path
is superseded. `RegionMutationPlan` and snapshot helpers remain internal
foundations; their existence does not mean region commands are accelerated.
The current authoritative paste/replay path is documented in the
[integration document](worldedit-enhanced-integration.md).

## Enhanced 6.3.0 inventory

The pinned `RegionCommands` implementation, rather than command names, was used
for this classification.

| Command | Enhanced implementation | Model | Runtime support |
| --- | --- | --- | --- |
| `//paste` | `ClipboardCommands.paste` via standard `PasteBuilder` | COPY | **ACCELERATED** for recognized compatible graphs; otherwise native |
| `//undo`, `//redo` | `HistoryCommands` and `EditSession.undo/redo` | HISTORY | **PACED** for accelerated paste history; native-only history requests remain native |
| `//set` | Composed `SelectionCommand -> RegionVisitor -> BlockReplace`; legacy API `setBlocks` also hooked | FILL | **HOOKED / NATIVE**; incremental owner not installed |
| `//replace` | `replaceBlocks(region, Mask, Pattern)`; omitted mask becomes `ExistingBlockMask` | FILTER | **HOOKED / NATIVE**; incremental owner not installed |
| `//walls` | `makeCuboidWalls(region, Pattern)` | GEOMETRY | **HOOKED / NATIVE**; incremental owner not installed |
| `//faces`, `//outline` | aliases sharing `makeCuboidFaces(region, Pattern)` | GEOMETRY | **HOOKED / NATIVE**; incremental owner not installed |
| `//center` | `center(region, Pattern)` | GEOMETRY | **HOOKED / NATIVE**; incremental owner not installed |
| `//overlay` | `overlayCuboidBlocks(region, Pattern)` | GEOMETRY/FILTER | **HOOKED / NATIVE**; incremental owner not installed |
| `//naturalize` | `naturalizeCuboidBlocks(region)` | FILTER/column topology | **HOOKED / NATIVE**; incremental owner not installed |
| `//stack` | `stackCuboidRegion(region, direction, count, copyAir)` | COPY | **HOOKED / NATIVE**; incremental ordered owner not installed |
| `//move` | `moveRegion(region, direction, count, true, leaveBlock)` | COPY/MOVE | **HOOKED / NATIVE**; incremental ordered owner not installed |
| `//smooth` | `HeightMap` plus Gaussian `HeightMapFilter`, repeated | NEIGHBORHOOD | **VANILLA** pending a halo height snapshot and staged iterations |
| `//deform` | `deformRegion` with a user expression | CUSTOM/COPY | **VANILLA**; arbitrary expression semantics are not worker-safe |
| `//hollow` | `hollowOutRegion` with Manhattan thickness and pattern | NEIGHBORHOOD/GEOMETRY | **VANILLA** pending topology snapshot and pattern capability |
| `//regen` | temporarily removes the session mask and calls `World.regenerate` | WORLDGEN | **VANILLA** intentionally; generator/chunk semantics do not belong in the mutation planner |
| `//line`, `//curve` | `drawLine`/`drawSpline` with thickness and shell mode | GEOMETRY | **VANILLA** pending deterministic geometry adapter |
| `//forest` | `makeForest` with random generator behavior | WORLDGEN/CUSTOM | **VANILLA** because exact generator randomness is not captured |

The region bridges currently return not-handled before doing any edit work.
Enhanced therefore executes those commands with its own masks, patterns and
session behavior. `HOOKED` in the `operationSupport` status line means only
that the corresponding hook was installed; an unavailable hook is reported
separately. Hook presence does not establish acceleration or bounded execution.

Each `EditSession` entry bridge returns an explicit handled/not-handled decision.
Handled decisions return the accelerated changed-block count; not-handled decisions
branch to the first instruction of the untouched Enhanced method body. The shared
transform rewrites stack-map frames and maximum stack/local values for the entire
class with the LaunchWrapper-safe writer, so all eight command-family hooks remain
valid Java 8 control flow while retaining fail-open vanilla behavior.

## Runtime validation

On an Enhanced 6.3.0 Forge 1.7.10 server, use a large selection and record
wall time, largest commit tick, changed count, and chunks touched for vanilla
Enhanced and Overdrive. Run `//set stone`, `//undo`, a full-feature large
`//paste`, `//paste -a`, and `//undo`; after each operation inspect
`/overdrive status`. Also run `//replace stone dirt`, `//walls stone`,
`//faces stone`, `//overlay grass`, `//stack 5`, `//move 10`, and their undo
commands to confirm native fallback. Installed-hook bridge counters may increase,
but accelerated counters for these region commands should not. Only compatible
paste and its paced history replay are acceleration candidates. No speedup is
claimed without live measurements.

## Adaptive scheduler

Preparation, paste and history replay share a deadline based on server headroom,
the configured maximum and the safety reserve. Ready work can resume more than
once per tick in fair bounded rounds; work waiting for memory, a session or a
worker leaves the round. Phase controllers include recovery and backoff for
expensive mutations and chunk loading. Individual game/mod calls cannot be
preempted.

See [pacing and runtime diagnostics](worldedit-enhanced-integration.md#pacing-and-runtime-diagnostics)
for the current policy and settings. This scheduler does not pace the region
commands listed as native above.
