# Stage 5B: immutable sources and primitive history

Stage 5B adds the two ownership boundaries needed by later generalized operations. It does not add paste or replace.

## Snapshot model and requested channels

`SnapshotRequirements` explicitly selects `BLOCK_STATE`, `TILE_NBT`, and/or `BIOME`. `ChunkSnapshotCapture` is a server-thread-only, one-chunk capture entry point. The published `ChunkSourceSnapshot` contains only copied primitives and NBT: it never retains a world, chunk, storage section, or tile entity.

Captures cover only the requested vertical section interval. A section is represented as all-air, homogeneous, or dense `char[]`; the char stores the exact Forge 1.7.10 `(numericId << 4) | metadata` value, including IDs 256–4095. Dense arrays retain 256 values per included Y layer rather than allocating the complete build height. Biomes are copied only on request. Tile NBT is deep-copied, normalized to chunk-local x/z, keyed by packed local position, and returned to callers as a fresh copy.

Every snapshot reports conservative estimated bytes. The intended coordinator lifecycle is capture on a budgeted server tick, publish, worker consumption, then immediate release. Stage 5A preparation classes already route `PURE`, `SNAPSHOT_REQUIRED`, and `SERVER_THREAD_REQUIRED`. Coordinator admission must reserve snapshot bytes before capture; a rejected reservation pauses capture while prepared consumers drain. The synchronous Stage 4 hook does not wait for future ticks and therefore retains bounded direct server-thread capture.

## Primitive history and Enhanced ownership

`OverdriveChangeSet` is a `FaweChangeSet`, so the accelerated edit installs it on the existing `EditSession`; the normal `LocalSession.remember(EditSession)` path continues to own undo and redo. Recording uses growable primitive arrays: packed x/z, byte y, and unsigned-char before/after legacy states. It allocates no `BlockVector`, `BaseBlock`, or `BlockChange` per record. The Enhanced interface adapter creates one `BlockChange` only when its lazy forward (redo) or backward (undo) iterator advances.

Tile before/after `CompoundTag` values are separate optional channels associated with a primitive block record and are supplied to the lazily created before/after blocks. Unchanged state/tile pairs are omitted. Attempted limit reservation remains independent of actual changed and history record counts.

Prepared entries are not visible through `size()` or either iterator. After each successful chunk write, only the successful count is added to the committed prefix. Failure seals and discards the uncommitted tail; this is partial-operation undo ownership, not rollback. Ordered sequences use the same rule at each successful placement boundary. Cancellation before mutation therefore exposes zero entries.

The first encoding is deliberately simple `PACKED_RAW`, a good bounded fallback for high-entropy 1.7.10 states. Uniform snapshot sections avoid redundant source storage; palette/RLE history segments are deferred until measurements justify their complexity. History has a hard memory limit and reports estimated bytes independently. Segmented checksummed spill is not implemented in 5B, `spillBytes()` is explicitly zero, and arbitrary-sized history is not claimed. A future implementation can add append-only per-operation segments behind the record/iterator boundary without changing Enhanced ownership.

## Constant set migration and diagnostics

Constant `//set` now installs primitive Overdrive history, records old state on the server thread before mutation, establishes ownership after each successful chunk commit, and seals committed history on completion or failure. Existing attempted-position limit reservation, changed filtering, writer behavior, synchronization, hook counters, fallback diagnostics, status, and stats command behavior remain intact.

Snapshot bytes/capture time, history bytes/encoding/recording time, spill bytes, and committed entries are model-level metrics ready for coordinator and summary aggregation. The existing summary already reports history time; wiring all model counters into `/overdrive stats` across future-tick plans remains before production Stage 5C.

## Verification boundary and remaining Stage 5C work

Unit coverage exercises air, homogeneous and dense sections, metadata, high IDs, negative chunks, partial ranges, immutability, estimated memory, unchanged omission, memory limits, lazy order, and committed-prefix behavior. Tile capture requires a Forge world fixture and Enhanced session ownership/undo/redo requires the dedicated live server matrix. Runtime validation must compare affected counts, limiter behavior, tile restoration, metadata and high IDs against Enhanced.

Before clipboard paste, finish coordinator reservation/release plumbing for snapshot and history categories, expose all new counters in the command snapshot, add checked segmented spill or explicitly constrain configured paste size, and run the live Enhanced differential matrix. Paste must then request its precise source/destination channels and must never let worker preparation touch a live extent, chunk, or tile. Clipboard paste itself is intentionally absent from Stage 5B.
