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
`pasteHookInstalled=true` and `pasteBytecodeModified=true`. The separate
`pasteAccelerated` count remains zero.

## Live verification

Select and copy a visible region, move the placement point, run `//paste`, then
run `/overdrive status`. `pasteBridgeInvocations` must increment; either active
is briefly nonzero or completed increments after the END tick; fallback remains
zero and accelerated remains zero. Run `//undo` and verify the pasted blocks and
entities undo through native history. Repeat with `//paste -a`: the standard
clipboard-bound air mask is owned, while an unsupported mask or graph increments
fallback with a precise reason and continues through Enhanced synchronously.

## Remaining Stage 5C work

**Paste acceleration is still inactive, although deferred interception is live.** No acceptance claim is made for accelerated blocks, transformations, reorder,
tiles, masks, entities, limits, continuation, synchronization, differential
correctness, or performance. In particular, native/modded and tile-bearing
cells have not been made fallback causes; execution simply is not owned at all.
The next implementation must add snapshot-backed planning, exact Enhanced
reorder tables, hybrid raw/native writes, bounded history, and entity history
behind the now-live command owner.

No runtime matrix or benchmarks are claimed by this document.
