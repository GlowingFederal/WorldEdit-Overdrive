# WorldEdit Enhanced 6.3.0 integration and ownership

WorldEdit Overdrive is currently a minimal Forge 1.7.10 addon for WorldEdit
Enhanced 6.3.0. Enhanced is the authoritative WorldEdit implementation;
Overdrive does not yet provide KAWE/FAWE acceleration or override WorldEdit.

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

## Deferred work

This baseline intentionally does not port Forge chunk writers, FAWE queues,
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
