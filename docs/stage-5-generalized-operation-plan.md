# Stage 5: generalized operations and clipboard paste

This architectural review is the implementation gate for Stage 5. It adds no
paste hook and no second scheduler. WorldEdit Enhanced 6.3.0 is the semantic
authority, modern FAWE is the primary batching/ownership reference, and legacy
FAWE supplies useful Forge 1.7.10 details rather than code to port blindly.

## 1. Enhanced reorder implementation and exact stages

The authority is Enhanced's `extent/reorder/MultiStageReorder.java`, supported
by the exact tables in `blocks/BlockType.java`.

### Extent position, enablement, and flushing

`EditSession` builds, from world outward: `FastModeExtent`,
`SurvivalModeExtent`, `BlockQuirkExtent`, `ChunkLoadingExtent`,
`LastAccessExtentCache`, the `BEFORE_CHANGE` event wrapper,
`DataValidatorExtent`, `BlockBagExtent`, `MultiStageReorder`, the
`BEFORE_REORDER` wrapper, `ChangeSetExtent`, `MaskingExtent`,
`BlockChangeLimiter`, and the `BEFORE_HISTORY` wrapper. A normal write is
therefore masked and limited before history; history is recorded before the
write is queued; lower validation, block-bag, quirk, and platform behavior runs
when a queued write reaches the delegate.

Reorder starts disabled. `enableQueue()` enables it. The supplied
`disableQueue()` flushes when enabled and then literally calls
`setEnabled(true)`, rather than false. This apparent Enhanced defect is part of
the observed source behavior and must not be silently “corrected.”
`flushQueue()` completes `EditSession.commit()`. Delegate commits compose
outside-in through `commitBefore()`, so reorder's commit precedes its delegate's
commit. Command infrastructure flushes an edit session after execution;
selection-composition commands enable its queue before applying their operation.
Direct API callers must themselves reach the normal commit/flush lifecycle.

### Classification

For each enabled `setBlock(location, desired)`, reorder first reads the current
**destination** using `getLazyBlock`, then evaluates these branches in order:

1. desired ID in `shouldPlaceLast`: append the complete `(BlockVector,
   BaseBlock)` to **stage 2**;
2. else desired ID in `shouldPlaceFinal`: append it to **stage 3**;
3. else current destination ID in `shouldPlaceLast`: immediately send air and
   then desired state to the delegate;
4. else append desired state to **stage 1**.

The queued branches return whether current and desired ID/data differ; NBT is
not part of this equality test. Tuple lists preserve insertions and do not
coalesce duplicate coordinates. Desired state chooses stages 1–3. Destination
state participates only in eager removal of an existing fragile block.

`shouldPlaceLast` (stage 2) contains: sapling, bed, powered/detector rails, long
grass, dead bush, piston extension, yellow/red flowers, brown/red mushrooms,
torch, fire, redstone wire, crops, ladder, normal rail, lever, stone/wood
pressure plates, both redstone torches, stone button, snow layer, portal, both
repeaters, trapdoor, vine, lily pad, nether wart, normal/sticky piston bases,
piston moving piece, cocoa, tripwire hook/wire, flower pot, carrots, potatoes,
wooden button, anvil, light/heavy weighted pressure plates, both comparators,
activator rail, iron trapdoor, carpet, double plant, and inverted daylight
sensor. Piston extension is inserted twice, harmlessly.

`shouldPlaceFinal` (stage 3) contains standing sign, wooden door, wall sign,
iron door, cactus, reed, cake, piston extension, piston moving piece, standing
banner, and wall banner. Because stage 2 is tested first, piston extension and
moving piece actually enter stage 2, not stage 3.

Air, water/lava, sand, and gravel have no special reorder classification: they
normally enter stage 1. Anvil is explicitly stage 2 for asynchronous placement.
Unknown numeric/modded IDs enter stage 1 unless Enhanced knows the ID. This says
nothing about whether raw mutation is safe; reorder stage and mutation strategy
are separate classifications.

### Commit and dependency ordering

`commitBefore()` returns an `OperationQueue` containing:

1. a `BlockMapEntryPlacer` over concatenated stage-1 and stage-2 iterators, so
   **all stage 1 globally precedes all stage 2**, with encounter order retained
   within each list; then
2. the stage-3 committer.

Stage 3 copies coordinates into a `HashSet` and desired states into a `HashMap`.
It chooses an arbitrary remaining coordinate and walks dependencies. A lower
door half first adds its upper half to the front of the placement deque. A rail
first adds the block below. It then follows `BlockType.getAttachment(id,data)`
toward the supporting block. Walking ends when support is outside the remaining
set or a cycle is detected. The deque is applied support-to-dependent. The
attachment table, including metadata-specific directions, is authoritative.

Thus stage 3 guarantees encoded dependency order, not stable coordinate order.
Its hash map also collapses duplicate stage-3 coordinates to the last state.
The implementation has no per-chunk boundary; dependencies may cross chunks.

The entire `BaseBlock`, including tile NBT, reaches the downstream extent. Tile
installation is **not a distinct reorder stage**. Reorder itself issues no
neighbor/physics callbacks: callbacks happen when delegate/world `setBlock` is
called, subject to the lower extent/platform implementation. Buffers clear only
when stage 3 completes; reorder cancellation is a no-op.

**Overdrive requirement:** preserve global barriers stage 1 → stage 2 →
dependency-ordered stage 3, plus eager fragile-destination removal. Native or
custom callbacks must retain original sequence where encounter order is
observable. Stage 3 must use the Enhanced table and an operation-wide graph.

## 2. Enhanced clipboard and `//paste` call path

The standard path is exact and compact:

1. `command/ClipboardCommands.paste(...)` implements `/paste` with `-a`
   (ignore air), `-o` (paste at clipboard origin), and `-s` (select pasted
   bounds). It reads `LocalSession.getClipboard()`, chooses clipboard origin or
   the session placement position, builds an operation, and calls
   `Operations.completeLegacy(operation)`. It then optionally updates the
   selection and prints completion.
2. `session/ClipboardHolder` owns the clipboard, source `WorldData`, and
   accumulated transform. `createPaste(...)` creates `PasteBuilder` for the
   destination extent/world data.
3. `session/PasteBuilder.build()` wraps the clipboard in `BlockTransformExtent`
   so destination registry/transform hooks transform block state. It creates
   `ForwardExtentCopy` from clipboard region/origin to the requested point and
   assigns the holder transform. `ignoreAirBlocks(true)` adds
   `ExistingBlockMask(clipboard)` as a **source** mask.
4. `ForwardExtentCopy.resume()` builds `ExtentBlockCopy`, wraps it in
   `RegionMaskingFilter`, optionally combines a source function, and traverses
   via `RegionVisitor`. `ExtentBlockCopy.apply()` gets the complete source block,
   computes `transform(position - from) + to`, then calls destination
   `setBlock`.
5. In the same repetition it gets `source.getEntities(region)` and runs
   `EntityVisitor` with `ExtentEntityCopy`. Entity copy is on by default in this
   PasteBuilder. Position pivots around rounded block centers, direction is
   transformed, type/NBT is retained, and hanging entity `TileX/Y/Z`,
   `Direction`, and legacy `Dir` are rewritten. Move-like uses remove the source
   only after successful destination creation.
6. Repetitions compose transforms. Completed visitors contribute affected
   counts. Command/session completion later flushes the edit session and stores
   its change set under the user's `LocalSession` for undo/redo.

Enhanced 6.3.0's standard PasteBuilder exposes no biome option and this
ForwardExtentCopy performs no biome traversal. Tile data is embedded in the
`BaseBlock` returned by clipboard `getBlock`; no separate tile visitor exists.
`-a` is distinct from the destination session mask. Destination masks, change
limit, history, block bag, survival/fast modes, validation/quirks, reorder,
event extents, and native behavior all arise from the destination extent chain.

Deprecated `CuboidClipboard` helpers and other clipboard APIs ultimately use
similar extent copies. The first adapter will recognize the standard graph, not
hook the command name or assume every ForwardExtentCopy is a paste.

## 3. Modern FAWE paste/queue concepts

Concrete supplied modern code supports these architectural concepts:

* Modern `PasteBuilder` and `ForwardExtentCopy` remain semantic glue for
  transform, masks, entities, biomes, repetitions, and affected accounting;
  optimized clipboard implementations may take direct paths.
* `IChunkGet` represents existing immutable state while `IChunkSet` represents
  desired blocks, tiles, entities, biomes, side effects, masks, and finalization
  intent. `ChunkHolder` owns their pre-process/apply/finalize/post-process
  lifecycle.
* `ChunkFilterBlock` and region `processSet` paths iterate primitive sections
  with reusable cursors. Full-section operations and masks avoid a
  `Vector`/block object for every cell.
* `IBatchProcessor`, `MultiBatchProcessor`, and processor holders provide
  ordered seams for history, masks/limits, NBT policy, height, lighting, and
  side effects rather than embedding policies in storage.
* Batch history compares get/set state and emits compact changes before apply.
  Side-effect sets explicitly describe requested effects.
* Queue application exposes completion/failure and separates preparation,
  native apply, finalization, and post-processing.

Adopt ownership, immutable-get/desired-set separation, primitive section
iteration, processor seams, explicit effects, and propagated completion. Do not
port modern state ordinals, palettes, world heights, relighters, platform queues,
parallel assumptions, or Minecraft APIs to 1.7.10.

## 4. Legacy FAWE 1.7.10 paste/reorder behavior

The supplied legacy platform subset, especially `ForgeQueue_All`, demonstrates
numeric ID plus metadata storage, extended IDs, 1.7.10 section arrays, tile NBT,
Forge multipart awareness, S21/S22 packets, watcher targeting, height/light
bookkeeping, and chunk-queued writes. The larger legacy source redirects
ForwardExtentCopy and uses queue-aware visitor/preload paths so clipboard
traversal fills queue chunks instead of mutating once per visited block.

Keep its chunk aggregation and verified platform details. Reject its mutable
global queue/cache coupling, ambiguous-thread live reads, broad exception
swallowing, temporary empty-section substitution, mixed mutation/packet flow,
and implicit completion. Legacy fast placement cannot replace Enhanced's exact
reorder or extent semantics. Stage 2 already extracts the safe Forge mechanics;
Stage 5 composes them under explicit phases.

## 5. Generalized Overdrive operation-plan model

Introduce reusable, narrow contracts:

* `WorldEditOperationAdapter` recognizes an operation graph before mutation and
  captures semantics or returns a precise unsupported reason.
* `ConstantFillAdapter` migrates the existing `RegionVisitor`/`BlockReplace`
  case; `PasteOperationAdapter` recognizes the standard ForwardExtentCopy graph;
  later adapters such as `ReplaceOperationAdapter` target the same backend.
* `OperationPlan` owns identity/kind, source volume, semantic policy, immutable
  transform, preparation-input class, ordered commit phases, snapshots, chunk
  plans, history sink, entities, finalization intent, accounting, cancellation,
  and first failure.
* `PreparedOperationChunk` owns primitive buffers partitioned by phase and
  execution class. It supports dense state arrays, homogeneous sections/runs,
  sparse packed coordinate/state arrays, tile records, and deduplicated effect
  coordinates. It never owns a live chunk.

The backend knows phases and placement requirements, never command names.
Adapters may delegate a narrow callback to Enhanced while retaining operation
ownership.

Preparation input is explicit:

* `PURE`: constant states, immutable clipboard arrays, coordinate-only masks,
  and immutable transforms may run on workers.
* `SNAPSHOT_REQUIRED`: destination/source masks, overwrite/history decisions,
  and tile/biome reads consume immutable server-captured snapshots on workers.
* `SERVER_THREAD_REQUIRED`: live patterns, unknown callbacks, block-bag/survival
  accounting, and side-effecting masks run as bounded server-thread preparation.
  Accepted changes can still use the common accelerated commit.

Workers never touch a live extent, world, chunk, tile, entity, registry hook, or
mod callback.

## 6. Per-change placement classifications

Use orthogonal attributes, not one overloaded enum:

* mutation: `RAW_SAFE`, `NATIVE_REQUIRED`, or `SPECIAL_HANDLER`; unknown/modded
  blocks default native, not operation fallback;
* reorder: `EAGER_FRAGILE_REMOVAL`, `STAGE_1`, `STAGE_2`, or
  `STAGE_3_DEPENDENCY`, derived using Enhanced's exact desired/destination test;
* payload: state, optional tile NBT, optional entity or biome record;
* effects: native callback, neighbor/comparator notification, tile validation,
  lighting/height, packets, and custom-extent requirements;
* sequence: traversal ordinal wherever Enhanced encounter order is observable.

Tile/entity are payload/final phases rather than exclusive mutation classes. A
chest may use a raw-safe shell and native tile installation; a mod tile may use
native block and tile paths. `SPECIAL_HANDLER` requires proven equivalence;
otherwise use native. Whole-operation fallback is reserved for an unmodelled
operation-wide contract, not an unusual cell.

## 7. Global phase and barrier requirements

The initial phase graph is:

1. server-thread semantic capture and compact snapshots;
2. bounded preparation of all chunks (streaming only when it cannot cross the
   first barrier);
3. eager fragile-destination removals in global encounter order;
4. Enhanced stage 1 across the entire operation;
5. global barrier, then Enhanced stage 2;
6. global barrier, then stage 3 using an operation-wide attachment graph;
7. tile installation/validation after owning blocks exist;
8. entity creation;
9. required deduplicated lifecycle/neighbor/comparator work;
10. once-per-chunk height/light finalization and synchronization;
11. history seal and Enhanced session completion.

Raw-safe stage-1/2 changes may batch by chunk because barriers preserve intended
state. Native/custom changes retain sequence. Stage 3 cannot commit complete
chunks independently: build door, rail, and attachment edges across chunk
boundaries, emit support before dependent, and preserve Enhanced cycle/outside-
set behavior. Independent nodes can be chunk-batched only within a dependency
frontier.

## 8. Stage 3 coordinator changes

Extend the existing coordinator; do not introduce a paste scheduler.

* Add `CommitPhase` and separate `OperationPhaseProgress`; keep
  `OperationState` as coarse lifecycle rather than block-specific states.
* Ready queues become operation/phase queues. Preserve operation round-robin;
  only an operation's current phase is eligible.
* Track outstanding work, buffers, time, and counts per phase. Advance only when
  submissions close, preparations finish, ready work drains, and no commit is
  active.
* Permit a global planning gate and explicit barriers. Advance on the captured
  server thread.
* A commit unit remains one live chunk mutation, or a bounded native sequence
  slice where strict order requires it, under the existing tick budget. Never
  interrupt a live chunk mutation midway.
* Check cancellation between snapshot batches, tasks, transitions, and chunks.
  First failure stops admission/advancement, releases uncommitted buffers, keeps
  committed history, and never claims rollback.
* Extend diagnostics with operation kind/source volume/actual changes, snapshot,
  preparation, history, each phase, raw/native/tile/entity counts, light,
  packets, total time, and peak bytes.

## 9. Snapshot design

Capture only touched chunks and requested channels on the server thread.
`ChunkSourceSnapshot` contains chunk coordinates, selected section/range data,
primitive numeric IDs including extended high bits, packed metadata, and
optional channels selected by `SnapshotRequirements`. Optional channels are
deep-copied normalized tile NBT keyed by packed local coordinate and the
256-byte biome array. Omit light, height, entities, irrelevant columns, and
unrelated tiles unless a demonstrated consumer requests them.

Use immutable sections with singleton all-air/all-one-state forms and copy only
selected sections. Snapshot bytes share global/per-operation backpressure with
prepared changes and are released after their last consumer. Capture is
server-thread tick-budgeted and cancellation-aware; workers receive only a
fully published immutable snapshot.

## 10. History design direction

Enhanced `BlockOptimizedHistory` defers `BlockChange` allocation but retains
parallel object tuples of `BlockVector` and `BaseBlock`, unsuitable for millions
of cells and tile-heavy NBT.

Design an `OverdriveChangeSet` adopted by the same EditSession/LocalSession undo
entry:

* chunk-keyed packed coordinates/deltas;
* palette or run encoding for before/after numeric ID/meta with raw fallback for
  high entropy;
* independently deduplicated/compressed tile-before/tile-after NBT;
* append after semantic acceptance and before live commit; track a committed
  prefix so partial failure exposes undo only for applied cells;
* lazy Enhanced-compatible reverse/forward iterators, or recognized Overdrive
  undo/redo adapters;
* bounded segments with checksummed compressed disk spill, operation/session
  ownership, cleanup, and configurable limits;
* no records for ignored air, rejected masks, or unchanged state;
* attempt/change-limit accounting separate from history changed count.

A first milestone may use bounded primitive memory, but must fail closed before
mutation if the session cannot own it. Disk spill and crash cleanup precede any
arbitrary-size claim.

## 11. Tile handling

Tiles do not trigger operation fallback. Keep normalized deep-copy NBT separate
from block arrays. Establish the owning block in its reorder phase. A later
server-thread phase removes stale tiles, validates block/type compatibility,
creates through vanilla/Forge mechanisms, restores coordinates, loads NBT,
validates, marks dirty, and collects description packets. History stores tile
before and after.

Known vanilla types may use proven handlers. Multipart and unknown mod tiles use
native/special installation. If direct creation is unprovable, route only that
coordinate through Enhanced native placement with the complete BaseBlock. Never
load mod NBT on a worker.

## 12. Modded block handling

Raw mutation remains a conservative allowlist. Capture registry identity,
class/tile-provider status, Forge hooks, and multipart contracts on the server
thread. Unknown numeric IDs, mod blocks, and callback-sensitive blocks default
to `NATIVE_REQUIRED` while remaining in the operation. Preserve their reorder
stage and sequence. Promote a mod state only through a versioned, tested adapter.
A 90% ordinary/10% complex clipboard can therefore accelerate its safe majority.

## 13. Lifecycle, neighbors, fluids, and gravity

Capture effective Enhanced/Forge placement behavior rather than assuming raw
writes are equivalent. Native cells call the same downstream extent/world path
in their phase. Raw cells accumulate tile invalidation, dirty columns/sections,
neighbor and comparator targets, and light effects. Deduplicate only commutative
final notifications; preserve callbacks whose sequence is observable.

Enhanced puts fluids in stage 1, anvil in stage 2, and sand/gravel in stage 1.
Preserve those stages. Raw/native remains independent: fluids and gravity blocks
should generally use native placement until a deferred-update equivalent is
proven. They are not blacklist or operation-fallback triggers.

## 14. Entity handling

Store transformed primitive position/direction plus immutable type/NBT and
reproduce Enhanced's rounded-center pivot and hanging-entity NBT rewrite.
Enumeration/NBT capture is server-thread-only unless the clipboard is proven
immutable. Create entities in a final server-thread phase through the destination
extent/platform, retain success-based affected accounting, and remove sources
only for move semantics. Entity history is required before accelerated move
claims. Prefer normal platform spawn packets until batching is proven equivalent.

## 15. Custom extent strategy

Inspect all Enhanced event boundaries and the complete extent chain by identity
and order. Register explicit capabilities:

* `PURE_CHUNK_PROCESSOR`: deterministic worker processing;
* `SNAPSHOT_PROCESSOR`: deterministic from declared snapshot channels;
* `SERVER_CHANGE_CALLBACK`: invoke per accepted change on the server thread,
  preserving order, boolean returns, and exceptions, then accelerate commit;
* `OPAQUE_OPERATION_COUPLED`: callback affects later traversal or has unknown
  side effects, requiring pre-mutation operation fallback.

Built-in masks, limit, history, reorder, validation, block bag, survival,
fast-mode, and quirks receive adapters or narrow delegation; they are not opaque
merely for being complex. Unknown third-party wrappers remain the legitimate
fallback boundary.

## 16. Packet and finalization strategy

Retain watcher-targeted Stage 3 synchronization. Union final changed coordinates
across phases and send once after block/tile work: sparse S22 changes, section or
chunk refresh for dense changes, full chunk when biomes change, then tile
description packets. Entity creation uses platform spawn flow unless an exact
batch adapter exists. Avoid intermediate packets unless a callback requires
client visibility.

Union height/light masks across phases and finalize each chunk once at the latest
safe point; cross-chunk lighting may require an operation frontier. Do not run
`generateSkylightMap()` after every phase. Native changes join the same dirty and
packet summary.

## 17. Migration of current `//set`

Keep the completion interception and current constant-fill planner as the first
adapter. Refactor immutable cuboid/state capture into a single-stage
`OperationPlan` with `PURE` input and normally Enhanced stage 1. Retain its
Stage 2 buffers, raw/native classifier, history/limit checks, and compatibility
inspector. Do not broaden behavior during migration; differential-test output,
history, and diagnostics. Paste then reuses its coordinator, accounting,
finalization, and synchronization.

## 18. Concrete first accelerated schematic paste

Implement in reviewable slices:

1. add coordinator phase abstractions and migrate constant fill unchanged;
2. add compact snapshots and primitive bounded history; prove set undo/redo;
3. recognize the exact standard PasteBuilder/ForwardExtentCopy graph before
   `completeLegacy`, capturing transform, ignore-air, entities, and clipboard;
4. construct a section-oriented clipboard view with dimensions/origin, primitive
   ID/meta arrays, homogeneous runs/sections, tile and entity tables—without one
   Vector/BaseBlock allocation per cell;
5. partition transformed destinations, omit ignored air before destination or
   history work, classify reorder/raw-native routes, and run masks/limits/extents
   according to preparation class;
6. commit global stage 1, barrier, stage 2, barrier, global dependency stage 3;
7. install tiles, create entities, finalize lifecycle/light/height, synchronize,
   seal history, and complete the original operation with matching affected and
   session behavior;
8. expand from known immutable clipboards to schematic-backed/modded cases only
   after differential tests.

The first useful target includes air, metadata, extended IDs, vanilla tiles,
stage-2/3 blocks, transforms, and entities—not a stone-only special case.

## 19. Compatibility risks

Risks include Enhanced's apparent `disableQueue` defect; encounter order;
duplicate destinations from transform/repetition; mod metadata transform hooks;
tile ID/NBT normalization; masks observing a changing destination; survival and
block-bag consumption order; limit attempt versus change counting; event
extents; native callbacks observing adjacent chunks; cross-chunk attachments;
entity UUID/collisions; IDs above 255; border lighting; partial failure history;
and memory/disk exhaustion.

Fallback before mutation is correct for unrecognized operation graphs,
operation-coupled custom extents, unsupported transforms, unavailable bounded
history ownership, or unprovable changing-world masks. A single unknown block,
tile, or mod state is not such a boundary: route it natively. Mid-operation
failure retains the first cause, stops later phases, releases buffers, reports
partial commit, and preserves undo for the committed prefix.

## 20. Benchmark and validation plan

Differential-test against plain Enhanced 6.3.0 from byte-identical world,
clipboard, session/player, and mod state. Compare final ID/meta, tile NBT,
entities, requested biomes, undo/redo, block-bag inventory, limit result,
affected count, command/selection feedback, loaded chunks, lighting/height, and
observable neighbor/comparator behavior.

The matrix includes stone/air; explicit and ignored air; metadata; IDs >255;
chests/furnaces; signs; doors; beds; torches; all rail types; redstone wire,
torches, repeaters, and comparators; fluids; sand/gravel/anvil; representative
multipart/mod tiles; mixed vanilla/mod sections; ordinary and hanging entities;
destination masks; limits/block bags/survival/fast mode/reorder; rotation and
mirror; chunk-edge attachments/light; duplicate destinations; cancellation; and
injected failure at every phase.

Benchmark warm/cold 16³, 64³, 256³, and memory-bound clipboards with homogeneous,
high-entropy, tile-heavy, and mixed-native content. Record source/selected
volume, attempted/actual/ignored/masked/unchanged counts, snapshot/preparation/
history time, every commit phase, raw/native/tile/entity counts, finalization,
lighting, packets, wall time, budget overruns, chunks/sections, peak
plan+snapshot+history bytes, spill bytes, and cancellation latency. Report
throughput and tick impact without claiming complete WorldEdit coverage.

## Stage 5 decision

Proceed with the generalized phased plan and make paste the next adapter after
constant-fill migration. Enhanced's actual global reorder and session/extent
ownership are non-negotiable semantics. Chunk-oriented primitive desired state,
immutable snapshots, bounded history, explicit processors/effects, and the
existing fair tick-budgeted coordinator are the performance core. Hybrid
raw/native execution is normal; whole-operation fallback is exceptional.
