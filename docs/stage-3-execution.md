# Stage 3: bounded operation execution

Stage 3 wraps the Stage 2 writer in an explicit operation/coordinator boundary. It
does not connect WorldEdit commands, sessions, patterns, masks, clipboards, or
history. `ChunkPreparationTask` is the sole future-facing input seam: its contract
permits only immutable data or invocation-owned state and returns one owned
`PreparedChunkChange`.

## Lifecycle and scheduling

An operation moves deterministically from `CREATED` to `PREPARING`, `READY`, and
`COMMITTING`. `finishSubmissions` closes its producer side. It reaches `COMPLETED`
only after every submitted task has finished, its ready deque is empty, no commit
is active, and required synchronization is finished. `CANCELLED` and `FAILED` are
terminal; the first failure is retained. Cancellation is not rollback and leaves
already committed chunks intact.

The fixed executor has a bounded submission queue and named daemon threads.
Submitting to a full queue is explicitly rejected, allowing a Stage 4 producer to
retry without hiding an unbounded queue. After preparation, a worker waits on a
condition protected by the coordinator accounting lock; commits never need that
worker or lock while mutating the world. Commit/cancel/failure/shutdown releases
bytes and notifies waiters. A buffer larger than either limit is admitted only
when both global and operation accounting are empty. Thus at most one isolated
oversize buffer is resident, avoiding permanent head-of-line deadlock.

Ready buffers are FIFO within an operation. Across operations, the coordinator
chooses one whole chunk from each ready operation in deterministic round-robin
order. A Forge `ServerTickEvent` at `END` captures/asserts the actual thread and
drains chunks until the fixed nanosecond deadline. There is no sleeping, custom
loop, block-level interleaving, or TPS controller. Chunk loading occurs through
the normal `WorldServer#getChunkFromChunkCoords` path inside the Stage 2 writer,
on that captured thread. Stage 3 never force-unloads a chunk; reliable
loaded-versus-new provenance is intentionally not claimed for 1.7.10.

## Synchronization and lighting

The coordinator consumes `ChunkCommitResult` directly. Up to the configured
sparse threshold it constructs the verified 1.7.10
`S22PacketMultiBlockChange(int, short[], Chunk)` coordinate form. Dense edits and
all biome edits use one `S21PacketChunkData`; biome edits request a full chunk so
the biome array is included. Packets go only to `EntityPlayerMP` instances for
which `PlayerManager.isPlayerWatchingChunk` is true. Changed supplied tiles send
their live `TileEntity#getDescriptionPacket` to those same watchers. Multipart
and other mod tiles therefore retain their own Forge description-packet behavior;
Overdrive does not reproduce Enhanced's compatibility layer. Default tiles
created without supplied NBT are a documented Stage 4 refinement for more exact
packet enumeration.

Stage 2's contextual `Chunk.generateSkylightMap()` remains authoritative. Light
masks guide packet selection only; modern deferred relighting is not safe to
transplant without a demonstrated Forge 1.7.10 solution for modded contextual
opacity.

## Statistics, configuration, and diagnostics

Immutable snapshots expose operation state and failure; submitted/prepared/
committed chunks and blocks; sparse/dense sections; raw/native applications;
preparation/commit/elapsed nanoseconds; current/peak buffered bytes; and sparse,
chunk, and tile packet counts. Coordinator snapshots expose active operations,
ready chunks, current/global-limit bytes, active workers, and the prior tick's
commit count/time. Configuration is deliberately limited to worker and submission
counts, global/per-operation bytes, commit milliseconds, and sparse packet
threshold. `submitSynthetic` is a direct internal smoke path, not a command.

Shutdown first rejects submissions, terminally cancels operations, discards and
releases ready buffers, wakes memory waiters, interrupts workers, waits briefly,
and then the mod lifecycle clears thread ownership. Workers are daemon threads as
an additional JVM-liveness safeguard. Prepared arrays are not pooled.

## Concrete reference review

Modern files reviewed below are under
`ReferenceSRC/ModernFAWECoreSRC/worldedit-core/src/main/java/com/fastasyncworldedit/core/`:

* `queue/IChunkGet.java`, `IChunkSet.java`, `IChunk.java`, and
  `IQueueExtent.java`: existing/read state and desired writes are distinct, masks
  carry finalization intent, and application owns a chunk-sized unit. Overdrive
  adopts the ownership unit and result metadata, translated to legacy numeric
  states; it does not take asynchronous live reads.
* `queue/implementation/chunk/ChunkHolder.java`: get/set ownership, pre-process,
  native call, finalize, and post-process have explicit ordering. Overdrive uses
  prepare, Stage 2 commit, then synchronization boundaries, without copying the
  modern platform API or processor graph.
* `queue/implementation/ApplyTask.java`: the application task owns completion and
  propagates exceptional completion. Overdrive catches only executor/commit
  boundaries, retains the first cause, and never turns a failed application into
  completion.
* `queue/implementation/SingleThreadQueueExtent.java`: FIFO chunk batching,
  draining, processor ordering, and chunk lifecycle support a chunk atomic unit.
  Overdrive adds operation-level round robin and a tick deadline rather than its
  modern queue/future machinery.
* `queue/IBatchProcessor.java` and processor/finalizer implementations establish
  useful before/after-application seams. Stage 3's prepare/commit/synchronize
  phases reserve compatible future hook locations, but arbitrary processors are
  deferred until Stage 4 has concrete immutable inputs.
* `queue/implementation/blocks/CharSetBlocks.java` and `BitSetBlocks.java`
  demonstrate masks and sparse iteration. Stage 2's 1.7.10 ID/data representation
  remains unchanged.
* `queue/Pool.java` and `FaweCache.java` show reset/reuse lifetimes. Pooling is
  rejected here because it complicates operation ownership, retains giant arrays,
  and makes byte release less truthful without profiling evidence.

Legacy packet signatures, watcher targeting, and synchronous provider behavior
were checked in
`ReferenceSRC/FAWE1710SRC/main/java/com/boydti/fawe/forge/v1710/ForgeQueue_All.java`.
Its broad `Throwable` swallowing, mutable global/cache hierarchy, temporary empty
section substitution, mixed mutation/sending, and implicit queue completion are
rejected. Enhanced remains external and authoritative for normal placement and
compatibility; its CUI, wrappers, and network layer are untouched.

## Comparison

| Concern | Legacy FAWE | Modern FAWE core | Enhanced | Overdrive Stage 3 |
|---|---|---|---|---|
| Ownership | mutable queue/chunk coupling | holder separates get/set | owns WE/platform behavior | operation exclusively owns each buffer |
| Scheduling/threading | global queue and unsafe mixing | futures, extents, apply tasks | normal synchronous WE | bounded pure workers; tick-only live commits |
| Backpressure/fairness | no trustworthy byte contract | caches, pools, parallel queues | none for this backend | estimated-byte condition + chunk round robin |
| Lifecycle/cancel/failure | implicit and often swallowed | explicit futures propagate | caller-visible placement | seven states, first cause, terminal cancellation |
| Loading/order/budget | cached/provider access, queue order | platform-dependent application | vanilla Forge | server-thread load, per-op FIFO, fixed tick deadline |
| Packets/players | S21/S22 and watchers, coupled to queue | platform module not supplied | Enhanced CUI/platform stays intact | S22 sparse, S21 dense/biome, watcher-only |
| Tiles/biomes/light | fragile tile flow/custom relighter | processor/deferred finalizers | compatibility authority | tile descriptions, full biome refresh, vanilla skylight |
| Shutdown | global manager lifetime | managed queue/pool lifecycle | platform lifecycle | reject, cancel, wake, interrupt, drain accounting |

The supplied modern tree is core-only, so its concrete Minecraft packet and
player-tracking adapter behavior cannot be compared; those exact APIs come from
the supplied 1.7.10 legacy source and active Forge mappings instead.

## Before Stage 4

No architectural blocker remains. Forge runtime integration still needs live
server testing for mod-specific tile description packets and packet density
tuning. Stage 4 must explicitly call `finishSubmissions`, handle bounded submit
rejection, and preserve the task's world-free contract when adapting WorldEdit.
