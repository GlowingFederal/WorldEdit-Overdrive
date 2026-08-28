# Stage 5A phased operation engine

Stage 5A introduces one coordinator-owned `OperationPlan`. A plan identifies a
backend operation kind (not a command), source volume, semantic and side-effect
policy, preparation class, ordered `CommitPhase` list, operation-owned prepared
chunks, finalization intent, cancellation/first-failure state, and accounting.
`OperationState` remains the coarse lifecycle; reorder stages are not states.

Each phase declares `CHUNK` or `ORDERED_SEQUENCE`, a global barrier boundary,
strict encounter-order requirements, and chunk-batching permission.
`OperationPhaseProgress` records prepared/ready/committed units, active commits,
current and peak bytes, elapsed time, completion, and barrier waits. The
coordinator selects operations round-robin but only looks at each plan's current
phase. It advances the phase only after submissions and preparation close, its
ready queue drains, no commit or synchronization is active, and the phase
boundary is finalized. Thus later-phase partitions prepared in advance cannot
leak through a global barrier.

`PreparedOperationChunk` is the single memory owner for a chunk and partitions
the retained Stage 2 `PreparedChunkChange` representation by phase. It also has
ordered native/special placement slices. A slice is the safe time-slicing
boundary: callbacks within it are never interrupted, while round-robin may give
another operation a turn afterward. Producers of strict phases submit slices in
encounter order. Tile/effect/snapshot ownership remains an intentional future
seam; Stage 5A adds no destination snapshot, tile/entity implementation, block
table, or clipboard adapter.

Cancellation is observed before/during preparation and between scheduler units;
it discards accounted, uncommitted partitions without rollback. Failure retains
the first cause, marks the plan failed, releases every later uncommitted
partition, and prevents phase advancement. Already committed accounting remains.
All live work continues through `OverdriveCoordinator.tick()` and its existing
nanosecond budget. Chunk phases retain the existing writer, once-per-changed-
chunk lighting, and once-per-changed-chunk synchronization ownership.

`DependencyGraph` is operation-global and generic. Edges mean prerequisite to
dependent, so nodes may freely span chunk keys. Its sorted frontier is
deterministic; cycles either fail or are broken deterministically according to
an explicit policy. Stage 5B/5C will translate Enhanced attachment, door, and
rail rules into this graph; Stage 5A deliberately contains no block IDs.

## Remaining work

Stage 5B must add immutable destination snapshots, snapshot memory ownership,
worker-safe destination comparisons, and the separately reviewed history
representation. Stage 5C must recognize Enhanced clipboard operation graphs,
implement transforms/source-air semantics, translate exact reorder rules into
phases and dependencies, and add tile/entity/finalization payloads. Neither
clipboard paste nor history redesign is part of Stage 5A.
