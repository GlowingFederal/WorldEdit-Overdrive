# WorldEdit Enhanced 6.3.0 integration and ownership

WorldEdit Overdrive is a Forge 1.7.10 addon for WorldEdit Enhanced 6.3.0.
Standard supported clipboard pastes use incremental server-thread capture,
immutable worker planning, and server-thread submission and reorder commit.
Enhanced remains authoritative for ordering, history and block semantics. Scoped
mutation hooks retain native chunk/lifecycle/physics behavior while indexing
client update duplicates and avoiding proven unchanged optical checks.

## Current command support

The 1.0.0 public command path accelerates supported standard `//paste` operations
and paces undo/redo of their streamed history. Requests involving only ordinary
WorldEdit history retain native behavior. Standard paste preserves masks,
transforms, ignore-air, metadata, tile data, entities and placement dependencies;
fast-mode paste remains native. Schematic loading happens before paste interception
and is not accelerated.

`//set`, `//replace`, `//walls`, `//faces`, `//outline`, `//center`,
`//overlay`, `//naturalize`, `//stack` and `//move` have integration hooks
but no installed incremental owners. Their bridges return the not-handled outcome
before mutation, so Enhanced performs the operation normally. Hook installation
alone does not establish acceleration: use the `operationSupport` status line,
which labels these paths `HOOKED` when their hooks are present. Other commands
remain native as well. The historical Stage 4 fill implementation and older
Stage 5D support claims do not describe current public command behavior.

The [README](../README.md) contains installation, player commands and settings.

## Active build boundary

The root project is the sole project included by `settings.gradle`. Its main
Java source set explicitly includes only
`com/glowingfederal/worldeditoverdrive/**`. The active class is the small
`WorldEditOverdrive` Forge entry point, which declares a required dependency on
the `worldedit` mod and logs the detected WorldEdit API version during Forge
initialization.

The legacy `core`, `bukkit`, `forge1710`, `favs`, and `ReferenceSRC` trees stay
in the repository only as future porting reference. In particular, none of the
old `forge1710/src/main/java/com/boydti/fawe/forge` queue, chunk, command,
player, metrics, or bootstrap implementations belongs to an active source set.

## Dependencies and packaging

The build uses the pinned WorldEdit Enhanced artifact
`curse.maven:worldedit-legacy-enhanced-1135144:5879351`
(`worldedit-mc1.7.10-6.3.0.jar`) as an external compile-time dependency. It is
not copied into the Overdrive JAR. Enhanced requires FalsePatternLib at runtime;
server installations must provide it alongside Enhanced and Overdrive.

The installable artifact is `build/libs/WorldEditOverdrive-1.0.0.jar`. It should
contain only the owned `com/glowingfederal/worldeditoverdrive` class tree and
`mcmod.info`; it must not contain `com/boydti/fawe`, project-owned
`com/sk89q/worldedit`, or dependency implementation classes.

## LaunchWrapper-safe frame computation

The `EditSession` transformer recomputes frames for the complete class, including
untouched methods. Frame hierarchy queries are answered from `.class` resource
headers exposed by LaunchWrapper rather than by loading classes. This avoids both
class initialization and a recursive transformation request while retaining the
actual WorldEdit inheritance graph; resolved headers are cached.

This matters in Enhanced 6.3.0's `fillXZ`: local 9 receives either
`RecursiveVisitor` or `DownwardVisitor`, and `DownwardVisitor` extends
`RecursiveVisitor`. The branch merge must therefore retain `RecursiveVisitor`,
which is the receiver required by the subsequent `visit` and `getAffected`
calls. Widening that local to `Object` produces invalid bytecode even though
Overdrive does not modify `fillXZ` itself.

Hierarchy metadata failure is fail-open. If every type needed for safe frame
emission cannot be read, Overdrive discards the attempted `EditSession` rewrite,
returns the original Enhanced bytes, and reports all associated command hooks as
unavailable.

## Original addon baseline

The original baseline intentionally did not port Forge chunk writers, FAWE queues,
history, asynchronous editing, or WorldEdit overrides. Those features require
separate compatibility work against Enhanced after the clean addon can build
and start successfully.

## Bounded paste preparation and execution

The supported Enhanced 6.3.0 `ForwardExtentCopy` graph enters an owned continuation
at the `ClipboardCommands` completion boundary. Admission validates the graph,
fresh native history/queues, dimensions and arithmetic, then reserves a 16 KiB
descriptor. Estimated total source bytes are diagnostic only. The removed rule
compared the total capture/preparation estimate against 64 MiB before capture;
a source larger than that is now normal, rather than a resource rejection.

Each owner borrows the existing `BlockArrayClipboard`, allocates one 1,024-cell
capture page lazily, and incrementally reads transformed blocks, destination
coordinates and tile NBT. NBT retained-memory sizing resumes after at most 4,096
tag steps or the deadline. Worker planning filters the immutable page for
ignore-air. Server-thread submission inspects destination blocks and runs the
native `EditSession.setBlock()` chain. Masks, limits, history recording, block bags,
validation, quirks, lighting and world updates remain at their native boundaries.

The owner installs `PasteStreamingExtent` at the actual native reorder position,
and `PasteDiskHistory` at the actual native history position. These replace the
whole-operation heap queues/history for this supported path. Bounded records are
serialized by one worker per owner into disk journals; records and source cells
are released when downstream ownership has transferred, and the page is released
after its queued writes finish. File I/O and decoding never run in the owned
server-thread phases. Temporary global pressure yields and resumes; it never
replays an admitted supported paste through synchronous WorldEdit.

Submission preserves source order. Stage one and two journals preserve native
queue order. Stage three uses a disk treap keyed by signed XYZ, last-write-wins
records and a disk linked dependency walk with cycle flags. Attachments, rail
support and paired door halves use Enhanced's classification rules. Long walks
can advance across batches/ticks without an in-memory chain proportional to the
paste. The shared placement cursor keeps adjacent door halves together even
across a page boundary; ordinary dependency placements can yield at a valid
support prefix. Ordering between independent native hash-map roots was never a
stable ordering contract.

All source submission finishes before global stages one, two and three are
placed. Thus `SUBMITTING` with zero committed blocks can be normal preparation;
`pastePlacementStage=WAITING_FOR_PREPARATION` makes that explicit. Starting stage
one early would change destination inspection and history for repeated or
transformed destinations. Entities follow block/downstream completion, then
history sealing, selection, native session remembering and success feedback.
Native immediate-placement exceptions (removing an existing place-last block),
and a disabled reorder queue, still place during submission as Enhanced does.

The remaining downstream commit is obtained through `EditSession.commit()` and
resumed under the same deadline until null. There is no owned call to native
`flushQueue()`/`completeBlindly()`. The original empty reorder extent is restored
before the completed session is retained, so history does not retain the working
spool and dependency index. Ordinary unsupported operations keep native behavior.
Fast-mode pastes remain unsupported: Enhanced's accumulated dirty-chunk finalizer
cannot be bounded with the available API.

## Live memory and backpressure

`operationMemory=67108864` reports the internal limit on conservative live
reservations for one paste or replay. `globalMemory=134217728` reports the internal
limit on their combined working memory, including
retained streamed-history descriptors. These are not limits on clipboard file
size, total source bytes, or total bytes processed. Reservations cover fixed state,
capture arrays/payloads, entities, planning arrays, completed plans, queued commit
and history records, and worker buffers/decoded records. Every ordinary allocation
must fit both limits; tickets transfer category ownership and return space when
released. One bounded page/job per owner prevents unlimited worker accumulation.

Admission alone has an explicit, reported 16 KiB-per-waiting-owner tolerance even
when the global pool is full. Such an owner waits for its working state rather
than falling back. Fixed storage state is conservatively reserved at 256 KiB.
Capture pages reserve 64 KiB plus separately charged payloads; planning reserves
16 KiB. Decode batches cap at 1,024 records and roughly 1 MiB of estimated record
payload, with reservation before decode. A single indivisible payload and the
copies/state needed to process it must fit the operation budget; otherwise the
operation fails explicitly rather than waiting forever. Disk exhaustion and I/O
failure are also genuine failures, not synchronous fallbacks.

The estimates are conservative accounting, not a JVM heap measurement. Existing
WorldEdit clipboard storage, Minecraft world/chunk storage, and file-system cache
are outside this pool. One native block/entity/NBT API call, allocation/GC pause,
chunk load, lighting or mod callback cannot be preempted by a cooperative deadline.
Disk data grows with the total operation and can exhaust temporary-disk space.

Previously whole-operation work removed from the owned path includes five capture
arrays, a complete resident snapshot/page directory, auxiliary-map copying, final
air rescanning, NBT string rendering, whole-list entity region filtering, all
planning jobs/results and final chunk collation, native stage queues and stage-three
hash/deque dependency state, and native in-memory history tuples. Compatibility
snapshot constructors and ordinary native operations still exist outside this path.
Source schematic loading remains WorldEdit-owned and occurs before interception.

## Streamed history, undo and redo

Native `LocalSession` still owns its history list and pointer. Block before/after
states, metadata/NBT and entity state/identity are journaled on disk. Only the two
history journals and a charged 32 KiB descriptor/buffer reservation remain after
completion; the remaining spool is deleted. Java 8 finalization provides a cleanup
fallback when native history drops the ChangeSet; explicit close is also available.
Entity identity uses native ID plus UUID and a weak world reference, preserving
removal/redo without retaining a heap wrapper per entity or an unloaded world.

Enhanced `HistoryCommands` undo/redo and direct `EditSession.undo/redo` calls on
streamed history are intercepted before their native synchronous completion loops.
A replay waits for preceding work in that session, reads bounded forward/reverse
history batches on workers, and submits restoration through the same disk reorder
and placement cursor as paste. Undo traverses blocks in reverse; redo traverses
forward. Entities retain native entities-before-blocks replay order and a maximum
of 16 entity calls per slice. Native IDs from redo are persisted on the worker,
including already applied entities during cancellation/error cleanup. A per-history
owner gate serializes simultaneous direct API replays.

Replay uses normal lighting mode so an undo cannot accumulate a whole fast-mode
finalizer. The history pointer advances only after an entry completes. Several
requested entries run sequentially; a mixed request can replay native
`BlockOptimizedHistory` lazily, while unknown custom changes fail explicitly.
Requests involving only ordinary native history remain native. Raw ChangeSet
iterators remain lazy synchronous compatibility APIs; commands and EditSession
replay APIs are the paced ownership boundaries.

Shutdown and server-world unload cancel owners, restore their native extents and
release working reservations/files after any active worker finishes. Cleanup is
idempotent. Failed history persistence is reported as an unreadable history entry
rather than leaving future replay waiting forever. This is temporary session
history, not durable history recovery across server restarts.

## Pacing and runtime diagnostics

All owners share a hard adaptive server-tick deadline. Each paste separately owns
capture, submission, reorder and entity controllers. Undo and redo use the same
controller and placement cursor. Each phase starts at 5 ms; ordinary capture,
submission and placement slices can grow to 20 ms, dependency stage three and
downstream commit to 10 ms, and entity work to 5 ms. The normal recovery floor is
4 ms, but current headroom and stage limits can reduce the effective allowance
below that floor or to zero. A depleted tick never forces a placement.

Forge creates `config/worldeditoverdrive.cfg` with `pacing.serverBudgetMillis`
(default 30, supported range 1-40) and `pacing.safetyMarginMillis` (default 5,
range 1-25). The shared allowance is bounded by the configured maximum and by
`50 ms - max(current external tick time, EWMA external tick time) - reserve`.
The reserve is at least the configured margin, and increases with observed load
variation. Current external load cuts the allowance immediately; recovery is
smoothed. External time is measured from server tick START through END before
Overdrive work. The separate internal Stage 3 API is not part of this allowance.

These are the only config-file settings read by the addon in 1.0.0. They are
loaded during Forge pre-initialization; changing them requires a restart. The
64 MiB operation and 128 MiB global paste/replay budgets are internal constants,
not configuration keys. The paste worker pool automatically uses
`max(1, min(4, availableProcessors - 1))` threads. Worker count, phase slice
limits and chunk-load caps are not exposed in the config file. The separate
Stage 3 coordinator's memory/worker/budget diagnostics describe an internal
backend, not additional public paste settings.

The former inner controller could not see headroom. It halved its target whenever
a slice exceeded its allowance by 10 percent (at least 0.1 ms), jumped directly
to 1 ms after a twofold overshoot, and counted decreases even at the floor. A
normal 1.25-1.5 ms final mutation then failed every 1 ms slice. Recovery required
four consecutive safe, well-used samples and only added 10 percent. The owner
received one visit per tick, so the outer 30 ms allowance went largely unused.
`resumeAllowanceUsedPercent` remains the lifetime sum of physical resume elapsed
time divided by the lifetime sum of those resumes' actual deadline allowances;
it is neither a recent average nor an individual mutation measurement.

Soft-slice overshoot by the final indivisible mutation now counts as ordinary
completion. Targets grow by 25 percent (at least 1 ms) after two useful stable
ticks. Adjustments happen once per active phase per tick, and decrease counters
only count actual target changes. Sustained loaded mutation cost above 4 ms
requires at least eight rolling observations and three expensive ticks before
25 percent backoff, with a six-tick cooldown. Chunk-load transitions use separate
samples, a four-tick cooldown, and temporary 25 percent backoff. Backoff also
reduces that phase's total tick allowance, so repeated same-tick resumes cannot
cancel it. Stable recovery removes a backoff level every two useful ticks.
Unexplained hard-deadline overshoot triggers separate scheduler backoff. A single
9.5 ms sample among normal mutations neither halves the target nor blocks recovery.
A downstream mutation above 50 ms takes fast backoff at the next phase tick,
with at least three backoff levels and an eight-tick cooldown, while retaining its
separate overrun attribution.

The scheduler runs at most 128 fair rounds through admitted paste and history
owners under one absolute deadline, rotating the first visit across both groups
so concurrent pastes cannot permanently starve another player's replay. Ready
capture, submission, commit and replay
continuations can run again in the same tick. Worker, session, memory and chunk-cap
waits leave the round; workers are never polled or awaited on the server. The cold
extent installation retains its separate startup slice. Page planning, serialization
and reads still use one bounded outstanding worker job per owner. Waiting for a
worker can still leave headroom unused; no queue or page prefetch was added.

Commit checks time at every dependency-safe placement boundary. Per-call timing
also supplies a rolling mean and p95 reserve at the hard boundary; percentile
sorting happens once per active phase/tick or on status reads, not per block.
The reserve is capped to a quarter of that phase's full tick allowance so one cold
sample cannot permanently prevent further observations. Door pairs reserve both
calls and finish together once begun. No additional unit deliberately starts
after the hard deadline, and a slice can stop early when its predicted next unit
will not fit. An unexpectedly expensive native call, atomic door pair, lighting,
mod callback or GC pause can still cross the deadline. Such downstream overruns
are counted separately from unexplained scheduler overhead. Deadlines do not
preempt an already-running native call.

Destination submission and streamed commit cap actual unloaded Forge chunks at two
per owner per tick, using
`IChunkProvider.chunkExists()` before a read. Crossing or revisiting already loaded
chunks consumes time allowance, not the chunk-load allowance. The prior transition
counter limited every raster boundary crossing and produced the observed roughly
540 source cells/second despite almost no active time or memory pressure.
`chunksSinceLastDrain` now counts reads that may load a destination chunk.

`/overdrive status` reports the selected controller phase, target, effective
allowance, target adjustment reason, limiting reason and worker/memory/chunk wait
reason. Recent resume/mutation mean and p95 use fixed rolling sample buffers;
tile and chunk latency are reported separately. Rates cover the last 64 recorded
work ticks and include elapsed gaps. Capture/submission rates are separate from
physical commit placement/change rates; per-resume unit counts refer to the
displayed controller phase. Headroom and reserve describe the latest shared tick;
hard-deadline remaining time is captured after the latest phase visit. Resume and
placement counts describe the latest shared tick; same-tick count accumulates
additional useful slices after the first slice in a shared tick. Adjustment age is
elapsed time since the last actual target change, with zero before any change.

Focused operator/console views complement the full `status` dump:

| Command | Progress shown |
| --- | --- |
| `/overdrive preparation` | Latest paste source capture, planned/submitted cells, pages, workers, memory, spill and recent rates |
| `/overdrive placement` | Latest paste stage 1/2/3 remaining, physical commits, tiles, entities, resumes and rates |
| `/overdrive pacing` | Most recently visited controller, limits, decision/wait reasons, recent latency/rates and overruns |
| `/overdrive paste` | Combined compact paste preparation and placement views |
| `/overdrive history` | Latest undo and latest redo, with record staging and physical placement shown separately |
| `/overdrive undo` or `/overdrive redo` | Latest operation in that direction, including entry count, stage queues, commits, entities and memory |

`paste`, `undo` and `redo` also accept `preparation`, `placement` or `pacing` as a
second argument, for example `/overdrive undo placement` or `/overdrive paste pacing`.
Tab completion includes the new views. These are read-only diagnostics; they never
start an edit or replay. Views describe the latest observed operation, not a selected
player or a sum across concurrent owners. Undo and redo retain separate bounded
primitive progress records after completion/cancellation, without retaining a world
or its history. Queued/staged history records do not mean the blocks are already
placed. For multi-entry replay, total records grow as each entry is selected.

The new controller/rate labels in a direction-specific pacing view keep the `paste`
prefix for compatibility; `pasteControllerPhase` identifies `UNDO_`/`REDO_` work.
Headroom and resumes/placements-this-tick counters are shared across all owners;
controller distributions and recent rates belong to the displayed operation.

### Deterministic controller comparison

The requested 350,000-mutation model uses 1.25-1.5 ms normal costs, a 5-10 ms
outlier every 257 mutations, 40 ms external headroom and a 30 ms shared allowance.
The baseline copies the previous working-tree controller and one-visit scheduling;
the updated model calls the production controller with repeated bounded slices.
These are simulated elapsed costs, not a Minecraft or disk/lighting benchmark.

| Metric | Previous | Updated |
| --- | ---: | ---: |
| Placements/sec | 20.00 | 413.96 |
| Average resume, ms | 1.399 | 14.468 |
| P95 resume, ms | 1.500 | 20.625 |
| Maximum resume, ms | 11.375 | 27.375 |
| Average placements/resume | 1.000 | 10.343 |
| P95 placements/resume | 1 | 15 |
| Resumes/tick | 1.000 | 2.001 |
| Placements/tick | 1.000 | 20.698 |
| Final target, ms | 1 | 20 |
| Increases / decreases | 0 / 349,984 | 7 / 0 |
| Resumes above 50 ms | 0 | 0 |
| Hard-deadline crossings | 0 | 229 |

Target progression at ticks 1 / 10 / 100 / 1,000 is 5 / 1 / 1 / 1 ms before,
and 5 / 12.207 / 20 / 20 ms afterward. Updated hard crossings are final
unpredicted downstream outliers; no new unit starts after the deadline. Constant
normal-cost runs have zero crossings. At these mutation costs the 30 ms allowance
permits roughly 400-480 placements/sec before additional overhead, so thousands
per second require cheaper actual mutations. Preparation uses the same controller
and is covered by production same-tick submission tests; this mutation model does
not supply a before/after preparation throughput measurement.

Capture `/overdrive status` at admission, periodically through preparation, after
placement starts and after completion, then during undo and redo. Compare:

- `pasteOperationId`, `activePhase`, `captureWorkStage`, `pastePreparationComplete`
  and `pastePlacementStage` to distinguish preparation from visible placement.
- Source visited/remaining/estimate, planned/submitted/committed mutations,
  prepared/committed tiles/entities, page allocation/residency/release and stage
  remaining counters to establish progress.
- Estimated source bytes versus current/peak live bytes and the category breakdown;
  global live/peak, backpressure yields/reason and spill bytes to verify bounded
  working memory rather than a source-size admission ceiling.
- Capture/submission/commit active versus wall time, worker planning versus I/O codec
  time, phase targets, actual chunk loads and maximum server slice/mutation time.
- `historyCommandHookInstalled`, `historySessionHookInstalled`, replay phase,
  processed/total records and current/peak replay memory during undo/redo.

Paste progress fields describe the owner that most recently published, identified
by `pasteOperationId`; global memory and worker activity are aggregate diagnostics.
Legacy native child-resume/flush fields can remain zero on the disk cursor path.
Source remaining can reach zero before source entities finish preparing. Hook
classes are resolved during server startup without initialization, so cold first
paste/history ownership is checked before admission.

Java 8 compilation, synthetic completion above the configured working budget,
metadata/transforms/ignore-air, disk history replay, long dependency walks, entity
identity serialization, memory pressure and cleanup are checked by the existing
unit suite. Those checks do not establish real Forge entity/lighting behavior,
cold LaunchWrapper boot, or successful completion of the 654 MiB schematic.
The supplied large-run logs establish admission and bounded progressing capture,
but stop during preparation; full paste, undo and redo still require server testing.

## Downstream world mutation audit

This audit uses the pinned Curse artifact `5879351`, its Enhanced reference sources,
and the cached Forge 1.7.10-10.13.4.1614 sources and class files. Modern FAWE and
the inactive raw fill writer are not the accelerated paste backend.

The physical commit path is:

```text
PasteCommitCursor.place(record.position(), record.block)
  -> PasteStreamingExtent.getExtent().setBlock
  -> BlockBagExtent -> DataValidatorExtent
  -> BEFORE_CHANGE event extent, if installed
  -> LastAccessExtentCache -> ChunkLoadingExtent
  -> BlockQuirkExtent -> SurvivalModeExtent -> FastModeExtent(enabled=false)
  -> ForgeWorld.setBlock(position, block, notifyAndLight=true)
  -> World.getChunkFromChunkCoords -> Chunk.func_150807_a
  -> optional Enhanced NBTConverter / TileEntityUtils / FMP compatibility
  -> World.func_147451_t -> updateLightByType(Sky and Block)
  -> World.markBlockForUpdate -> WorldManager -> PlayerManager / PlayerInstance
  -> World.notifyBlockChange -> six notifyBlockOfNeighborChange callbacks
  -> optional comparator propagation
```

The reverse traversal through delegates preserves custom event extents; they may
add work or override behavior. The exact stock `ForgeWorld`/`WorldServer`/`Chunk`
read optimization falls back for subclasses and invalid coordinates.

| Responsibility | Before this pass | Current handling |
| --- | --- | --- |
| Native chunk lookup | ForgeWorld lazy ID/meta reads use two world lookups, then the write looks up the chunk again | One scoped chunk lookup shared by lazy ID/meta and the following write for stock server classes; scope ends after that placement |
| Block state equality | Preparation filters identical ordinary states; reorder reads existing state; native chunk checks current ID/meta | Retained because masks, custom extents, intervening ticks and callbacks may change the live state |
| Storage and sections | Chunk creates ExtendedBlockStorage, writes ID and metadata, maintains section counts | Native chunk method retained; no raw array writes |
| Metadata | Forge writes metadata before `breakBlock`, then again afterward | Both retained: the first makes callbacks see valid world state; the second restores the requested metadata after callbacks |
| Precipitation/height maps | Precipitation cache invalidation; column relight or whole skylight map on first high section creation | Native behavior retained |
| Skylight | Height changes can call `markBlocksDirtyVertical`, update the column and four adjacent columns over a vertical range; opacity changes flag lazy gap checks | Retained and sampled separately from block-light checks |
| Block light | ForgeWorld explicitly calls the combined Sky/Block light check even after native chunk work | Only successful replacements between allowlisted opaque vanilla cubes skip this additional check when optical inputs do not change; air, transparent, emitting, NBT, modded and subclass paths retain it |
| Block callbacks | Old pre-destroy and break callbacks, then new on-added callback | Retained, including their world mutations and scheduling |
| Tile lifecycle | Forge `shouldRefresh`, tile removal/default creation, metadata update; Enhanced NBT/FMP override and description packet | Retained without reimplementing the FMP or modded tile boundary |
| Chunk dirty state | Chunk sets `isModified`; lighting and tiles can also dirty state | Retained; a cheap assignment, not a confirmed dominant cost |
| Neighbor physics | Six direct neighbor callbacks per ForgeWorld call, including unsuccessful/no-op writes; callbacks can cause further work | Retained, including comparator propagation. Reorder does not prove boundary-only notification sufficient |
| Scheduled updates | Created only when callbacks/block behavior request them | Retained; profiler counts scheduled method calls, including delegation, rather than claiming successful unique queue inserts |
| Client update marking | Marks each position, then Forge linearly searches the growing changed-position list | Scoped exact unsigned-short index eliminates the repeated scan; native list, section flags, packet selection, watcher filtering and changed tile packets remain authoritative |
| Render marking | Server world listeners receive light/range markers; actual client rendering follows client packet processing | Native behavior retained; client render cost and packet serialization outside the placement scope are not timed here |
| History | Submission reads previous state for equality/NBT sizing, then native ChangeSetExtent reads it again to capture disk history; commit is below history/masks/limits | No duplicate history during commit. Submission's extra read remains to preserve event/mask evaluation and live capture ordering; it is not a second history serialization |

The cursor now checks chunk existence once per attempted ordinary placement rather
than twice, and shares its one elapsed-time result between the maximum-mutation
diagnostic and pacing feedback. NBT-presence accounting is also shared. Deadline
checks still occur between placements. There is one position allocation per disk
record placement; mutating/reusing a position object would break extents that retain
that position. Expensive group strings, sorting and percentiles are sampled or
computed only when an operator reads diagnostics. No per-block log was added.

Enhanced's existing `shouldPlaceLast` removal writes AIR then the desired block
immediately during submission. Both writes can run lighting, lifecycle, neighbor
and client marking; ice removal has another native quirk write. These transitions
remain intact because their intermediate callbacks are meaningful. They are
profiled as individual mutations. Queue stage boundaries do not independently
repeat lighting repair, and no native history extent is traversed during disk commit.

### Flags, lighting and synchronization decisions

`World.setBlock` flag 1 controls neighbor/comparator notification, flag 2 client
marking, and flag 4 client-side render marking. None suppresses its immediate
`func_147451_t` call or the chunk method's internal lighting. Enhanced bypasses
`World.setBlock` and directly invokes `Chunk.func_150807_a`, then performs its own
light/client/neighbor work; changing world flags would not change this paste path.
Forge snapshot capture in `World.setBlock` was already bypassed by Enhanced and
has not been newly bypassed here.

The chunk's lazy `recheckGaps` work complements immediate column/height work. It
does not establish that immediate lighting can safely be omitted for arbitrary
edits. `ForgeWorld` inherits empty `fixAfterFastMode` and `fixLighting` methods
from AbstractWorld; simply enabling fast mode is not a complete repair strategy.
The inactive `ForgeChunkWriter.generateSkylightMap` finalizer is also insufficient
as a proof of cross-chunk block-light repair, so it is not reused for paste.

An opaque platform raised far above terrain can update many Y positions in the
changed and adjacent columns. Replacing an opaque block with another opaque block
usually leaves the height and light unchanged. This is a source-supported
explanation for differing costs, not a measured attribution of the reported
200–230 / 1,600 / >10,000 placements per second. Underground/transparent/emissive
edits must be measured separately. Native light propagation requires neighboring
chunks within 17 blocks and can refuse work when they are absent; this pass retains
that native loading/repair behavior rather than inventing an unbounded repair queue.

Client packets were already tick-batched. The newly eliminated work is Forge's
growing duplicate search before those packets. For N unique marks in one watcher
between sends, that search performs N(N-1)/2 comparisons. The lazy 8 KiB bit index
does O(N) total indexing and constant membership checks. It synchronizes native
marks appended between accelerated calls and resets before a chunk update send.
It does not truncate Forge's changed-tile list at 64 or bypass pending initial
chunk delivery. The index belongs to the native watcher and holds no world reference;
it is additional native synchronization memory, not a paste-sized retained set.

No lighting, physics, dirtying or network phase is deferred. The same server-thread
scheduler, budgets, streamed queues/history and lifecycle still own placement.
There is no new unbounded final repair, worker world access or success-before-repair
path. These decisions leave height/skylight propagation as a major candidate for
the remaining open-air cost; broad repair batching needs real runtime evidence and
correctness comparisons before replacing it.

### Profiling and real-world acceptance

`/overdrive profiling` shows the most recently mutated paste/replay profile. Its
native-hook status distinguishes unavailable hooks from zero observed work.
Class-shape failures return the original bytecode. Hooks match MCP/SRG names and
use Forge's deobfuscating remapper for production names/descriptors without moving
the existing core-plugin loading order. Dedicated-server code references no client
classes.

- `sample` uses a deterministic pseudorandom approximately 1/64 sample, avoiding
  a fixed spatial stride through raster records; `full` samples every placement;
  `off` disables detailed sampling. Basic attempts and optimization counters remain.
- `native` disables the downstream read/light/client-index optimizations;
  `optimized` restores them. Controls are operator-only, global until restart,
  and do not reset an existing operation's totals. Begin each comparison with a
  fresh equivalent paste and a fixed mode. Native baseline still has the profiler
  probes and the cursor's shared timing/existence-check improvements.
- Sample totals, mean, rolling p95, max, inclusive nested method timing, block IDs,
  transition/opacity/emission, tile flag, chunk, Y section and height-change context
  are reported. The 64-entry candidate table admits expensive new regions by
  replacing a cheaper candidate; its counts cover retention, not complete global
  type/chunk totals. Total method samples are not limited by that table.
- Timing categories overlap and must not be summed. Only aggregation overhead is
  labeled diagnostic aggregation; instrumentation overhead itself needs native
  `off` versus `sample`/`full` comparisons. Overrides that bypass instrumented native
  methods and later gap checks, server packet sends or client rendering are excluded.
  P95 covers the latest 512 sampled placements, not the full paste distribution.
- Use existing placement/pacing/status views for committed counts, recent rates,
  preparation versus commit active/wall time and shared tick/server headroom. A
  server profiler is still needed for total server-thread cost and late packet work.

The repeatable runtime workloads are A: a 128x128 stone platform at Y=200 above
open air; B: the same volume of stone replacing cobblestone in solid terrain;
C: alternating stone/cobblestone in already-lit, loaded underground chunks. Avoid
an identical source/destination in B/C: preparation filters those cells, so they
would not benchmark committed mutations. Keep destination chunks and their light
neighbors loaded, compare restored equivalent worlds, use the same clipboard and
pacing settings, warm up once, then capture native/optimized profiles and server
profiling for each workload. Repeat with detailed profiling off to quantify probes.

| Workload | Before/after placements/sec, mean/p95/max, light/neighbor/client/chunk time, server-thread time and wall time |
| --- | --- |
| A: open-air platform | Not measured in this source/build environment |
| B: terrain replacement | Not measured in this source/build environment |
| C: cheap loaded blocks | Not measured in this source/build environment |

These are real ForgeWorld workload specifications, not raw-array or mock-extent
benchmarks. The local `run` directories have no configured runtime mods or saved
world. Compilation, reobfuscation and unit checks do not provide these measurements.

Automated checks exercise the actual pinned Enhanced/Forge class files with ASM
verification, the complete 65,536-coordinate index, native/accelerated interleaving
and reset, profiling-off counters and exception scope cleanup. Existing streaming
tests cover metadata/NBT, transforms, history undo/redo and cross-page door ordering
through their extent fixtures; they do not validate Minecraft lighting or physics.

Runtime acceptance still requires comparing native and optimized final block/meta,
tile NBT and sky/block light for stone into air, high platforms, opaque replacement,
holes and opened/closed shafts, transparent transitions, emitting blocks and
cross-chunk/section boundaries. Compare torches, doors, rails, falling blocks,
redstone neighbors and modded tiles during and after placement; then save/reload,
relog, undo and redo on integrated and dedicated servers. No claim of that runtime
matrix or a measured throughput improvement is made by the source/build checks.
