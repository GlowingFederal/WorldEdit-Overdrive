# Forge 1.7.10 correctness regression checklist

Run each terrain or tile case, inspect it before and after relogging, then save/restart
the server and inspect it again.

- [ ] Dense `//set` across several chunks.
- [ ] Sparse `//replace` in an existing chunk.
- [ ] Create blocks in a previously empty chunk section.
- [ ] Completely clear a chunk section and confirm it remains empty after reload.
- [ ] Place blocks with numeric IDs above 255.
- [ ] Confirm metadata is preserved for unchanged and newly written blocks.
- [ ] Place a tower, remove its upper half, and confirm the reported surface lowers.
- [ ] Put transparent blocks and leaves at the terrain top; include a mod block with
      unusual light opacity.
- [ ] Place random-ticking blocks and confirm random ticks continue after save/reload.
- [ ] Place and use chests, furnaces, and signs with NBT.
- [ ] Exercise tile-to-air, tile-to-normal, normal-to-tile, and tile-to-different-tile
      replacements.
- [ ] Change metadata on the same tile-capable block.
- [ ] Undo a tile edit and verify its original NBT is restored.
- [ ] Repeat the important section, height, and tile cases after save/reload.
- [ ] Edit across loaded and unloaded chunk boundaries.
- [ ] Start two simultaneous large edits and verify their commits remain serialized.
- [ ] Shut the server down while work remains queued; verify failures are visible and
      no queued chunk is falsely reported complete.

## Intentionally deferred risks

This pass does not introduce immutable world snapshots. Command evaluation, masks,
patterns, extents, and history capture can still perform world-sensitive reads while
an edit is being prepared asynchronously. Third-party mod callbacks invoked during
those reads may therefore retain thread-affinity risks. A later architecture pass
should separate snapshot acquisition, CPU-only transformation, and server-thread
commit. Full transactional rollback and a general tile packet redesign are also
deferred; this pass retains batched chunk packets and adds only Forge Multipart's
description packet where that optional mod is present.

## Performance note

Forge commits no longer fan live chunks out to the shared worker pool. They still use
direct section arrays and the TPS-aware chunk queue. Each touched section performs one
4096-block vanilla counter scan, and only touched height-map columns are rescanned from
the top of the world. Tile lifecycle work is limited to changed positions.
