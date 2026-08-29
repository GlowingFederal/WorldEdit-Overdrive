# Stage 5C live deferred paste interception

The runtime shape gate remains strict, and this increment installs the first active command-scoped paste interception. It defers Enhanced's original operation without claiming accelerated planning.

## Verified source path, LaunchWrapper identity, and runtime gate

The pinned Enhanced source builds a paste in
`ClipboardCommands#paste` through `ClipboardHolder#createPaste` and
`PasteBuilder#build`. The builder directly executes `new ForwardExtentCopy(...)`;
there is no Enhanced wrapper or replacement in this standard path. The exact
binary name is `com.sk89q.worldedit.function.operation.ForwardExtentCopy`, its
internal name is `com/sk89q/worldedit/function/operation/ForwardExtentCopy`, its
superclass is `java.lang.Object`, and its sole direct interface is
`com.sk89q.worldedit.function.operation.Operation`. Enhanced ships this class
under that name; it is third-party WorldEdit code rather than an obfuscated
Minecraft class and is not remapped. LaunchWrapper therefore normally supplies
the same dotted value for `name` and `transformedName`. The narrow matcher also
accepts the exact value in either argument and normalizes slash form because
coremod callers/tests may expose an internal name. It does not suffix-match or
accept another `Operation` implementation.

The audited Enhanced 6.3.0 class declares the following paste-relevant private
instance fields (the first five are also `final`):

| Name | JVM descriptor | Meaning |
| --- | --- | --- |
| `source` | `Lcom/sk89q/worldedit/extent/Extent;` | block and entity source |
| `destination` | `Lcom/sk89q/worldedit/extent/Extent;` | block/entity creation target |
| `region` | `Lcom/sk89q/worldedit/regions/Region;` | traversal and entity-query bounds |
| `from`, `to` | `Lcom/sk89q/worldedit/Vector;` | source and destination anchors |
| `repetitions` | `I` | repetitions remaining |
| `sourceMask` | `Lcom/sk89q/worldedit/function/mask/Mask;` | block filter |
| `removingEntities` | `Z` | whether copied source entities are removed |
| `sourceFunction` | `Lcom/sk89q/worldedit/function/RegionFunction;` | optional post-copy source mutation |
| `transform`, `currentTransform` | `Lcom/sk89q/worldedit/math/transform/Transform;` | configured and repetition-accumulated transforms |
| `lastVisitor` | `Lcom/sk89q/worldedit/function/visitor/RegionVisitor;` | prior block traversal awaiting affected-count accounting |
| `affected` | `I` | accumulated affected block count |

All fields are declared directly by `ForwardExtentCopy`; it extends `Object`
and implements only `Operation`. There are no destination-mask,
`filterFunction`, `copyEntities`, or `copyBiomes` fields in this artifact.

On each `resume`, Enhanced constructs `ExtentBlockCopy`, wraps it in a
`RegionMaskingFilter`, optionally combines the filter with `sourceFunction`,
and queues its `RegionVisitor` before an `EntityVisitor`. Entity visitation is
**unconditional**: `source.getEntities(region)` supplies the entities and an
`ExtentEntityCopy(from, destination, to, currentTransform)` transforms their
locations and calls `destination.createEntity`. `removingEntities` controls
source removal, not whether copying occurs. Thus the old `copyEntities` gate
was an assumption imported from a different WorldEdit layout. A destination
`EditSession` receives entity creation through its normal extent/history chain;
future acceleration must retain that behavior and ordering after blocks for
every repetition.

Enhanced 6.3.0 `ForwardExtentCopy.resume()` contains no biome read, write,
function, or visitor. Standard `PasteBuilder` exposes no entity, biome, or
repetition setters: standard paste therefore always copies entities, never
copies biomes, and starts with one repetition. Biome-enabled behavior cannot be
represented by this standard graph and remains vanilla/unsupported rather than
being inferred.

The runtime artifact itself is treated independently: the launch transformer
requires the exact class, superclass, sole interface, field names and JVM
descriptors used by the adapter, and one public
`resume(RunContext):Operation` with descriptor
`(Lcom/sk89q/worldedit/function/operation/RunContext;)Lcom/sk89q/worldedit/function/operation/Operation;`.
A missing, altered, or ambiguous anchor leaves the input
byte array unchanged. Even when that shape matches, the current slice remains
inactive until continuation support exists. This is fail-open for WorldEdit.

The runtime discovery state is one of `NOT_SEEN`, `SEEN_INCOMPATIBLE`,
`SEEN_COMPATIBLE`, or `HOOK_INSTALLED`. `NOT_SEEN` means LaunchWrapper has not
offered the exact target class and is not an incompatibility verdict.
`SEEN_INCOMPATIBLE` retains the precise first shape problem. A compatible class
currently becomes `SEEN_COMPATIBLE`, with interception intentionally inactive
and bytecode unmodified. `HOOK_INSTALLED` is reserved for a later increment
that actually modifies bytes.

## Verified operation-runner semantics and ownership

Enhanced's `Operations.complete`, `completeLegacy`, and `completeBlindly` are
synchronous `while (operation != null) operation = operation.resume(context)`
loops. A non-null returned operation is immediately resumed; it is not queued
for a later tick. Returning `null` ends the loop. A returned nested operation
simply replaces the current value and is driven in the same loop. The runner
has no externally-progressing-operation wait or scheduler, and these completion
helpers do not call `cancel`; cancellation only exists on the `Operation`
contract for an owner that abandons work.

`ClipboardCommands#paste` calls `completeLegacy` before changing selection and
sending `COMMAND_PASTE` success/tip feedback. Consequently, returning `null`
after merely submitting work would make the command report completion and let
the surrounding command/edit-session lifecycle proceed too early. Returning a
pending operation to this runner would busy-spin on the server thread, while a
future/future-like blocking wait would stall that thread. History and final
EditSession effects must therefore remain owned until commit and entity/session
finalization are finished, before command feedback is allowed to execute.

`PasteContinuationOperation` supplies the cross-thread lifecycle token with an
`AtomicReference` state: `CREATED`, `SUBMITTED`, `RUNNING`, `COMMITTING`, and
the distinct terminal states `COMPLETED`, `FAILED`, and `CANCELLED`. Pending
`resume` returns the continuation and terminal `resume` returns `null`; this
models the `Operation` contract but **must not be inserted into the current
synchronous runner**. The minimal future integration point is therefore the
command completion boundary: a scheduler-aware owner must retain the command
continuation, arrange worker planning and bounded main-thread commits, and only
re-enter/finalize command completion when terminal. No method in this
foundation submits work, waits, or mutates a clipboard/world.

`PasteBridge.Result` also separates `VANILLA` from `CONTINUATION` ownership and
requires a non-null continuation before ownership can be claimed. The bridge
currently always returns `VANILLA`; because no bytecode calls it yet, discovery
does not increment invocation or fallback counters.

## Strict operation adapter

`PasteOperationAdapter` only accepts the concrete Enhanced
`ForwardExtentCopy`, the exact standard `BlockTransformExtent -> Clipboard`
source graph, an exact `EditSession` destination, no source mutation or entity
removal, and the standard clipboard region/origin identity. It also requires an
unstarted traversal (`currentTransform` and `lastVisitor` null, `affected`
zero), and requires the block-state wrapper and coordinate copy to hold the
same transform object. Ignore-air is recovered only from an
`ExistingBlockMask` bound to that clipboard; the sole other accepted mask is
the `Masks.alwaysTrue()` singleton.

The first live intercepted paste reached every one of those predicates and
failed only at the region identity check. That isolated the actual graph as
`BlockTransformExtent -> BlockArrayClipboard`, with a direct `EditSession`
destination, null `sourceFunction`, the `Masks.alwaysTrue()` singleton, and an
identity transform shared by the wrapper and copy. The earlier assumption that
`copy.region == clipboard.getRegion()` was false: `BlockArrayClipboard`
deliberately clones its stored region on every `getRegion()` call. PasteBuilder
therefore passes one clone into `ForwardExtentCopy`, while the adapter observes
a different clone when it asks the clipboard again.

The adapter now proves that cloned edge semantically and narrowly: both regions
must have the same concrete class, minimum and maximum points, area, and world
identity. It still requires the clipboard origin, exact wrapper and destination
classes, shared transform object, standard initial repetition count of one,
and untouched traversal state. PasteBuilder always installs
`BlockTransformExtent`, including for an identity transform; `//rotate 90`
retains the identical wrapper graph and changes only the shared transform
implementation/value. There is no identity-only direct-clipboard variant in
the pinned builder.

Normal paste leaves `sourceFunction` null. `CombinedRegionFunction` is created
later inside `ForwardExtentCopy.resume()` only when a caller supplied a source
mutation, so seeing one in the operation field remains nonstandard. Normal
paste keeps the non-null `Masks.alwaysTrue()` singleton. `//paste -a` changes
only that field to `ExistingBlockMask -> BlockArrayClipboard`; the mask extent
must be the very clipboard recovered from the transform wrapper. No masking,
position-transform, destination-delegate, or other arbitrary wrapper is
accepted.

The requirement classification is: source, destination, region, anchors,
repetitions, source mask/function, transform, removal flag, and traversal state
are `REQUIRED_DIRECT_FIELD`; the clipboard and ignore-air setting are
`DERIVABLE_FROM_STANDARD_GRAPH`; `copyEntities`, `copyBiomes`, and
`filterFunction` are `NOT_PRESENT_IN_ENHANCED_6_3_0`; custom wrappers,
subclasses, masks, source mutation/removal, already-started traversal, and
biome-copy graphs are `UNSUPPORTED`. Entity and biome values exposed by the
adapter are consequently the verified constants `true` and `false`, not
reflected or guessed booleans.

This strictness is intentional. The live bridge now uses the adapter solely as
the ownership gate; rejected graphs remain wholly synchronous and accepted
graphs run the unchanged operation later, so no paste is partially handled.

## Primitive clipboard representation

`PreparedClipboardView` establishes the worker-safe numeric layout: full-width
bounds/origin, packed 12-bit legacy ID plus four-bit metadata states, compact
tile lookup, constant-time air checks, and primitive contiguous indexing. It
does not yet provide a clipboard materializer. A later slice must select direct
access for proven compact Enhanced clipboards and server-thread snapshotting
for unknown implementations before workers may read them.

## Diagnostics

`/overdrive status` now reports paste diagnostics independently of `//set`:

```text
pasteHookInstalled=true pasteBridgeInvocations=1 pasteAccelerated=0 pasteFallbacks=0 lastPasteFallbackReason=null
pasteDeferredActive=0 pasteDeferredCompleted=1 pasteDeferredFailed=0 lastPasteDeferredReason=owned standard Enhanced 6.3.0 paste graph
pasteRuntimeShape=HOOK_INSTALLED forwardExtentCopySeen=true pasteRuntimeShapeCompatible=true pasteBytecodeModified=true
pasteHookReason=installed
```

## First live command interception and ownership

The active transformer targets only
`com.sk89q.worldedit.command.ClipboardCommands#paste(Player, LocalSession,
EditSession, boolean, boolean, boolean):void`. It requires exactly one matching
overload and exactly one static `Operations.completeLegacy(Operation):void`
invocation. Immediately before that invocation, injected code duplicates the
built operation and calls `PasteBridge.tryDefer`. `VANILLA` leaves the original
operand and call untouched. `DEFERRED` is returned only after the strict adapter
accepts the graph and `DeferredPasteManager.register` retains a complete owner;
only then does the injected branch discard the operand and return. Descriptor,
call-site, or graph mismatch fails open, and no global operation runner changes.

The owner retains the original `ForwardExtentCopy`, destination `EditSession`,
player, `LocalSession`, clipboard holder, destination, and selection flag.
Registration performs no traversal. At a later server END tick the manager
progresses one owner with the original `Operations.completeLegacy` on the main
thread. Enhanced therefore remains authoritative for traversal, air masks,
transforms, repetitions, entities, affected count, extent behavior, and native
history. One operation may consume that tick in this validation increment; this
is lifecycle deferral, not accelerated or worker-side paste planning.

The command framework's early remember observes an empty session. After the
later traversal, the owner calls `LocalSession.remember` on the retained
EditSession, which flushes and records the normal Enhanced change set for undo.
Only then does it replay Enhanced 6.3.0's optional selection update and exact
paste success line. Feedback is therefore emitted once and only after mutation.
A later-tick failure is counted; it cannot be synchronously rethrown into a
command stack which has already returned.

Status exposes `pasteDeferredActive`, `pasteDeferredCompleted`,
`pasteDeferredFailed`, and `lastPasteDeferredReason`, in addition to hook,
bridge, fallback, and shape fields. Installed transformation makes
`pasteHookInstalled=true` and `pasteBytecodeModified=true`. The accelerated
slice adds the more detailed counters described below.

Every bridge attempt also publishes `lastPasteGraphDiagnostic`. It lists only
the known paste fields and the one standard source delegate (source,
destination, mask, source function, configured/current transforms, clipboard
delegate classification), followed by the exact rejecting predicate on
fallback. It neither calls object `toString()` nor walks an arbitrary object
graph. A successful recognition records the same bounded shape without a
reject clause.

## Live verification

Select and copy a visible region, move the placement point, run `//paste`, then
run `/overdrive status`. `pasteBridgeInvocations` must increment; either active
is briefly nonzero or completed increments after the END tick. A supported
identity block-only paste increments accelerated; semantic fallback does not.
Run `//undo` and verify the pasted blocks undo through native history. Repeat
with `//paste -a`: the standard
clipboard-bound air mask is owned, while an unsupported mask or graph increments
fallback with a precise reason and continues through Enhanced synchronously.
For the immediate live gate, use `//copy`, `//paste`, `/overdrive status`, and
`//undo`; require `pasteFallbacks=0`, `pasteDeferredCompleted=1`, and visually
confirm undo. Then repeat the status-and-undo sequence with `//paste -a`. A
rotated probe (`//rotate 90`, `//paste`) verifies the same standard graph with
the non-identity shared transform. These runtime actions cannot be performed by
the source-only build environment and remain the required server acceptance
step before accepting acceleration in production.

## First accelerated identity-paste slice

The live owner accelerates only the narrow Enhanced 6.3.0 standard graph when
its delegate is the concrete `BlockArrayClipboard`, its destination is the
concrete `EditSession`, traversal has not started, repetitions are one, source
mutation/removal is absent, `sourceFunction` is null, and the source mask is the
standard always-true mask or clipboard-bound `ExistingBlockMask`. The transform
must be `Identity`; rotations/flips retain the proven deferred vanilla traversal
rather than approximating their coordinate or metadata semantics. `//paste -a`
is accelerated by filtering source air only.

Clipboard capture is explicitly synchronous on the command/server thread. It
records full-width bounds and origin plus an immutable primitive `int[]` of
`(legacyId << 4) | metadata`, preserving metadata without palette conversion.
Tile NBT is indexed for conservative detection. Any tile NBT or copied entity
selects deferred vanilla before planning, so associated data is never omitted.

Admission precedes large allocations. Its conservative estimate includes the
capture array, immutable defensive copy, four primitive plan arrays, and
metadata (24 bytes per source position plus a fixed allowance). It must fit the
64 MiB operation limit and an atomic reservation under the 128 MiB global
retained limit. Rejection records a precise acceleration fallback reason and
uses deferred vanilla; reservations are released on every terminal path.

Workers receive only `PreparedClipboardView`, ignore-air, and numeric operation
parameters. They count entries and publish an immutable `int[] x/y/z/state`
plan through a volatile reference. They never access a clipboard, extent,
session, world, chunk, entity, or tile. At server END ticks, a published plan
moves `RUNNING -> COMMITTING` and calls `EditSession.setBlock` with legacy
`BaseBlock(id, metadata)` entries until the five-millisecond deadline. Remaining
entries continue next tick. Native mutation records WorldEdit history; only
after the final entry does the owner call `LocalSession.remember` once, issue
feedback, complete, and increment `pasteAccelerated`.

A planner failure before mutation switches safely to the retained original
`ForwardExtentCopy`. After the first accelerated `setBlock`, ownership is
irrevocable: commit failure is reported and vanilla is never replayed. Custom
graphs, transforms, entities, tiles, and admission rejection remain on deferred
vanilla traversal.

Status exposes acceleration fallback count/reason, active planning/commit
gauges, prepared/planned/committed counts, and separate preparation/planning/
commit timings. A fresh supported paste should show
`pasteBridgeInvocations=1`, `pasteAccelerated=1`, `pasteDeferredCompleted=1`,
and `pasteDeferredFailed=0`.

For live verification, use a block-only, entity-free clipboard: run `//copy`,
`//paste`, `/overdrive status`, then `//undo` and verify complete restoration.
Repeat with a large clipboard and observe the planning/commit gauges while the
server remains responsive across ticks. Repeat with air gaps and `//paste -a`,
confirming destination blocks beneath source air remain unchanged, then undo.
Finally include a tile/entity and verify acceleration stays unchanged, the
specific fallback reason appears, deferred vanilla succeeds, and undo works.

Transformed coordinates/state, tile commits, and entity planning/history remain
future Stage 5C expansion points. No in-game result is claimed by this
source-only environment; the production-jar procedure above is mandatory.

## Deferred EditSession finalization correction

The pinned Enhanced 6.3.0 lifecycle is materially different from modern
WorldEdit. `Operations.completeLegacy` only resumes an operation to completion;
it does not flush an `EditSession`. Command-created sessions are always queued
(`isQueueEnabled()` returns true and `enableQueue()` is a compatibility no-op).
`CommandManager` normally owns the terminal boundary in its `finally` block:
it captures queue/chunk state, calls `EditSession.flushQueue()`, flushes the
block bag, and calls `LocalSession.remember(editSession)`. Because Overdrive
returns from the intercepted call before deferred mutation, that normal boundary
runs against an empty queue.

Enhanced's `LocalSession.remember(EditSession)` is not a substitute for placing
the flush at the mutation boundary. Its three-argument implementation does call
`flushQueue()` defensively, but only after the command framework has already
performed its original empty completion point. `flushQueue()` completes the
session's reorder commit operation, resets the edit limit, drains/dequeues the
FAWE queue, and closes the current history stage. It does not mark the
`EditSession` unusable; Enhanced itself describes remember's flush as a
defensive repeat, and `disableQueue()` is merely a conditional `flushQueue()`
(with queueing permanently reported enabled), not a distinct terminal mode.

The corrected deferred-vanilla order is therefore `completeLegacy(original) ->
destination.flushQueue() -> LocalSession.remember(destination) -> selection and
success feedback`. This explains the live entity-only symptom: entity creation
was not waiting in the block queue, while copied blocks were. The original
operation is still executed exactly once, and the original retained
`EditSession` is still remembered exactly once by the deferred owner.

Accelerated placement drains after every bounded batch rather than accumulating
the entire plan for one terminal flush. Enhanced exposes no incremental
`EditSession.flushQueue` budget, so each batch is capped at 256 submissions and
also stops submissions at the five-millisecond deadline before flushing. A
single queue drain may exceed five milliseconds, but its input is bounded; its
actual duration is included in `lastPasteCommitMillis` instead of being hidden
outside the measurement. `pasteSubmittedBlocks` counts calls submitted to the
retained session, while `pasteCommittedBlocks` advances only
for changed blocks after that batch's flush succeeds. Flush failure leaves the
owner failed and never increments `pasteAccelerated`. The final successful
order is last submission, last batch flush, history remember, feedback,
terminal lifecycle completion, and `pasteAccelerated` increment.

## Full standard PasteBuilder acceleration

The accelerated ownership path now covers every semantic emitted by Enhanced
6.3.0 `PasteBuilder`: legacy blocks and full-width metadata, clipboard and
placement origins, ordinary air writes and `ExistingBlockMask` ignore-air,
identity/rotation/reflection transforms, block NBT, and the unconditional
clipboard-entity pass. The pinned builder does not copy biomes, repeat, mutate
the source, or remove entities.

Preparation remains on the command/server thread. Blocks are read through the
actual `BlockTransformExtent`, so its target-world registry and transform hook
produce precisely the same directional metadata (including modded registry
states) as Enhanced traversal. Coordinate destinations use the pinned
`transform.apply(source - from) + to` expression. Published worker data is only
primitive IDs, untruncated integer metadata, already-transformed integer
coordinates, sparse copied `BaseBlock` payloads, and immutable entity snapshots;
workers never retain a holder, transform extent, EditSession, or live entity.

Sparse auxiliary blocks carry complete `BaseBlock` NBT into native
`EditSession.setBlock`. This deliberately delegates coordinate-bearing tile NBT
and platform-specific tile creation to WorldEdit's normal placement chain
rather than creating Minecraft tile objects. Entity preparation invokes the
pinned `ExtentEntityCopy` synchronously against a capture-only extent. It
therefore reuses Enhanced's half-block pivot, position/direction conversion,
and hanging-entity `TileX/TileY/TileZ`, `Direction`, and `Dir` rewrite exactly.
Only copied `BaseEntity` state and its final immutable location cross into the
plan; creation later uses `EditSession.createEntity`.

Commit has block and entity subphases. At most 256 blocks and then at most 64
entities are submitted within the shared five-millisecond tick deadline.
Every block batch is flushed, all blocks and tiles are applied before entity
creation, and a final flush precedes `LocalSession.remember`, selection, and the
exact Enhanced success line. Native `setBlock` return values supply affected
block diagnostics; native EditSession block/entity change extents remain the
single undo/redo mechanism. Enhanced's command itself defines pasted selection
as `to` through `to + (region.max - region.min)` even for transformed clips, so
the retained completion reproduces that definition rather than inventing a
transformed bounding-box behavior.

Admission conservatively includes primitive source/destination arrays, sparse
block/NBT payloads, entity snapshots, and worker indices under the 64 MiB
operation and 128 MiB global limits. Status reports prepared/committed tiles
and entities, transformed blocks, transform class, and ignore-air alongside
block planning and actual-change counters.

Legitimate deferred traversal is now limited to non-concrete clipboards,
nonstandard/custom operation or extent graphs rejected by the strict adapter,
custom masks/functions, source mutation/entity removal, repetitions or an
already-started traversal, resource admission failure, and safe preparation or
planning failure before mutation. No semantic fallback remains for standard
transforms, tiles, entities, or their combination with ignore-air. After the
first accelerated mutation, failure is irrevocable and never replays the
original operation.

### Feature-rich live verification

Create one clipboard containing air gaps, multiple directional legacy blocks,
a chest/sign and preferably a modded NBT tile, plus an entity. Exercise normal
`//paste`, `//rotate 90` plus `//paste`, a `//flip` plus paste, and `//paste -a`.
After each operation inspect `/overdrive status`, then verify `//undo` (and
`//redo` where enabled) removes/restores blocks, tile data, and entities without
orphans or duplicates. A successful complex operation increments
`pasteAccelerated` once, leaves acceleration fallbacks unchanged, reports
positive tile/entity commits, and keeps `pasteDeferredFailed=0`.

## Tick-bounded capture lifecycle

The accelerated standard paste path now claims only immutable graph references, bounds,
and a conservative memory reservation in the command hook. It does not enumerate the
clipboard or entities before returning. The server-tick owner retains primitive `x/y/z`,
ID, and metadata arrays plus a sparse `BaseBlock` map for NBT or specialized blocks. A
primitive linear cursor maps each index to `x/z/y` coordinates and resumes without
restarting traversal.

The same adaptive global deadline used for commit now bounds clipboard block capture and
entity transformation. When capture is complete, one bounded worker submission performs
air filtering and chunk-local ordering without accessing the live world. Commit checks the
deadline between mutations and crosses at most two distinct destination chunks per owner
slice, limiting synchronous chunk-load pressure. Entity creation is capped at sixteen per
slice. Native `EditSession` mutation and history remain on the server thread. Queue flush
is deliberately confined to finalization because Enhanced 6.3.0 does not expose a
preemptible partial-flush API; its duration is included in maximum-slice and wall metrics.

`/overdrive status` exposes the active phase, capture progress, worker task counts, commit
remainder, command interception time, phase wall/active time, total wall time, and maximum
server slice. Wall time measures elapsed lifecycle latency; active time measures actual
Overdrive occupancy on the server thread.

## Command interception audit

The previous command bridges performed the following work before returning:

* paste captured every transformed clipboard block and enumerated/transformed entities;
* replace traversed the full region, evaluated its live mask, and copied every old block;
* walls, faces/outline, and center generated complete position lists and immediately
  evaluated patterns and mutated them;
* overlay scanned every selected column from top to bottom before applying its pattern;
* naturalize scanned and mutated every block in every selected column;
* stack and move fully captured the source and synchronously performed all repetitions,
  clearing, and destination writes; and
* set prepared all changed chunks, captured complete history, committed, synchronized,
  and emitted packets in the intercepted invocation.

Paste is now the only one of these paths reported `ACTIVE`: it follows incremental capture,
worker planning, bounded commit, then finalization. The installed set, replace, geometry,
overlay, naturalize, stack, and move bytecode hooks are reported `HOOKED` and immediately
fall through to Enhanced's native implementation. This is intentional: hook presence is
not represented as asynchronous safety, and Overdrive no longer claims those operations
until dedicated incremental owners preserve their mask/pattern and (for stack/move)
full-source-before-mutation ordering.

## Bounded native queue pipeline

The dedicated-server regression was traced to the deferred owner's lifecycle: bounded
calls to `EditSession.setBlock` were retained across ticks, while the first meaningful
`EditSession.flushQueue` occurred only after the last planned batch. Enhanced 6.3.0's
`flushQueue` completes the EditSession operation, then sends its `FaweQueue` through
`SetQueue.flush` on the main thread (or calls the queue's blocking `flush` on the other
path). `LocalSession.remember` also calls `flushQueue` defensively. Consequently, the old
finalization admitted virtually the complete paste to a single non-preemptible drain.

The commit invariant is now **bounded native queue population and bounded drain**, not
merely bounded `setBlock` submission. Each owner submits at most the adaptive native
queue limit, touches no more than two destination chunks, immediately flushes that
population, records the complete submission-plus-drain occupancy, and yields. The next
tick cannot submit more work left over from a prior drain. A single large destination
chunk is split at the same adaptive mutation boundary. The drain controller smooths
observed nanoseconds per queued mutation, halves aggressively after an over-budget
drain, grows by at most 25 percent after a cheap drain, and can never admit more than
4,096 mutations. Finalization retains a defensive empty flush before entities and the
flush inside `LocalSession.remember`; neither can contain accumulated block mutations.

Planning is partitioned into bounded 8,192-source-cell tasks rather than two monolithic
jobs. Workers independently perform air filtering and compact source-index production;
the last worker combines those immutable results into stable destination-chunk batches.
This makes worker count, total worker CPU time, average task time, and maximum concurrent
workers observable. Live destination reads, `EditSession.setBlock`, native queue drain,
entity creation, and history ownership remain server-only for Forge 1.7.10 safety.

`/overdrive status` now separates source/destination capture, worker planning, mutation,
queue drain, and finalization server time. It reports queue population, chunk population,
flush count/last/average/maximum time, submission maximum, final-flush population/time,
and the native queue/extent classes and enabled state. The formerly ambiguous
`maxCommitTickMillis` label is now `maxOverdriveTickWorkMillis`: it measures only wall
time inside `DeferredPasteManager.tick`, including a non-preemptible drain, rather than a
whole Minecraft tick.

## Architecture references audited

- **Modern FAWE (vendored `ReferenceSRC/ModernFAWECoreSRC`)**: its queue extent and batch
  processor architecture accumulates compact chunk-local changes, applies processors at
  batch boundaries, and uses bounded parallel preparation rather than using a worker pool
  solely as a command wrapper. Adapted concepts are immutable chunk grouping, many
  bounded preparation units, explicit backpressure, and observable queue-drain ownership.
  Direct modern chunk/state code was rejected because its block-state, lighting, packet,
  Java, and chunk APIs are incompatible with numeric-ID Forge 1.7.10.
- **Legacy FAWE / WorldEdit (this repository's core and forge1710 sources)**: `FaweQueue`,
  `SetQueue`, and `ForgeChunk_All` confirm chunk-local numeric ID/metadata buffering and a
  blocking apply boundary. The existing Overdrive raw chunk writer remains appropriate
  for proven constant fills, but paste retains `EditSession` for masks, limits, history,
  tile NBT, and entity semantics.
- **Enhanced 6.3.0 (`ReferenceSRC/WorldeditEhancedCoreSRC` and pinned core)**:
  `EditSession.flushQueue` is synchronous and non-preemptible once entered, selects
  `SetQueue.flush` on the main-thread queue path, and closes the change set after the
  drain. `LocalSession.remember` invokes it again. These exact semantics require limiting
  the input population before every drain; moving the EditSession or live world to a
  worker was rejected as unsafe.
