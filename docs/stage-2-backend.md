# Stage 2: Forge 1.7.10 acceleration backend

Stage 2 introduces an operation-owned preparation model and a server-thread-only
Forge writer. It deliberately has no `EditSession`, command, history, packet, or
scheduler integration. WorldEdit Enhanced 6.3.0 remains the runtime authority.

## Architecture and ownership

`PreparedChunkChange.Builder` accepts numeric IDs (0–4095), metadata (0–15),
explicit air (ID 0), optional copied native tile NBT, optional biome values, and
marks every affected height/light column. An absent section entry or absent
section-local index means unchanged. Calling `build()` transfers ownership and
closes the builder. There are no static buffers, queue caches, thread-count
settings, or cross-operation mutable state.

Preparation performs no world access and can later run off-thread when its input
is immutable. Commit is a separate call to `ForgeChunkWriter`; it asserts the
Minecraft server thread and never silently schedules itself. The section loop is
deliberately bounded so Stage 3 can put cancellation checks between chunks and
sections without redesigning storage.

## Representation and memory model

A sparse section starts with parallel primitive `short[]` index and `int[]`
packed-state arrays. A repeated coordinate updates its existing slot and does
not change the exact count. At 512 changed positions (12.5%) the section promotes
once to a 4096-entry contiguous `int[]`, with `-1` meaning unchanged. Packed
states use 12 block-ID bits and four metadata bits, so air needs no sentinel and
IDs above 255 survive preparation. Sparse iteration touches only changed entries;
dense iteration is linear and cache-friendly.

This simple hybrid avoids the roughly 8 KiB eager cost of legacy parallel
`char[4096]`/`byte[4096]` arrays for tiny edits, while avoiding hash entries and
per-block objects. It deliberately accepts an O(n) sparse repeated-write search:
below 512 entries this is bounded and avoids another index table. A later stage
may add a primitive lookup table if profiling proves that worthwhile.

`estimatedBytes()` includes section array capacities, dense arrays, column bits,
biomes, a fixed chunk envelope, and a conservative tile-NBT floor plus its text
size. It is an estimate rather than JVM instrumentation, but is stable and cheap
enough for Stage 3 backpressure. No global scheduler and no `System.gc()` call is
introduced.

## Commit behavior

Requested raw writes pass through `RawMutationClassifier`. The initial
classifier identity-checks only a small set of inert vanilla block instances and
rejects tile-bearing or random-ticking states; every unknown or modded block uses
`World.setBlock(..., 2)`. This is intentionally conservative. It is a capability
boundary, not a giant numeric-ID switch.

Raw writes update LSB, metadata nibble, and optional MSB nibble arrays directly.
The MSB nibble is also cleared when replacing an ID above 255 with a low ID.
Every raw-touched section finishes through vanilla
`ExtendedBlockStorage.removeInvalidBlocks()`, which recalculates both non-air and
random-ticking counts over the final state. Empty sections are then removed.
Native-compatible placements leave counter maintenance to vanilla. This costs a
4096-entry scan only for sections that actually used raw writes and avoids the
legacy counter corruption; incremental counting is deferred until it can be
proved correct for modded random-tick behavior.

Every affected column is recorded. After block writes the writer calls vanilla
`Chunk.generateSkylightMap()`. Although this is a whole-chunk bounded rescan, it
correctly handles both additions and removals and uses contextual block opacity,
unlike a highest-non-air or monotonic-height shortcut. Dirty columns and the
section mask remain in the result for a narrower Stage 3 lighting implementation.
No asynchronous or broad external relighter is ported.

Tile transitions are explicit: any existing tile is invalidated and removed
before its block changes; a resulting tile-capable block gets an explicit
block-created instance; supplied NBT is defensively copied, coordinates are
normalized, its declared type is checked against the block-created tile, and the
tile is installed, validated, and dirtied through world APIs. This covers tile to
air/normal, normal to tile, tile-type replacement, and same-block metadata/NBT
changes. Old tiles can serialize their NBT before removal when Stage 3 connects a
history sink; Stage 2 does not retain history globally.

`SideEffectPolicy` distinguishes raw storage from native-compatible placement
and separately names lifecycle callbacks, neighbors, comparators, and tile
handling instead of using an `ignorePhysics` boolean. Lighting/client delivery
remain explicit finalization boundaries: required live skylight state is updated,
but packets are not sent. The result reports changed blocks, touched/dense
sections, affected columns, tiles, biomes, section mask, memory estimate, and a
coarse full-packet density hint.

Failures are not caught. Invalid IDs/NBT, rejected native placement, wrong thread,
or finalization errors propagate, and a result exists only after finalization.
Stage 2 does not promise transactional rollback, so callers must treat a thrown
commit as potentially partial.

## Reference comparison

### Section representation and sparse/dense handling

* **Legacy FAWE:** `CharFaweChunk`/`ForgeChunk_All` eagerly combined sentinel
  `char[4096]`, byte IDs, and nibble arrays, then frequently scanned all 4096
  positions. This demonstrated the 1.7.10 LSB/MSB/nibble layout, but sentinel air
  and unconditional counters made repeated writes fragile.
* **Modern FAWE:** the supplied modern tree is primarily a newer WorldEdit Forge
  adapter and native-access layer; it does not include enough queue/chunk source
  to substantiate a current section container. Only its clear separation of
  native access responsibilities is adapted here; no unsupported representation
  claim is made.
* **Enhanced:** numeric legacy block IDs/data and explicit tile NBT remain the
  6.3.0 compatibility shape, including IDs above 255 in modded 1.7.10.
* **Overdrive:** primitive sparse arrays promote at 512 entries to a packed dense
  array. Explicit air is packed normally and absence alone means unchanged.

### Commit threading

* **Legacy FAWE:** queue code mixed computation, cached live chunks, mutation,
  synchronization, and error swallowing, making ownership difficult to audit.
* **Modern FAWE:** supplied native-access code presents live platform mutation as
  a distinct boundary, consistent with separating preparation from placement.
* **Enhanced:** its Forge adapter ultimately mutates the live Forge world and is
  the compatibility authority for native placement behavior.
* **Overdrive:** preparation has no `World`; commit requires `WorldServer`, checks
  the actual server thread, and never schedules or shares a global queue.

### Section bookkeeping

* **Legacy FAWE:** incremented change/air counters even on repeated writes and its
  merge path could install arrays without reliably restoring storage counters.
* **Modern FAWE:** the supplied subset does not expose modern section-counter
  maintenance, so no specific algorithm is attributed to it.
* **Enhanced:** normal Forge placement relies on vanilla section invariants.
* **Overdrive:** native writes retain vanilla bookkeeping; direct-touched sections
  use vanilla final recalculation and empty-section removal for correctness.

### Heightmaps and lighting

* **Legacy FAWE:** only raised height values and could not correct terrain removal;
  its separate relighter is not reused.
* **Modern FAWE:** the available native-access source shows lighting as an
  explicit side-effect concern, but the supplied subset is insufficient to claim
  its batching internals.
* **Enhanced:** Forge blocks may provide world/coordinate-dependent opacity, so
  simple non-air height is incompatible.
* **Overdrive:** tracks exact dirty columns and uses server-thread vanilla
  skylight/height regeneration for safe Stage 2 correctness, deferring batching.

### Tile lifecycle

* **Legacy FAWE:** removed changed tiles, then attempted to read NBT into whatever
  `getTileEntity()` returned; failures could be swallowed.
* **Modern FAWE:** supplied `TileEntityUtils`/`NBTConverter` sources emphasize
  explicit conversion/type-aware native tile handling, but target a newer API.
* **Enhanced:** legacy block/tile NBT and Forge world placement semantics require
  coordinates and tile type to match the final block.
* **Overdrive:** removes/invalidate old instances, creates through the final block,
  validates copied/normalized NBT type, installs via the world, and marks dirty.

### Memory accounting

* **Legacy FAWE:** per-operation arrays existed, but this 1.7.10 path exposes no
  trustworthy chunk-level byte estimate for backpressure.
* **Modern FAWE:** bounded queues are a useful architectural objective, while the
  supplied subset does not contain the allocator/queue implementation needed for
  exact comparison.
* **Enhanced:** adds no backend queue contract Overdrive should replace.
* **Overdrive:** every prepared chunk exposes a cheap estimate over allocated
  primitive capacities, optional biomes, fixed overhead, and copied tile NBT.

### Side effects

* **Legacy FAWE:** raw merging, lighting, tile work, and packets were coupled, and
  safety was often represented by broad fast/physics choices.
* **Modern FAWE:** supplied native access explicitly names side effects and
  separates native placement responsibilities.
* **Enhanced:** `setBlock(..., notifyAndLight)` demonstrates that compatible
  placement semantics matter to Forge/WorldEdit behavior.
* **Overdrive:** uses a policy object with named dimensions plus a conservative
  classifier; unknown states fall back to native placement. Packets are deferred.

### Failure behavior

* **Legacy FAWE:** broad `Throwable` catches logged and continued, allowing partial
  mutation to look complete.
* **Modern FAWE:** the supplied subset does not contain enough operation lifecycle
  code to document its exact failure/cancellation implementation.
* **Enhanced:** callers expect placement errors to remain observable rather than
  silently reporting success.
* **Overdrive:** catches nothing in commit, validates inputs, and constructs the
  result only after counters, tiles, biomes, height/light state, and dirty marking
  finish. Loops are section-bounded for future cancellation points.

## Intentionally rejected legacy behavior

No FAWE platform/queue/player/task hierarchy, injection, Bukkit abstraction,
global last-chunk cache, entity mutation, packet sender, or `NMSRelighter` is
ported. There is no `com.boydti.fawe` or project-owned `com.sk89q.worldedit`
production class. Stage 3 will add coordination, cancellation, backpressure,
history capture, packets, and refined lighting without changing this ownership
boundary.
