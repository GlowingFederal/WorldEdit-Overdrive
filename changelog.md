# Changelog

Changes are listed oldest to newest.

## (abcc128 Fix Forge chunk commit correctness)

- Assigned live Forge chunk commits and queued lighting to the server thread.
- Recalculated vanilla section counters and affected height-map columns.
- Added explicit removal, construction, validation, dirtying, and optional Forge
  Multipart synchronization for changed tile entities.
- Added the Forge 1.7.10 manual regression checklist and documented deferred risks.

## (51f3513 Normalize self-contained WorldEdit packaging)

- Replaced snapshot WorldEdit core coordinates with one pinned 6.1.3 baseline.
- Embedded the WorldEdit core remainder and Forge 1.7.10 platform in the
  canonical KAWE Forge distribution while retaining local FAWE overrides.
- Added artifact checks for required WorldEdit/Forge/FAWE classes, duplicate
  entries, and accidental reference-source packaging.
- Corrected Forge metadata and documented ownership, coexistence, remaining
  dependencies, Enhanced follow-ups, and clean-server regression coverage.

## (88ec106 Restore legacy WorldEdit snapshot baseline)

- Restored `com.sk89q.worldedit:worldedit-core:6.1.3-SNAPSHOT` in every module
  changed by the packaging-normalization pass.
- Prioritized EngineHub's WorldEdit Maven repository ahead of legacy aggregate
  mirrors while preserving the self-contained Forge shadow packaging rules.
- Corrected the build and ownership documentation to identify the snapshot as
  an embedded build input rather than an external runtime dependency.

## (a5d10cc Layer KAWE on WorldEdit Enhanced 6.3.0)

- Replaced the legacy WorldEdit core and Forge dependencies with the external
  WorldEdit Enhanced 6.3.0 Curse Maven artifact.
- Removed WorldEdit shadow assembly and KAWE's competing Forge player wrapper.
- Required Enhanced's `worldedit` Forge mod and documented FalsePatternLib,
  source ownership, retained FAWE overrides, and `AbstractChunkUpdater`.

## (44d66ab Fix Forge 1.7.10 core output packaging)

- Added the complete compiled `:core` source-set output directly to the
  canonical Forge shadow jar instead of relying on project dependency filtering.
- Kept WorldEdit Enhanced and FalsePatternLib external while preserving KAWE's
  WorldEdit overrides, FAWE classes, Forge output, and private shaded libraries.
- Documented the assembled artifact topology and Enhanced integration boundary.

## (b20782e Establish WorldEdit Overdrive addon foundation)

- Replaced the active KAWE multi-module topology with one conventional root
  Forge 1.7.10 addon project.
- Added minimal `worldeditoverdrive` metadata and an owned entry point that
  verifies the WorldEdit Enhanced API at initialization without acceleration.
- Kept Enhanced external, retired Shadow and custom packaging from the active
  build, and documented the normal `build/libs` runtime artifact.

## (9c93864 Restrict compilation to Overdrive addon sources)

- Limited the active Java source set to the owned WorldEdit Overdrive package.
- Kept the legacy KAWE/FAWE Forge implementation on disk solely as excluded
  reference material for later porting.
- Updated the project and integration documentation to describe the minimal
  addon boundary and deferred acceleration work.

## (3bb8526 Implement Stage 2 Forge chunk backend)

- Added operation-owned hybrid sparse/dense chunk preparation with explicit air,
  extended numeric IDs, metadata, tile NBT, biomes, dirty columns, exact change
  counts, and lightweight memory estimates.
- Added a server-thread-only Forge writer with conservative raw classification,
  native compatibility fallback, vanilla section-counter and contextual height/
  light finalization, explicit tile lifecycle, and propagating failures.
- Added a compact commit result and cancellation-ready section boundaries without
  connecting the backend to WorldEdit commands or `EditSession`.
- Documented the subsystem-by-subsystem legacy FAWE, supplied modern FAWE,
  WorldEdit Enhanced, and Overdrive design comparison.

## (05c5bc8 Review Stage 2 against modern FAWE core)

- Re-reviewed the completed Forge 1.7.10 chunk backend against concrete modern
  FAWE core queue, chunk-set, processor, lighting, NBT, and memory implementations.
- Replaced boxed raw-section tracking with a primitive section mask and extended
  commit results with light-dirty masks, raw/native counts, and tile/biome flags.
- Documented the subsystem KEEP/MODIFY/DEFER decisions, retained correctness-first
  skylight and tile behavior, and recorded modern designs rejected for 1.7.10.

## (bb61f2e Implement Stage 3 bounded execution coordinator)

- Added explicit operation lifecycle, statistics, cancellation, failure, and
  completion semantics around operation-owned Stage 2 chunk buffers.
- Added bounded named preparation workers, global/per-operation byte
  backpressure, isolated oversize handling, and fair tick-budgeted commits.
- Added captured-server-thread Forge tick integration and watcher-targeted 1.7.10
  sparse, dense, biome, and tile synchronization.
- Documented the concrete modern/legacy/Enhanced comparison, shutdown behavior,
  diagnostic API, deferred work, and the deliberate absence of WorldEdit session
  or command integration.

## (3687c33 Begin Stage 4 constant fill integration)

- Added a descriptor-checked, single-method Enhanced `EditSession#setBlocks` redirect with conservative pre-mutation eligibility and transparent native fallback.
- Added world-read-free cuboid/chunk/section planning, direct dense full-section population, constant legacy state and tile-NBT translation, server-thread Enhanced history capture, and watcher-owned synchronization.
- Documented the exact Enhanced execution/extent path, modern and legacy FAWE comparison, completion and history model, compatibility compromises, unclaimed runtime matrix, benchmark plan, and blockers before Stage 5.

## (6c2c182 Diagnose Stage 4 set hook activation)

- Corrected the coremod transformer exclusion boundary so the injected Stage 4
  bridge remains visible to LaunchClassLoader-owned WorldEdit and Minecraft
  classes.
- Added concise core-plugin, transformer, descriptor, hook-state, FML metadata,
  runtime-type, counter, and first-fallback diagnostics.
- Kept exact session/cuboid safety, added a conservative constant-pattern
  resolver, and documented the runtime verification procedure and the reason
  WorldEdit's package-derived API version may be unknown.

## (4c30058 Harden and measure Stage 4 constant fills)

- Added one stable operation summary with separate planning, Enhanced history,
  commit, skylight finalization, synchronization, wall-time, placement, section,
  changed-count, chunk, and prepared-memory measurements.
- Filtered observably unchanged states before history and placement, including
  coordinate-normalized explicit tile NBT comparison, while retaining dense
  section representation and conservative raw/native selection.
- Matched Enhanced's attempted-position block-limit accounting through a narrow
  fail-closed limiter handshake and documented the synchronous Stage 3 call path,
  unavailable runtime matrix, validation status, and provisional Stage 5 choice.

## (d049766 Stage 4.6 dedicated-server compatibility and observability)

- Added one Forge-routed Overdrive logger, startup proof, reliable terminal
  operation summaries, failure context, and a bounded immutable latest snapshot.
- Added dedicated-console/operator `/overdrive status` and `/overdrive stats`
  commands without introducing any client dependency.
- Added fail-closed constant-pattern, cuboid, EditSession, and extent-chain
  compatibility inspection while retaining masks, block bags, survival, reorder,
  unknown wrappers, and custom behavior on Enhanced.
- Documented timing semantics, artifact diagnosis, the dedicated runtime matrix,
  fallback guarantees, remaining restrictions, and timing-led optimization.

## (af1a180 Correct Stage 4 composed set interception)

- Traced Enhanced 6.3.0 `/set` registration through `SelectionCommand`,
  `ApplyCommand`, `Apply`, `RegionVisitor`, and `BlockReplace`, including the
  exact owner of the `Operation completed (...)` response.
- Moved the active Stage 4 redirect to the composed command immediately before
  traversal while retaining the old `EditSession#setBlocks` hook as a separately
  reported legacy diagnostic.
- Preserved nullable, pre-mutation fallback and existing command feedback, and
  made ACTIVE status depend only on installation of the real command-path hook.

## (eecd9b6 Fix Stage 4 Enhanced completion interception)

- Anchored the active command transformer solely to the exact static
  `Operations.completeBlindly(Operation): void` boundary and captured its
  already-stacked operation without assuming a construction helper or fixed
  command locals.
- Reworked the operation bridge to derive the supported `RegionVisitor`, region,
  `BlockReplace`, and owning `EditSession` shape from that completion argument,
  retaining untouched native fallback for every unsupported operation.
- Made absent, ambiguous, or incompatible completion bytecode fail open with
  explicit diagnostics and accurate ACTIVE/INACTIVE status, and documented the
  runtime descriptors, stack strategy, frame recomputation, and remaining
  validation boundary.

## (88633ae Document generalized operation and paste architecture)

- Recorded Enhanced 6.3.0's exact reorder stages, global ordering rules,
  clipboard paste traversal, extent-chain semantics, and completion behavior.
- Compared supplied modern and legacy FAWE queue, paste, history, side-effect,
  and Forge 1.7.10 concepts without proposing a direct port.
- Defined the phased hybrid operation plan, per-change raw/native routing,
  snapshots, bounded history, tiles, entities, lifecycle handling, custom extent
  compatibility, migration sequence, risks, and differential validation plan.

## (ea0a295 Add generalized phased operation engine)

- Added operation-level phase plans, per-phase progress and memory ownership,
  phase-gated round-robin coordinator queues, explicit barriers, cancellation,
  first-failure handling, and terminal finalization accounting.
- Added chunk and bounded ordered-sequence scheduling units around the retained
  Stage 2 chunk representation, plus a generic deterministic operation-wide
  dependency graph with explicit cycle policy.
- Added Stage 5A design documentation and synthetic dependency/phase model tests;
  clipboard paste, snapshots, compressed history, and block-specific dependency
  tables remain intentionally deferred.

## (fc5b9f2 Begin Stage 5B snapshots and primitive history)

- Added immutable, requested-channel chunk snapshots with compact air,
  homogeneous, and dense legacy numeric-state sections plus deep-copied tile
  NBT and explicit byte accounting.
- Added bounded primitive Enhanced-owned history with lazy undo/redo adapters,
  tile before/after state, and committed-prefix failure behavior.
- Migrated accelerated constant fill recording to primitive history and
  documented the Stage 5B lifecycle, current in-memory limit, spill status,
  verification boundary, and remaining Stage 5C work.

## (11097fb Repair Stage 5B Enhanced history integration)

- Replaced the legacy FAWE history base with Enhanced 6.3.0's native `ChangeSet`
  contract and attached accelerated primitive segments through the owning edit
  session's existing history.
- Preserved full-width coordinates, packed legacy block state, committed-prefix
  iteration, defensive tile NBT ownership, and bounded retained-memory accounting.
- Decoupled chunk synchronization from concrete operation types with a result
  value carrying strategy and tile packet counts, fixing generalized coordinator
  accounting.
- Expanded Stage 5B history tests and documented the native history seam,
  iterator direction, coordinate representation, and normal-operation isolation.

## (b8d5aaf Begin Stage 5C paste compatibility foundation)

- Added independent paste-hook status counters and `/overdrive status` output,
  without conflating paste attempts with the existing constant-set bridge.
- Added a fail-open runtime `ForwardExtentCopy` shape gate that leaves Enhanced
  bytecode untouched until the asynchronous command continuation is safe.
- Added a strict standard-PasteBuilder operation adapter and immutable primitive
  clipboard-view foundation, and documented verified source flow and remaining
  Stage 5C compatibility work without claiming active acceleration.

## (35f88ae Establish Stage 5C paste continuation lifecycle)

- Corrected the narrow LaunchWrapper target matcher to recognize the exact
  dotted or internal name in either `name` or `transformedName`, and verified
  that Enhanced's standard `PasteBuilder` directly constructs the concrete
  `com.sk89q.worldedit.function.operation.ForwardExtentCopy` operation.
- Added explicit not-seen, incompatible, compatible, and installed runtime-shape
  diagnostics with exact field, interface, superclass, and `resume` descriptor
  validation while preserving unchanged, fail-open Enhanced bytecode.
- Added a Java 8 atomic paste-continuation lifecycle and explicit bridge ownership
  result for future scheduler integration; neither is connected to traversal or
  mutation, and paste acceleration remains inactive.
- Documented Enhanced's synchronous returned-operation loop, command feedback
  boundary, cancellation limitation, and why both early completion and blocking
  worker waits are unsafe.
- Added focused class matching, runtime shape, diagnostic transition, lifecycle,
  illegal-transition, cancellation, failure, and concurrent completion tests.

## (a7100aa Reconcile Stage 5C with Enhanced paste shape)

- Recorded that live Enhanced 6.3.0 runtime discovery exposed the invalid
  `copyEntities` field assumption, then audited the actual source and operation
  layout including entity, biome, mask, removal, and traversal semantics.
- Corrected the strict compatibility gate to require the real fields,
  descriptors, visibility, class/interface structure, and `resume` descriptor,
  with contextual deterministic mismatch diagnostics.
- Reconciled the paste adapter with the exact standard
  `BlockTransformExtent -> Clipboard` graph: entity copying is unconditional,
  biome copying is absent, ignore-air is clipboard-bound mask state, and custom,
  mutating, already-started, or otherwise ambiguous graphs fall back precisely.
- Updated runtime-shape tests and Stage 5C documentation. Paste interception,
  async submission, traversal suppression, and world mutation remain inactive.

## (7046654 Install live deferred paste interception)

- Installed the first active Stage 5C hook at Enhanced 6.3.0's exact
  `ClipboardCommands#paste(Player, LocalSession, EditSession, boolean, boolean,
  boolean)` `Operations.completeLegacy(Operation)` call site, with exact
  descriptor/call matching and fail-open vanilla fallback.
- Connected real standard paste operations to the strict paste bridge and made
  ownership explicit: vanilla execution is suppressed only after the adapter
  accepts the graph and the deferred manager successfully registers its owner.
- Added later-END-tick, server-thread execution of the retained original
  `ForwardExtentCopy`, preserving Enhanced traversal, transforms, air handling,
  repetitions, entities, affected count, extent behavior, and failure reporting.
- Retained the original EditSession through deferred execution, remembered and
  flushed its native history after mutation for live `//undo`, and delayed the
  original selection/success behavior until actual completion.
- Added active/completed/failed deferred-paste diagnostics and precise fallback
  reasons while keeping `pasteAccelerated` separate and unchanged.
- Updated Stage 5C documentation with the installed interception point,
  ownership order, tick lifecycle, feedback/history boundary, fallback rules,
  status fields, and immediate real-server verification procedure.

## (242bd8d Fix Stage 5C live paste graph adaptation)

- Replaced the invalid clipboard-region identity predicate with narrow semantic
  validation of the cloned region returned by Enhanced's `BlockArrayClipboard`,
  allowing the verified ordinary PasteBuilder graph to enter deferred execution.
- Kept graph ownership strict for source and destination classes, clipboard
  delegate, origin, transform identity, masks, source mutation, repetitions,
  and untouched traversal state; ordinary, rotated, and ignore-air PasteBuilder
  forms remain explicitly distinguished by their transform and mask fields.
- Added bounded `lastPasteGraphDiagnostic` output with runtime classes and an
  exact rejecting predicate, and documented the discovered graph, false prior
  assumption, accepted forms, and live paste/status/undo procedure.

## (85d58e2 Accelerate bounded identity clipboard pastes)

- Added the first actual Stage 5C accelerated execution path for strict,
  identity-transformed Enhanced 6.3.0 `BlockArrayClipboard` pastes, including
  source-air filtering support for `//paste -a`.
- Captured immutable full-width legacy ID/metadata clipboard views on the server
  thread, rejected entity/tile-bearing clips conservatively, and planned compact
  primitive destination arrays exclusively on daemon workers.
- Added pre-allocation per-operation/global retained-memory admission with
  deterministic release, then bounded native `EditSession.setBlock` commits to
  the five-millisecond END-tick budget and remembered native history exactly once.
- Kept the proven deferred vanilla traversal for transformations, custom or
  unsupported semantics, memory rejection, and safe pre-mutation planner failure;
  prohibited vanilla replay after accelerated mutation begins.
- Added acceleration fallback, planning/commit activity, block count, and phase
  timing diagnostics, and documented the mandatory real `//paste`, status,
  multi-tick, `//paste -a`, semantic-fallback, and `//undo` verification matrix.

## (ab091bf Flush deferred paste batches before success)

- Fixed retained Enhanced 6.3.0 `EditSession` finalization by explicitly
  flushing deferred vanilla traversal before native history is remembered, so
  queued blocks are applied alongside entities and tile data.
- Changed accelerated commits to flush at every bounded batch, count submitted
  writes separately, and count changed blocks as committed only after the
  corresponding flush succeeds.
- Included queue-drain time in commit diagnostics and moved accelerated success
  after final flush, native history retention, and success feedback; flush
  failures now remain deferred failures rather than false acceleration success.
- Documented the exact Enhanced command/session lifecycle, the reason entities
  were visible while blocks were not, undo/redo ordering, the mandatory live
  regression matrix, and the still-deferred transform/tile/entity capabilities.

## (5ec4c6c Accelerate complete standard Enhanced pastes)

- Expanded acceleration from identity block-only clips to the complete standard
  Enhanced 6.3.0 PasteBuilder graph, including rotations/reflections, tiles,
  entities, ordinary air, and combined `//paste -a` semantics.
- Prepared transformed coordinates and registry/hook-transformed legacy block
  states synchronously, retained full-width metadata and sparse complete
  BaseBlock/NBT payloads, and committed tiles through native EditSession block
  placement.
- Captured entities through Enhanced's own ExtentEntityCopy semantics, including
  transformed position, orientation, hanging-entity coordinates/directions, and
  complete BaseEntity state, then bounded native entity creation after blocks.
- Preserved batch flush ordering, actual changed-block accounting, native
  EditSession block/entity history, single completion feedback, and the pinned
  command's pasted-selection behavior.
- Added conservative block/tile/entity/plan memory accounting and semantic
  runtime diagnostics, while retaining deferred vanilla only for custom graphs,
  mutation/removal, unsupported masks/state, resource rejection, and safe
  pre-mutation failures.
- Documented feature-rich live verification using directional blocks, air gaps,
  vanilla/modded NBT tiles, and an entity across identity, rotate, flip,
  ignore-air, undo, and redo operations.

## (7b89d7b Generalize the bounded mutation commit engine)

- Introduced reusable, ordering-aware `RegionMutationPlan` and immutable destination `ChunkMutationBatch` primitives, and migrated accelerated paste planning to stable chunk-local batches.
- Changed deferred mutation scheduling to enforce one global five-millisecond deadline and round-robin owner rotation across active operations instead of granting every operation a separate tick allowance.
- Added measured adaptive commit sizing with immediate overload contraction and gradual growth between conservative 32- and 4096-mutation bounds.
- Added server-thread destination-state filtering for ordinary blocks so unchanged ID/metadata states bypass `EditSession.setBlock`, while complete NBT blocks retain the native semantic path.
- Added a runtime-derived operation support matrix to `/overdrive status` and documented the pinned Enhanced 6.3.0 command inventory, semantic categories, exact fallback reasons, and live validation procedure.
- Kept replace, geometry, stack, move, neighborhood, expression, and world-generation commands intentionally vanilla until their bounded snapshots, ownership, pattern/mask capabilities, and ordering requirements can be preserved exactly.

## (37b3992 Adaptive maximum-throughput mutation scheduling)

- Replaced the rigid five-millisecond deferred-paste ceiling with a tick-headroom feedback
  controller that backs off on late ticks, recovers progressively, permits safe bursts, and
  retains a variance-based responsiveness reserve.
- Kept adaptive non-preemptible mutation batching separate from the global time budget and
  made its target follow the live budget instead of a fixed block rate.
- Added a reusable mutation-owner lifecycle contract and the first-class incremental,
  chunk-local, immutable destination snapshot representation with lossless `BaseBlock`/NBT
  state and optional halo capture.
- Added live budget, used-time, headroom, and maximum-accelerator-tick diagnostics to
  `/overdrive status`, and documented the truthful hook matrix and scheduler behavior.
- Production compilation was intentionally not run because this change request explicitly
  prohibits compiling or adding binary files.

## (e1dc241 Accelerate Enhanced region command families)

- Installed exact Enhanced 6.3.0 `EditSession` entry hooks for `replaceBlocks`,
  `makeCuboidWalls`, `makeCuboidFaces`, `center`, `stackCuboidRegion`,
  `moveRegion`, `overlayCuboidBlocks`, and `naturalizeCuboidBlocks`.
- Activated `//replace`, `//walls`, `//faces`/`//outline`, `//center`, `//stack`,
  `//move`, `//overlay`, and `//naturalize` with native `EditSession.setBlock`
  mutation, native history/limits, lossless `BaseBlock` tile NBT snapshots, and
  source-first overlap safety for move.
- Added runtime-derived family hook, bridge-invocation, accelerated-completion,
  operation-type, fallback, and phase-timing diagnostics to `/overdrive status`.
- Kept line/curve/hollow/smooth vanilla: their exact Enhanced voxelization,
  topology, and staged Gaussian height semantics require dedicated immutable
  planners. Regen remains vanilla world generation, deform remains an arbitrary
  expression evaluator, and forest retains generator-owned randomness.
- Production compilation was intentionally not run because this change request
  explicitly prohibits compiling binary files.

## (19539e6 Fix EditSession hook verifier control flow)

- Replaced the unconditional accelerated-return prefixes for every newly hooked
  `EditSession` command-family method with explicit handled/not-handled bridge
  decisions and a not-handled branch into the untouched Enhanced implementation.
- Recomputed Java 8 stack-map frames and maximum stack/local values for the whole
  transformed `EditSession` class through the LaunchWrapper-safe class writer.
- Preserved fail-open vanilla execution for `replaceBlocks`, cuboid walls/faces,
  center, stack, move, overlay, and naturalize hooks.
- Production compilation was intentionally not run because this change request
  explicitly prohibits compiling binary files.

## (b7c1486 Resolve LaunchWrapper frame hierarchies safely)

- Replaced the object-only ASM common-superclass fallback with a cached class-header
  hierarchy resolver that reads LaunchWrapper resources without loading, initializing,
  or recursively transforming WorldEdit classes.
- Preserved superclass, interface, and array assignability during whole-class frame
  computation, including the `DownwardVisitor` to `RecursiveVisitor` merge in the
  untouched Enhanced 6.3.0 `EditSession.fillXZ` implementation.
- Made `EditSession` transformation fail open: hierarchy or frame-emission failure now
  returns the original class bytes and marks every associated command hook unavailable.
- Documented the pinned visitor relationship, safe resource-resolution strategy, and
  verifier failure policy.
- Production compilation and ASM binary verification were intentionally not run because
  this change request explicitly prohibits compiling binary files.

## (f4bae76 Make paste capture yield from command start)

- Moved standard paste block, special-state, and entity preparation out of command interception into a resumable, adaptive-deadline server-tick cursor.
- Kept immutable transformation/filter planning on tracked workers and bounded destination work by deadline, mutation batch, entity count, and distinct chunks per slice.
- Added phase, progress, worker utilization, interception, wall-versus-active occupancy, and maximum server-slice diagnostics.
- Downgraded synchronous set, replace, geometry, overlay, naturalize, stack, and move adapters to truthful `HOOKED` status and native fallthrough rather than claiming them as asynchronously safe.
- Documented the synchronous-loop audit, ordering constraints, chunk-load behavior, finalization limitation, and deferred lifecycle.
- Production compilation was intentionally not run because the user explicitly prohibits compiling or adding binary files.

## (14356e1 Bound asynchronous paste queue drains)

- Replaced cross-tick native queue accumulation with an invariant that every adaptive,
  at-most-two-chunk mutation batch is synchronously drained before another batch may be
  submitted by that paste owner.
- Added a drain-time feedback controller with conservative growth, aggressive overload
  contraction, a 4,096-mutation emergency ceiling, and regression tests for its bounds.
- Split immutable air filtering and index preparation into bounded 8,192-cell worker
  tasks, preserving server-only live world reads, mutation, entity creation, and history.
- Added stage-specific server/worker timing, native queue depth and implementation,
  flush/final-flush, concurrency, and unambiguous Overdrive-owned tick diagnostics.
- Documented the exact Enhanced 6.3.0 flush/remember lifecycle and the modern FAWE,
  legacy FAWE/WorldEdit, and pinned Enhanced architecture audit.
- Production compilation was intentionally not run because the user explicitly prohibits
  compiling or adding binary files.

2026-08-29 02:33 — Remove pathological intermediate paste flushes

- Traced Enhanced 6.3.0 flushing through `EditSession.commit()` and the complete extent
  chain: it is an unbounded synchronous operation, not a mutation-count queue primitive.
- Required the reorder queue to be disabled for acceleration, retained every block write
  through the normal server-thread `EditSession` path, and reduced hundreds of
  intermediate flushes to one WorldEdit semantic-boundary flush.
- Removed diagnostics that called nonexistent queue/extent accessors and explicitly
  reports that Enhanced does not expose those internals through its API.
- Split clipboard cells, source air, ignore-air filtering, destination matches, actual
  planned/submitted/committed mutations, and remaining mutations; added final-flush
  mutation/chunk correlation and non-preemptible budget-overrun reporting.
- Runtime paste, undo, tile/NBT, transform, entity, and stall validation remains required;
  project compilation was intentionally not run by request.

2026-08-29 03:15 — Incrementally commit Enhanced reorder queues

- Allowed accelerated pastes to retain Enhanced 6.3.0's normal `MultiStageReorder`
  buffering and drive the `EditSession.commit()` operation graph across server ticks.
- Added runtime-gated, deadline-aware resume hooks for Enhanced's unbounded
  `BlockMapEntryPlacer` and stage-three dependency committer. Stage one/two yield after
  individual placements; stage three yields only between complete attachment chains.
- Kept ordinary `flushQueue()` synchronous: without an Overdrive thread-local deadline
  the transformed operations finish in one resume call with their original ordering.
- Entities and session history finalization now wait for the retained commit operation;
  reorder-enabled accelerated pastes make no final synchronous flush when supported.
- Reorder plus fast mode remains on Enhanced's synchronous path because its whole-set
  dirty-chunk finalizer has no resumable unit in 6.3.0.
- Added reorder commit support, slice, resume duration, operation class/remaining, and
  final synchronous flush diagnostics. Runtime validation remains required; compilation
  was intentionally not run by request.
