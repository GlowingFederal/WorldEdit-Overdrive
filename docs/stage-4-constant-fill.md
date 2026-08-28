# Stage 4: Enhanced 6.3.0 constant-region fills

## Enhanced path traced

The supplied Enhanced sources establish this path (paths are relative to `ReferenceSRC/WorldeditEhancedCoreSRC` unless noted):

1. `extension/platform/CommandManager` explicitly registers `/set` as `SelectionCommand(new ApplyCommand(new ReplaceParser(), ...))`; `RegionCommands#set` is not involved.
2. `ApplyCommand` and `Apply` produce a `RegionVisitor(selection, BlockReplace(editSession, pattern))`. `SelectionCommand#call` creates the selected region and owned `EditSession`, creates that operation, calls `Operations.completeBlindly`, collects `operation.addStatusMessages`, and directly prints `Operation completed (` plus the joined messages plus `).` (or the no-status variant). This is the exact source of the observed text.
3. Consequently this command never calls either `EditSession#setBlocks` overload. The old hook was installed in a valid API method which is outside the composed-command path.
4. The `EditSession` constructor builds, from world inward, `FastModeExtent`, `SurvivalModeExtent`, `BlockQuirkExtent`, `ChunkLoadingExtent`, `LastAccessExtentCache`, the `BEFORE_CHANGE` event wrapper, `DataValidatorExtent`, `BlockBagExtent`, `MultiStageReorder`, the `BEFORE_REORDER` wrapper, `ChangeSetExtent`, `MaskingExtent`, `BlockChangeLimiter`, and the `BEFORE_HISTORY` wrapper. Normal `setBlock` enters at `BEFORE_HISTORY`.
5. `ChangeSetExtent#setBlock` reads the previous block and appends WorldEdit's `history/change/BlockChange`; `BlockChangeLimiter` enforces the session limit; `MaskingExtent` applies the session mask. Reordering may defer placement until `EditSession#flushQueue`/`Operations.complete`.
6. The Forge leaf is `ReferenceSRC/WorldeditEnhancedSRC/main/java/com/sk89q/worldedit/forge/ForgeWorld.java#setBlock(Vector, BaseBlock, boolean)`. It uses `Chunk#func_150807_a`, installs JNBT tile data through `NBTConverter`/`TileEntityUtils`, and performs lighting, update, neighbor, and comparator behavior when requested.
7. Command infrastructure owns the `EditSession` lifecycle and remembers its change set for undo after command completion. This is why an edit cannot be silently queued after the operation returns without also redesigning session completion.

The runtime artifact's narrow interception point is the sole static `Operations.completeBlindly(Operation): void` invocation in `SelectionCommand#call(CommandArgs, CommandLocals): Operation`. Enhanced 6.3.0 supplies the operation with an `ALOAD` from its operation local immediately before that call. The transformer consumes the already-stacked argument and therefore does not depend on how it was constructed. In particular, the installed Enhanced class has no `Contextual#createFromContext` invocation even though the supplied source does: that construction detail was changed or compiled through a different helper in the distributed artifact.

The bridge accepts only the exact `RegionVisitor -> BlockReplace -> EditSession` shape and obtains the visitor's region and the replacement function's extent from the operation itself. Success records the affected count on the visitor and jumps only over traversal to the existing status-message code; `null` restores the original operation stack and invokes `completeBlindly` unchanged, before any Overdrive mutation. The legacy `EditSession#setBlocks` hook remains separately diagnosed but no longer determines ACTIVE status.

## Mechanism and fallback

Forge 1.7.10 does not provide a bundled, dependable Mixin service. Stage 4 uses a launch-time, method-descriptor-and-anchor-checked `IClassTransformer` as the 1.7-compatible equivalent of a one-method redirect. It duplicates the completion argument and inserts a nullable call to `Stage4SetBridge.trySetOperation`; `null` restores the original stack and continues into the untouched traversal path, while success discards the saved argument, skips traversal, and rejoins existing feedback. Frames and maximums are recomputed from the modified method rather than copied from a reference shape. A missing, ambiguous, non-static, interface, or descriptor-mismatched completion anchor now logs that acceleration is unavailable and returns the original class bytes. WorldEdit continues loading, and ACTIVE means only that this command boundary was successfully emitted; the legacy diagnostic hook cannot make it ACTIVE. No WorldEdit-owned class is packaged.

The loading plugin, transformer registration, target encounter, descriptor match,
hook installation, bridge calls, accelerated calls, fallbacks, and last fallback
reason now have a small shared diagnostic state. Startup reports the `worldedit`
`ModContainer` version rather than treating `WorldEdit.getVersion()` as a
compatibility signal. Enhanced obtains that API string from the package
`Implementation-Version`; it is `(unknown)` when its jar did not publish that
package attribute, even though Forge has valid `mcmod.info` metadata.

The coremod exclusion is intentionally limited to the loading plugin,
transformer, and shared status class. Excluding the former whole `integration`
package caused `Stage4SetBridge` to be parent-loaded, outside LaunchClassLoader,
so it could not safely resolve runtime WorldEdit/Minecraft types. This bootstrap
class-loader boundary was the primary installation defect.

Eligibility is decided before planning or mutation. It requires the exact Enhanced `EditSession`, exact `CuboidRegion`, legacy `SingleBlockPattern`, a null session mask, disabled reorder queue, Forge `WorldServer`, server side, ID 0..4095, metadata 0..15, a safely computed volume fitting Enhanced's integer result model, and volume within `getBlockChangeLimit()`. Unsupported/custom regions, patterns, masks, sessions, worlds, transforms represented by another pattern/session type, clients, overflow, and unavailable tile conversion return to Enhanced unchanged. The exact session and cuboid checks remain conservative because subclasses can change the extent boundary or bounds/traversal contract. The constant resolver uses `instanceof SingleBlockPattern`, safely allowing implementation subclasses while accepting no arbitrary `Pattern` wrapper. Each intercepted call reports only its first failed eligibility condition, and the first call also reports actual session, region, pattern, World, queue, and mask values.

The command remains synchronous. Planning and commit complete before the original command receives its affected count, so normal feedback and session remembering occur in the expected order and there is no server-thread wait on future ticks. This deliberately does not use Stage 3's tick budget: it preserves Enhanced semantics but can still stall for very large edits. Cooperative asynchronous command/session finalization is deferred.

## Translation, partitioning, and commit

Only `SingleBlockPattern#getBlock` is accepted. ID, metadata, explicit air, extended IDs, and constant JNBT are retained. JNBT-to-native conversion calls Enhanced's own Forge converter reflectively because that class is package-private; failure is an all-or-nothing fallback before mutation. Tile NBT is defensively copied once per destination by `PreparedChunkChange.Builder`.

`ConstantFillPlanner` calculates inclusive chunk bounds using shifts, intersects each chunk mathematically, then emits section-local rectangular ranges. It creates no coordinate collections and performs no world read. Complete 16x16x16 coverage calls `SectionChange.fill`, allocating and filling the final dense array directly; only boundary spans use primitive xyz loops. Tile-bearing constants necessarily add per-position NBT records and are intended for small correctness-oriented fills.

On the server thread, history is captured immediately before each chunk commit. Each previous `BaseBlock` comes from Enhanced's Forge world and therefore carries old tile JNBT; WorldEdit `BlockChange` records are appended to the operation's existing `ChangeSet`. Undo consequently restores both previous block state and old tile data through Enhanced. This simplest correct history format has WorldEdit's existing object-per-change cost; compressed/disk-backed operation history is deferred to Stage 5.

`ForgeChunkWriter` remains the placement authority below the bridge. `SideEffectPolicy.RAW` is only a request: Stage 2's conservative classifier keeps tile, modded, unknown, ticking, and otherwise unsafe states on native placement. Stage 2 retains live old-state reads and `Chunk.generateSkylightMap()` on the server thread. The accelerated path calls Stage 3's watcher-aware `ChunkSynchronizer` exactly once per committed chunk; fallback never calls it and remains Enhanced-owned.

## Modern and legacy FAWE comparison

Modern FAWE reference points inspected include `IBatchProcessorHolder`/batch processors, `HistoryExtent`, `LimitExtent`, chunk filter blocks, queue chunk implementations producing `IChunkSet`, and region traversal paths that prefer chunk/flat processing. Overdrive adopts mathematical chunk partitioning, final chunk-set-like buffers, dense section writes, separate preparation/application, conservative processors/classification, and history at the application boundary. New block-state registries, `IChunkSet`, modern side-effect sets, parallel filter forks, and modern lighting APIs are not portable to 1.7.10.

Legacy FAWE's replacement `EditSession`, broad `com.boydti.fawe` queue extent stack, classpath override model, wholesale visitors, and compressed-history ports are rejected. Enhanced remains authoritative and an ineligible call resumes the untouched method, rather than partially editing and replaying.

| Concern | Enhanced 6.3 | Legacy FAWE | Modern FAWE | Stage 4 Overdrive |
|---|---|---|---|---|
| Fill traversal | `RegionVisitor`, point objects | replacement session/queue | chunk filters | cuboid math + section spans |
| Integration | extent stack | broad overrides | queue/batch extents | one descriptor-checked redirect |
| Limits | per-change limiter | FAWE limit layer | `LimitExtent` | preflight same session limit |
| History | `BlockChange` objects | compressed variants | history processor/extents | Enhanced changes captured at commit |
| Completion | synchronous | queue-dependent | explicit queue flush | synchronous, no hidden work |
| Fallback | native path | generally replaced | processor-dependent | nullable pre-mutation return |

## Validation and measured status

Static inspection can verify package ownership, transformer target/descriptors, world-read boundaries, dense fill construction, and absence of `//replace`/general-pattern handling. A dedicated 1.7.10 server/client fixture is not present in this checkout, so the requested runtime world matrix, save/reload, visual synchronization, injected preparation/commit failures, shutdown, undo exercise, and plain-Enhanced versus Overdrive timings have **not** been claimed. Lighting is now instrumented separately inside total commit wall time.

For a diagnostic server run, the startup sequence must contain the core-plugin
initialization line, the `EditSession#setBlocks(Region, Pattern)` installation
line, and an `ACTIVE` summary. Run vanilla cuboid `//set stone`, `//set air`, and
one legacy metadata value. Every call must increment the bridge counter and
either print `Overdrive //set:` or one exact fallback reason; the type
line establishes whether command binding supplied the expected objects and
whether reorder was enabled. An `INACTIVE` summary is a hard integration failure,
not a ready state.

The benchmark matrix to run on the fixture is: aligned stone and air cuboids, unaligned boundary-heavy cuboids, raw-ineligible modded state, and small vanilla/modded tile fills; record blocks, chunks, elapsed/tick stalls, dense/sparse sections, raw/native applications, packets, and peak prepared bytes for Enhanced and Overdrive from identical snapshots. Stage 3 statistics do not currently aggregate synchronous bridge commits, another explicit blocker before performance claims.

## Blockers before Stage 5

* Run the correctness matrix and paired benchmark on a real Forge 1.7.10 Enhanced 6.3.0 server/client installation.
* Add an explicit compatibility handshake for plugins installing custom `EditSessionEvent` extents.
* Move synchronous integration onto a command-aware asynchronous completion/remembering contract before enabling extremely large fills.
* Feed synchronous bridge metrics into a common Stage 3 statistics record and separately profile skylight generation.
* Replace object-per-block Enhanced history with bounded compressed/disk-backed history only after equivalent undo/redo and tile tests exist.
* Add cancellation linkage at the command/session boundary; synchronous Stage 4 has no Enhanced cancellation seam, although Stage 3 operations remain internally cancellable.
* General replacement matching, masks, nonconstant patterns, clipboard work, and arbitrary iterable regions remain out of scope.

## Stage 4.5 hardening and measurement

The completion message was not on an alternate execution path: the bridge's only
successful return is after the operation-level `FMLLog.info` call. That logger is
routed to the normal Forge/FML server log (commonly `ForgeModLoader-server-0.log`),
and an INFO console filter may omit it. A run showing an accelerated counter but
no old `Overdrive //set completed` text may also be using a jar predating that
wording. The permanent message now begins `Overdrive //set:` and is emitted once,
after synchronization, for every successful accelerated call. It reports actual
changed positions, changed chunks, dense/sparse sections, raw/native placements,
planning, Enhanced history, total commit, the `generateSkylightMap` portion,
synchronization, total wall time, and peak simultaneously retained prepared bytes.

Planning now reads the live world on the captured server thread and omits positions
whose ID and metadata already match. If the destination carries explicit NBT, the
live tile's full serialized NBT (with destination coordinates normalized) must
also match. Omitted positions create no history, placement, dirty column, tile
lifecycle, packet coordinate, or affected count. Dense representation is retained
when at least 512 positions differ, including a wholly changed 16x16x16 section.

Enhanced's `BlockChangeLimiter` counts **attempted calls before delegation**, not
successful changes: unchanged and rejected calls consume the limit, and repeated
calls consume it repeatedly. The exact-`EditSession` bridge therefore reserves the
entire selected volume against the limiter's existing count before mutation while
returning only actual changes. If the known Enhanced limiter handshake cannot be
resolved, acceleration falls back before mutation. Unlike Enhanced's sequential
visitor, this preflight is atomic: an over-limit fill makes no partial edit.

History remains Enhanced `BlockChange` history and command/session ownership is
unchanged, so normal Enhanced undo and redo consume the same retained change set.
No Overdrive redo mechanism exists. Commit timing includes raw/native placement,
section counters, tile lifecycle, chunk dirtying and finalization; the nested
lighting/finalization figure measures `Chunk.generateSkylightMap()` only. History
and synchronization are measured separately. No per-block logging or timing was
introduced.

The synchronous bridge does **not** submit to `OverdriveCoordinator`,
`ChunkPreparationTask`, its worker pool, byte backpressure, tick budgets, or fair
commit queue. It plans and commits serially on the command/server thread and calls
only the Stage 2 writer plus Stage 3's watcher-aware `ChunkSynchronizer`. Therefore
this source path supplies no preparation-worker parallelism; a process-wide CPU
burst cannot be attributed to Stage 3 without an external profiler and may be JVM
allocation/GC, lighting, networking, or other server activity. Command completion
still waits for the full reported wall time.

### Runtime validation record

This checkout has no runnable Forge 1.7.10 server, Enhanced 6.3.0 server/client
fixture, installed high-ID mod, or baseline world snapshots. Consequently it would
be misleading to invent paired timings or claim client, restart, undo, redo, tile,
or lighting observations. The following remain runtime-required:

* changed-count cases (all same, half same, all different, air/air, metadata, and
  explicit tile NBT), plus undo/redo from equivalent snapshots;
* aligned sections/chunks, awkward X/Z/Y crossings, negative coordinates, Y=0 and
  Y=255, with block-by-block comparison to plain Enhanced;
* a real ID above 255 through high-to-low, high-to-air, metadata, undo and restart,
  including inspection that the section MSB nibble is zeroed;
* raw stone and conservative-native ticking/modded/tile states; chest, furnace,
  tile transitions and custom NBT; watcher-only sparse/dense/tile packets;
* opaque placement/removal, absent/cleared sections, large air, contextual opacity,
  save/reload, and paired A-F benchmark timings requested for Enhanced and Overdrive.

Until that matrix is recorded, the classification is **NOT VALIDATED**. This is a
statement about unavailable runtime evidence, not a claim that activation failed.
No Stage 5 implementation should begin from static results alone. The single
provisional Stage 5 recommendation is **coordinated asynchronous/tick-budgeted
WorldEdit execution**, because this confirmed synchronous call path necessarily
stalls the server thread for its full wall time; runtime phase measurements must
confirm or revise that choice before implementation.
