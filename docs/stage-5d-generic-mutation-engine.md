# Stage 5D generic mutation engine and capability audit

## Shared pipeline

Overdrive now separates semantic adapters from `RegionMutationPlan`. Plans declare
whether ordering is independent or constrained and contain stable, destination
chunk-local batches. Paste is the first adapter producing this representation;
future adapters must reuse it rather than own another commit loop.

The shared paste-backed commit path now filters ordinary unchanged destination
states before `EditSession.setBlock`, drains one destination chunk at a time, and
adapts its 32–4096 mutation allowance from measured drain cost. The five
millisecond deadline is global across all active owners in a tick rather than per
owner. Unfinished owners rotate to the tail for round-robin fairness, retain native `EditSession` history,
and retain the existing 64 MiB operation/128 MiB global admission limits. NBT
blocks deliberately take the complete path because ID/data equality cannot prove
tile equality.

This is a conservative evolution: destination reads and all mutations remain on
the server thread. A later bounded snapshot phase is still required before
FILTER or NEIGHBORHOOD adapters may publish destination state to workers.

## Enhanced 6.3.0 inventory

The pinned `RegionCommands` implementation, rather than command names, was used
for this classification.

| Command | Enhanced implementation | Model | Runtime support |
| --- | --- | --- | --- |
| `//set` | `EditSession.setBlocks(region, pattern)` | FILL | **ACCELERATED** for the existing exact constant-pattern capability; otherwise vanilla |
| `//replace` | `replaceBlocks(region, Mask, Pattern)`; omitted mask becomes `ExistingBlockMask` | FILTER | **ACTIVE**; Enhanced masks and patterns execute against the fully captured pre-mutation state |
| `//walls` | `makeCuboidWalls(region, Pattern)` | GEOMETRY | **ACTIVE** for Enhanced's cuboid command |
| `//faces`, `//outline` | aliases sharing `makeCuboidFaces(region, Pattern)` | GEOMETRY | **ACTIVE** for Enhanced's cuboid command |
| `//center` | `center(region, Pattern)` | GEOMETRY | **ACTIVE** with Enhanced's center bounds |
| `//overlay` | `overlayCuboidBlocks(region, Pattern)` | GEOMETRY/FILTER | **ACTIVE** with one immutable top-down column capture |
| `//naturalize` | `naturalizeCuboidBlocks(region)` | FILTER/column topology | **ACTIVE** with the Enhanced grass/dirt/stone depth rule |
| `//stack` | `stackCuboidRegion(region, direction, count, copyAir)` | COPY | **ACTIVE**; one lossless `BaseBlock`/NBT source capture is reused for every repetition |
| `//move` | `moveRegion(region, direction, count, true, leaveBlock)` | COPY/MOVE | **ACTIVE**; complete source capture before clearing preserves overlapping moves and NBT |
| `//smooth` | `HeightMap` plus Gaussian `HeightMapFilter`, repeated | NEIGHBORHOOD | **VANILLA** pending a halo height snapshot and staged iterations |
| `//deform` | `deformRegion` with a user expression | CUSTOM/COPY | **VANILLA**; arbitrary expression semantics are not worker-safe |
| `//hollow` | `hollowOutRegion` with Manhattan thickness and pattern | NEIGHBORHOOD/GEOMETRY | **VANILLA** pending topology snapshot and pattern capability |
| `//regen` | temporarily removes the session mask and calls `World.regenerate` | WORLDGEN | **VANILLA** intentionally; generator/chunk semantics do not belong in the mutation planner |
| `//line`, `//curve` | `drawLine`/`drawSpline` with thickness and shell mode | GEOMETRY | **VANILLA** pending deterministic geometry adapter |
| `//forest` | `makeForest` with random generator behavior | WORLDGEN/CUSTOM | **VANILLA** because exact generator randomness is not captured |

The newly hooked methods continue to invoke Enhanced `Mask` and legacy `Pattern`
contracts and mutate through the originating `EditSession`; specialized commands
outside these exact descriptors remain vanilla. `/overdrive status` derives all
ACTIVE values from the corresponding installed runtime hooks.

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
commands and confirm the matching bridge and accelerated counters increase before
undoing each operation. No speedup is claimed without those live measurements.

## Adaptive maximum-throughput scheduler

The fixed five-millisecond commit ceiling has been replaced by one feedback-controlled
server-thread budget. The controller observes normal START-to-END tick cost, keeps an
exponentially weighted cost and variance reserve, contracts immediately on late/severely
late ticks, and progressively recovers up to available safe headroom. It controls elapsed
time rather than blocks per tick. The existing mutation batch learner remains independent:
it limits the predicted non-preemptible queue drain while the controller decides how many
drains fit this tick. One owner receives all available capacity; multiple owners rotate
fairly over the same global capacity. No sleeps, cooldowns, or command-specific rates exist.

`/overdrive status` exposes the current budget, actual accelerator use, estimated server
headroom, and maximum observed accelerator tick. The bounded destination snapshot
foundation is now available as an incremental, chunk-local, immutable, lossless view with
optional halo capture; adapters must advance its capture cursor under this same deadline.

The broad command-family matrix above remains intentionally truthful: this source-only
increment does not label an adapter ACTIVE until its exact Enhanced command hook is
installed. The adaptive controller currently drives the installed deferred paste owner;
the synchronous legacy set path has no artificial per-tick throttle. Future command owners
implement `MutationOperationOwner` and therefore share the same scheduler rather than
creating command-local commit loops.
