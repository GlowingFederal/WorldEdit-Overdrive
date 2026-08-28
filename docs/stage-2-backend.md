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
but packets are not sent. The result reports changed blocks, touched/dense sections, affected columns,
tiles, biomes, changed and light-dirty section masks, raw/native application
counts, dirty flags, memory estimate, and a coarse full-packet density hint.

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
* **Modern FAWE:** `CharBlocks`/`CharSetBlocks` lazily allocate a full
  `char[4096]` for a written section. Reserved ordinal zero means unchanged,
  ordinal one represents air, and `hasSection` plus a chunk bit mask describe
  occupancy. `BitSetBlocks` adds a 4096-bit presence mask for efficient sparse
  traversal while retaining the dense ordinal array.
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
* **Modern FAWE:** `IChunkSet.getBitMask()` and `CharSetBlocks.getBitMask()`
  carry section occupancy to native application; storage counters themselves are
  platform responsibilities and are not implemented by this core-only tree.
* **Enhanced:** normal Forge placement relies on vanilla section invariants.
* **Overdrive:** native writes retain vanilla bookkeeping; direct-touched sections
  use vanilla final recalculation and empty-section removal for correctness.

### Heightmaps and lighting

* **Legacy FAWE:** only raised height values and could not correct terrain removal;
  its separate relighter is not reused.
* **Modern FAWE:** `CharSetBlocks` carries optional per-section block-light and
  skylight arrays and typed heightmaps; `RelightProcessor` and `NMSRelighter`
  separate deferred lighting from set application and consume section masks.
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
* **Modern FAWE:** `CharSetBlocks` lazily allocates sections, biomes, lighting,
  heightmaps, tiles, and entity collections, and recycles reset containers through
  a configured pool. `trim` can discard empty allocations. Its newer-state and
  pooled lifetime model is not safe to transplant into an operation-owned 1.7.10
  buffer, but it confirms accounting must follow capacities and optional data.
* **Enhanced:** adds no backend queue contract Overdrive should replace.
* **Overdrive:** every prepared chunk exposes a cheap estimate over allocated
  primitive capacities, optional biomes, fixed overhead, and copied tile NBT.

### Side effects

* **Legacy FAWE:** raw merging, lighting, tile work, and packets were coupled, and
  safety was often represented by broad fast/physics choices.
* **Modern FAWE:** `IChunkSet` carries a `SideEffectSet`; `IBatchProcessor` splits
  pre-application `processSet` from post-application processing, while
  `ChunkHolder` keeps existing `IChunkGet` and desired `IChunkSet` distinct.
* **Enhanced:** `setBlock(..., notifyAndLight)` demonstrates that compatible
  placement semantics matter to Forge/WorldEdit behavior.
* **Overdrive:** uses a policy object with named dimensions plus a conservative
  classifier; unknown states fall back to native placement. Packets are deferred.

### Failure behavior

* **Legacy FAWE:** broad `Throwable` catches logged and continued, allowing partial
  mutation to look complete.
* **Modern FAWE:** `ApplyTask.compute()` rethrows application failures, and
  `ChunkHolder.call()` sequences processing, native `IChunkGet.call`, finalization,
  and post-processing. Its futures/concurrency machinery is deliberately not
  ported; the useful finding is that failure must outlive every application stage.
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

## Modern FAWE core review (Stage 2 improvement pass)

### Concrete source reviewed

The review used these implementation files, rather than adapter documentation:

* Queue contracts and stages: `queue/IChunkGet.java`, `queue/IChunkSet.java`,
  `queue/IChunk.java`, `queue/IQueueExtent.java`, `queue/IBatchProcessor.java`,
  `queue/implementation/SingleThreadQueueExtent.java`,
  `queue/implementation/ApplyTask.java`, and
  `queue/implementation/chunk/ChunkHolder.java`.
* Storage and iteration: `queue/implementation/blocks/CharBlocks.java`,
  `CharSetBlocks.java`, `ThreadUnsafeCharBlocks.java`, `BitSetBlocks.java`,
  `CharGetBlocks.java`, and `extent/filter/block/CharFilterBlock.java`.
* Side effects and finalization: `extent/processor/BatchProcessorHolder.java`,
  `MultiBatchProcessor.java`, `heightmap/HeightmapProcessor.java`,
  `heightmap/HeightMapType.java`, `lighting/RelightProcessor.java`, and
  `lighting/NMSRelighter.java`.
* NBT and memory lifetime: `nbt/FaweCompoundTag.java`,
  `nbt/EagerFaweCompoundTag.java`, `nbt/LazyFaweCompoundTag.java`,
  `math/BlockVector3ChunkMap.java`, `queue/Pool.java`, and `FaweCache.java`.

All paths above are below
`ReferenceSRC/ModernFAWECoreSRC/worldedit-core/src/main/java/com/fastasyncworldedit/core/`.
The 1.7.10 comparison also rechecked
`ReferenceSRC/FAWE1710SRC/main/java/com/boydti/fawe/forge/v1710/ForgeChunk_All.java`
and `ForgeQueue_All.java`; Enhanced compatibility was checked against
`ReferenceSRC/WorldeditEnhancedSRC/main/java/com/sk89q/worldedit/forge/ForgeWorld.java`,
`TileEntityUtils.java`, and `NBTConverter.java`.

### Subsystem decisions made before code changes

| Subsystem | Overdrive baseline | Legacy / modern finding | Enhanced constraint | Decision |
|---|---|---|---|---|
| Sections | short/int sparse, dense int sentinel at 512 | Legacy eager arrays; modern lazy dense ordinal arrays and optional bitset | numeric ID/data and explicit air | **KEEP** |
| Packing | 12-bit ID + 4-bit metadata in int | Modern char ordinals require a newer global state registry | preserve ID 4095 and metadata 15 | **KEEP** |
| Read/set/result ownership | prepared set and result are separate; live reads occur only during commit | `ChunkHolder` explicitly separates `IChunkGet` and `IChunkSet` | live Forge state is server-thread-only | **KEEP**; snapshot is **DEFER** |
| Section dirty tracking | result mask, boxed set used internally for raw sections | modern passes integer bit masks | no compatibility impact | **MODIFY** boxed set to primitive mask |
| Side effects | policy plus conservative classifier and explicit finalization | modern carries `SideEffectSet` and processor stages | Enhanced placement semantics win | **KEEP**; processor graph **DEFER** |
| Tiles/NBT | copied NBT and explicit remove/create/validate/install | modern lazy/eager tags and chunk-normalized keyed map | native 1.7.10 tile creation/type rules | **KEEP** |
| Height/light | dirty columns plus full `generateSkylightMap()` | modern typed heightmaps and deferred relighter rely on platform support | modded contextual opacity and removals | **KEEP** |
| Memory | allocated primitive capacities plus approximate NBT | modern lazy allocations, trim, and pooling | operation ownership must remain obvious | **KEEP** |
| Allocation | primitive section arrays; boxed raw-section set | modern uses masks, flattened arrays, maps, and lifecycle-bound pools | no shared mutable state | **MODIFY** raw-section tracking only |
| Raw/native selection | explicit conservative capability classifier | modern delegates application to platform after processors | unknown/modded blocks must remain native | **KEEP** |
| Result handoff | counts, changed mask, packet hint | modern transports masks and distinct application stages | Stage 3 needs sync facts, not packet code | **MODIFY** add light mask, path counts, dirty flags |
| Failures | propagate; result only after finalization | `ApplyTask` rethrows and `ChunkHolder` orders finalizer/post-process | observable errors | **KEEP** |
| Entities | intentionally absent | modern set has add/remove collections and a removal processor | not required by completed Stage 2 | **DEFER** to a separately scoped stage |

No subsystem was classified **REPLACE**: the modern implementations confirm the
existing boundaries, but their newer registries, variable world height, platform
lighting, futures, and pooling are not drop-in 1.7.10 improvements.

### Representation, packing, and allocation conclusions

Modern FAWE uses a dense `char[4096]` ordinal section with a reserved unchanged
ordinal; air has a different ordinal. `BitSetBlocks` supplements this with 512
bytes of presence bits and bit scanning. Overdrive's dense `int[4096]` costs
16 KiB, while its sparse entries cost 6 bytes each excluding array headers. At
512 entries the sparse payload is about 3 KiB, well below dense storage, but the
promotion deliberately trades memory for bounded repeated-write lookup and
contiguous broad-edit iteration. A modern-style dense char array cannot preserve
legacy ID plus metadata without an operation/global palette, and adding a bitset
would increase tiny-edit memory while leaving the sparse O(n) write lookup.
Consequently 512 remains a conservative performance crossover, not a claimed
byte-equivalence point; it should move only with profiling.

The modern ordinal is registry-dependent and therefore was rejected. Overdrive's
16-bit logical payload remains in an `int` so `-1` is an unambiguous dense
unchanged sentinel, air remains packed zero, and all 4096 legacy IDs plus 16 data
values survive. Shrinking it to `char` would need a separate occupancy structure
and provides no clear win for sparse arrays because indices already consume a
`short`.

The concrete allocation improvement is replacing the commit-local
`HashSet<Integer>` of raw-touched sections with a 16-bit integer mask. This
removes boxing/hash nodes and changes counter finalization to one bounded,
cache-friendly section loop. Modern pooling was rejected: Stage 2 has no proven
hot reusable lifecycle, while pooling would weaken operation ownership and retain
large arrays. Tile lookup remains a small linear list rather than importing a
boxed modern coordinate map; an index should be added only if tile-heavy profiling
shows a real cost.

### Pipeline, effects, finalization, and result conclusions

`ChunkHolder`'s existing/get versus desired/set distinction matches Overdrive's
prepared write envelope and server-thread live read boundary. A full source
snapshot is unnecessary for the present writer and would add memory and stale-read
risk. The modern `IBatchProcessor` pre/post split is valuable for a coordinator,
but introducing its graph now would begin Stage 3. The current explicit order—
block application, raw-section bookkeeping, tiles, biomes, skylight/height,
dirty marking, result—already provides the required lifecycle separation.

Modern tile maps use chunk-normalized keys and `FaweCompoundTag` ownership wrappers.
Overdrive already copies at ingestion and extraction, normalizes absolute
coordinates only at application, validates the created tile type, removes old
instances, and validates/marks the replacement. Modern lazy NBT is rejected
because native 1.7.10 `NBTTagCompound` lacks the same immutable supplier contract.

Modern light arrays, typed heightmaps, relight masks, and deferred relighters
cannot establish correctness for Forge 1.7.10 modded contextual opacity, downward
height changes, or storage creation/removal. `generateSkylightMap()` therefore
remains. The result now exposes the changed sections as light-dirty when block
columns forced that finalization, plus raw/native application counts and tile/
biome dirty flags. These are synchronization facts Stage 3 can consume without
sending packets or claiming entity support. Failure behavior is unchanged: no
broad catch, and no result before finalization.
