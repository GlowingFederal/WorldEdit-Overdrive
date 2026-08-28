# Stage 5C paste foundation

This change begins Stage 5C without claiming paste acceleration prematurely.
The runtime transformer now recognizes `ForwardExtentCopy` and verifies the
private member and `resume(RunContext)` descriptors needed by a future bridge.
It intentionally leaves the class bytes untouched and reports paste INACTIVE:
the asynchronous command-continuation contract has not yet been implemented,
and returning normally from an intercepted completion would make Enhanced send
feedback and close the session before a multi-tick edit finished.

## Verified source path and runtime gate

The pinned Enhanced source builds a paste in
`ClipboardCommands#paste` through `ClipboardHolder#createPaste` and
`PasteBuilder#build`. The result is a `ForwardExtentCopy`. On its first
`resume`, Enhanced constructs either a `RegionVisitor` using its block-copy
function or a backwards transform copy, completes blocks, then completes an
`EntityVisitor` for each repetition. The command invokes
`Operations.completeLegacy(Operation)`.

The runtime artifact itself is now treated independently: the launch
transformer requires exactly one of every adapter field and exactly one
`resume(RunContext):Operation`. A missing or ambiguous anchor leaves the input
byte array unchanged. Even when that shape matches, the current slice remains
inactive until continuation support exists. This is fail-open for WorldEdit.

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
pasteHookReason=runtime graph verified; asynchronous command continuation not installed forwardExtentCopySeen=true
```

## Remaining Stage 5C work

No acceptance claim is made for accelerated blocks, transformations, reorder,
tiles, masks, entities, limits, continuation, synchronization, differential
correctness, or performance. In particular, native/modded and tile-bearing
cells have not been made fallback causes; execution simply is not owned at all.
The next implementation must establish the operation-owned continuation before
installing the hook, then add snapshot-backed planning, exact Enhanced reorder
tables, hybrid raw/native writes, bounded history, and entity history.

No runtime matrix or benchmarks were run because this slice cannot accelerate
paste. The user instruction forbidding binary compilation also prevents the
requested `gradlew clean build` and generated-JAR inspection in this change.
