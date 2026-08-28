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
