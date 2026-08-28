# Stage 5C paste foundation

This change advances Stage 5C without claiming paste acceleration prematurely.
The runtime transformer now recognizes `ForwardExtentCopy` and verifies the
private member and `resume(RunContext)` descriptors needed by a future bridge.
It intentionally leaves the class bytes untouched and reports paste INACTIVE:
the asynchronous command-continuation contract has not yet been implemented,
and returning normally from an intercepted completion would make Enhanced send
feedback and close the session before a multi-tick edit finished.

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

On its first
`resume`, Enhanced constructs either a `RegionVisitor` using its block-copy
function or a backwards transform copy, completes blocks, then completes an
`EntityVisitor` for each repetition. The command invokes
`Operations.completeLegacy(Operation)`.

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
`ForwardExtentCopy`, a `Clipboard` source (directly or through PasteBuilder's
standard `BlockTransformExtent` metadata wrapper), an `EditSession` destination, no
source mutation/filter, and the standard clipboard region and origin identity.
It extracts both origins, transform, repetitions, entity/biome flags, and the
special `ExistingBlockMask` representation of ignore-air. Custom subclasses,
extent chains, masks, and filters receive precise reasons rather than being
mistaken for standard paste graphs.

This strictness is intentional. The adapter is not connected to mutation yet,
so no paste can be partially accelerated by this slice.

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
pasteHookInstalled=false pasteBridgeInvocations=0 pasteAccelerated=0 pasteFallbacks=0 lastPasteFallbackReason=null
pasteRuntimeShape=SEEN_COMPATIBLE forwardExtentCopySeen=true pasteRuntimeShapeCompatible=true pasteBytecodeModified=false
pasteHookReason=compatible; async continuation runner not implemented
```

## Remaining Stage 5C work

**Paste acceleration is still inactive.** No acceptance claim is made for accelerated blocks, transformations, reorder,
tiles, masks, entities, limits, continuation, synchronization, differential
correctness, or performance. In particular, native/modded and tile-bearing
cells have not been made fallback causes; execution simply is not owned at all.
The next implementation must add the scheduler-aware command owner before
installing the hook, then add snapshot-backed planning, exact Enhanced reorder
tables, hybrid raw/native writes, bounded history, and entity history.

No runtime matrix or benchmarks are claimed by this document.
