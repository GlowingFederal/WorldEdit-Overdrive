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
| `//replace` | `replaceBlocks(region, Mask, Pattern)`; omitted mask becomes `ExistingBlockMask` | FILTER | **VANILLA** pending bounded destination snapshots and exact mask/pattern adapters |
| `//walls` | `makeCuboidWalls(region, Pattern)` | GEOMETRY | **VANILLA** pending command ownership adapter |
| `//faces`, `//outline` | aliases sharing `makeCuboidFaces(region, Pattern)` | GEOMETRY | **VANILLA** pending command ownership adapter |
| `//overlay` | `overlayCuboidBlocks(region, Pattern)` | GEOMETRY/FILTER | **VANILLA** pending immutable column snapshots |
| `//naturalize` | `naturalizeCuboidBlocks(region)` | FILTER/column topology | **VANILLA** pending immutable column snapshots |
| `//stack` | `stackCuboidRegion(region, direction, count, copyAir)` | COPY | **VANILLA**; source-once reuse and selection-shift feedback must be preserved |
| `//move` | `moveRegion(region, direction, count, true, leaveBlock)` | COPY/MOVE | **VANILLA**; overlap, source clearing, and selection shift require constrained plans |
| `//smooth` | `HeightMap` plus Gaussian `HeightMapFilter`, repeated | NEIGHBORHOOD | **VANILLA** pending a halo height snapshot and staged iterations |
| `//deform` | `deformRegion` with a user expression | CUSTOM/COPY | **VANILLA**; arbitrary expression semantics are not worker-safe |
| `//hollow` | `hollowOutRegion` with Manhattan thickness and pattern | NEIGHBORHOOD/GEOMETRY | **VANILLA** pending topology snapshot and pattern capability |
| `//regen` | temporarily removes the session mask and calls `World.regenerate` | WORLDGEN | **VANILLA** intentionally; generator/chunk semantics do not belong in the mutation planner |
| `//line`, `//curve` | `drawLine`/`drawSpline` with thickness and shell mode | GEOMETRY | **VANILLA** pending deterministic geometry adapter |
| `//center` | `EditSession.center(region, pattern)` | GEOMETRY | **VANILLA** pending pattern adapter |
| `//forest` | `makeForest` with random generator behavior | WORLDGEN/CUSTOM | **VANILLA** because exact generator randomness is not captured |

Unknown Pattern, Mask, RegionFunction, operation subclasses, or extent graphs
remain invocation-local vanilla fallbacks. This avoids permission, edit limit,
mask, random ordering, and extent-chain bypass. `/overdrive status` derives the
ACTIVE values for set and paste from their actually installed runtime hooks and
labels the audited, unhooked families VANILLA.

## Runtime validation

On an Enhanced 6.3.0 Forge 1.7.10 server, use a large selection and record
wall time, largest commit tick, changed count, and chunks touched for vanilla
Enhanced and Overdrive. Run `//set stone`, `//undo`, a full-feature large
`//paste`, `//paste -a`, and `//undo`; after each operation inspect
`/overdrive status`. Also run `//replace stone dirt`, `//walls stone`,
`//faces stone`, `//overlay grass`, `//stack 5`, `//move 10`, and their undo
commands to confirm the matrix truthfully reports VANILLA until those adapters
are installed. No speedup is claimed without those live measurements.

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
