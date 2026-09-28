# WorldEdit Enhanced 6.3.0 integration and ownership

WorldEdit Overdrive is a Forge 1.7.10 addon for WorldEdit Enhanced 6.3.0.
Standard supported clipboard pastes use incremental server-thread capture,
immutable worker planning, and server-thread submission and reorder commit.
Enhanced remains authoritative for mutation, ordering, history, and world updates.

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

## Reorder-enabled paste commit lifecycle

Enhanced 6.3.0's `EditSession.flushQueue()` is only a synchronous convenience
method: it passes `commit()` to `Operations.completeBlindly()`. `commit()` starts
at the outer `bypassNone` extent. Each `AbstractDelegateExtent.commit()` places
its own `commitBefore()` operation before its delegate operation in an
`OperationQueue`. `Operations.complete()` and `completeBlindly()` repeatedly call
`Operation.resume()` until it returns `null`; the latter only translates a
`WorldEditException` to a runtime exception.

The reorder node is `MultiStageReorder`. Its commit operation is an
`OperationQueue` containing, in order:

1. `BlockMapEntryPlacer` over the concatenated stage-one and stage-two iterators;
2. the private `MultiStageReorder.Stage3Committer`, which topologically walks
   attachments and places each complete dependency chain; and
3. downstream delegate commits, notably `FastModeExtent.commitBefore()`, which
   calls `world.fixAfterFastMode(dirtyChunks)` when fast mode collected dirty
   chunks.

The stock `BlockMapEntryPlacer.resume()` traverses its entire iterator, and the
stock stage-three `resume()` traverses its entire set. Therefore merely retaining
the top-level `OperationQueue` does **not** bound a server tick. Overdrive's
pinned-6.3.0 LaunchWrapper transform redirects only those two concrete resume
methods to deadline-aware equivalents. Stage one/two retain Enhanced's iterator
and may yield after one placement. Stage three retains the exact remaining set
and block map and may yield only after a complete attachment chain has been
placed and removed. That boundary is safe: no dependency chain is split, the
same `HashSet` selection and attachment walk determine order, and all writes
still pass through Enhanced's downstream extent (including NBT and world update
handling).

The deadline is a server-thread `ThreadLocal` installed only while an
Overdrive-owned paste owner resumes its retained `EditSession.commit()` result.
With no deadline, as in every ordinary `flushQueue()` call, both transformed
operations continue to exhaustion in the same resume invocation. Consequently
Enhanced's public synchronous flushing contract is unchanged.

An accelerated reorder-enabled paste first submits its bounded `setBlock()`
calls through the normal `EditSession`, allowing masks, limits, history, block
bags, and reorder stages to operate normally. Once submission ends, the owner
obtains `EditSession.commit()` exactly once and resumes it on the server
coordinator across ticks. Entity creation starts only after that operation
returns `null`; selection feedback and `LocalSession.remember()` follow entity
creation. The supported reorder path never calls `flushQueue()`. If either
concrete resume transform is unavailable, reorder-enabled pastes fail open to
Enhanced's original command path before Overdrive takes ownership.

The fast-mode dirty-chunk finalizer is one unbounded downstream resume step because
Enhanced exposes `fixAfterFastMode(Set)` only as a whole-set operation. It is not
reimplemented or moved off-thread, so a reorder-enabled session with fast mode
enabled fails open before Overdrive takes ownership. Runtime diagnostics report reorder state,
hook support, incremental slices, resume calls and maximum duration, top-level
commit class, observable stage-three remaining entries, and final synchronous
flush count. Runtime profiling is still required to measure the world-specific
cost of the downstream dirty-chunk finalizer.


## Incremental reorder completion contract

The Enhanced 6.3.0 transforms preserve `BlockMapEntryPlacer`'s field-backed iterator
and keep `Stage3Committer`'s remaining block/type collections in weak, operation-keyed
side state. A deadline returns the same non-null child operation. Only exhausted state
returns `null`; stage three never yields within an attachment chain. The deferred paste
additionally requires the retained top-level commit to return `null` with all observable
stage counts at zero before it creates entities, remembers the edit, or reports success.
A mismatch is a fail-closed acceleration error because replaying vanilla after partial
mutation would be unsafe. Detailed paste counters are reset on admission and status
labels identify them as last-paste values.

## Commit deadline and pacing diagnostics

The deferred manager creates one hard deadline at the start of its server-tick callback,
after observing the normal server tick. Earlier owners consume that same allowance.
Each paste has independent capture, submission, and reorder time controllers. They start
at 5 ms, with a 1 ms floor and a 30 ms ceiling, and clamp their slice deadline to the hard
deadline. The coordinator's separate `coordinatorCommitTick` setting does not control paste.

Four safe samples using at least 75% of the available target permit 10% growth. More than
10% overshoot (with a 0.1 ms tolerance) halves the target immediately; exceeding twice the
allowance clamps it to 1 ms. A shortened allowance caused by another owner cannot train
growth. Entering stage three or downstream finalizers clamps the reorder target to at most
5 ms and resets the growth streak. All corrections apply during the current paste.
Only the global server-load estimate and lifetime tick statistics survive across pastes;
an earlier paste's learned phase targets are never used as startup targets.

A retained commit installs its absolute `System.nanoTime()` slice deadline in the reorder
bridge. Children check before the next placement or complete dependency chain, including
when they enter with an expired deadline. The driver can resume again if a child finishes
early, but never extends the current slice deadline. Growth affects a subsequent tick;
contraction may shorten the current slice. A chain already started finishes atomically.

Status separates capture active/elapsed time, summed worker time/planning elapsed time,
submission active/elapsed time, reorder commit active/elapsed time, and finalization
active/elapsed time. `commitState*` covers only the reorder phase, including operation
initialization. `commitServerMillis`, `lastPasteCommitMillis`, and `commitActiveMillis`
retain their aggregate meaning: submission + reorder + finalization. `commitWallMillis`
continues updating through reorder and finalization instead of freezing after submission.

Resume statistics include actual mean and maximum duration, a fixed-memory 0.25 ms
median interval (averaging the two middle samples for an even count), allowance utilization, and a count
above 50 ms. Per-resume child counts are reset before each top-level call, so a stage-three
or downstream call cannot inherit the previous block placer's work counts. Diagnostics
also report controller increases/decreases, stage-three preparation time, complete-chain
time, and the slowest downstream `setBlock` with stage, block ID/data, NBT presence,
position, chunk, and downstream extent. These identify the call context, not an internal
lighting/update/chunk-load attribution; that requires runtime profiling of the native path.

Placement counters are published once per child resume rather than through atomic updates
for every block. Ordinary synchronous flushing installs no deadline and does not modify
last-paste diagnostics. A downstream mutation, stage-three setup/collection clearing, or a
complete dependency chain can still overshoot: none is preemptible inside its native call.
No latency or throughput improvement is claimed from static controller inspection alone.

## Incremental paste startup

The standard Enhanced `PasteBuilder.build()` constructs references and an operation graph;
it does not visit the clipboard. Overdrive's command interception performs graph checks and
constant-size admission only. The first server tick creates a small page directory rather
than five full-volume arrays. Capture allocates 1,024-cell pages as it progresses under the
same phase/tick deadline; auxiliary block maps are local to those pages. Full integer IDs,
metadata, destination coordinates, and copied `BaseBlock` NBT remain unchanged.

The two reorder commit classes are resolved through Enhanced's actual class loader, without
initialization or running a commit, during `FMLServerStartingEvent`. They are otherwise lazy:
checking hook flags before the first `MultiStageReorder.commitBefore()` could reject the cold
first paste and run it synchronously merely because its commit classes had not yet been
offered to LaunchWrapper. Hook preparation removes that bootstrap dependency. An incompatible
transform still leaves support disabled and retains the semantic fallback.

Air counts accumulate during capture. Entity capture obtains concrete `BlockArrayClipboard`'s
constant-time unfiltered list and resumes region filtering and native `ExtentEntityCopy`
one entity at a time. Entity snapshots also use pages. NBT admission sizing walks tag entries
incrementally, with at most 4,096 entries per continuation, instead of rendering entire tag
trees to strings. This retained-memory estimate accounts for tag/container/string/array data;
it is an estimate, not an exact JVM heap measurement.

Snapshot sealing transfers the completed builder's pages in constant time. There is no
full auxiliary-map copy, final air pass, or NBT scan at that boundary. The existing dense-array
snapshot constructor is retained for API compatibility, but the live owner never calls it.
Planning jobs are dispatched at most four per server tick, under the capture time target;
the worker-only filtering and final chunk grouping retain their existing semantics.

Supported graphs rejected by the 64 MiB per-operation or 128 MiB global admission limits
now report a resource error and return without placement or success feedback. They no longer
fall into a synchronous full-size native paste. Busy global admission can be retried after
the active paste finishes. Unsupported graphs, missing hooks, and reorder plus fast mode
still follow the native fallback contract. A capture that grows past admission fails before
destination submission and is never replayed synchronously.

Status includes `captureWorkStage`, `captureSlices`, `capturePagesAllocated`, and
`maxCaptureSliceMillis`, plus a separately labeled lifetime admission-rejection count and
the last rejection reason. Individual native clipboard reads, transform hooks, entity copies,
and JVM allocation/GC pauses are still indivisible; a cooperative deadline cannot preempt
one of those calls. Large-paste startup responsiveness remains a runtime verification item.
