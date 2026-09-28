# WorldEditOverdrive

WorldEditOverdrive is a performance and scalability addon for **WorldEdit Enhanced
6.3.0 on Minecraft 1.7.10**. It spreads supported WorldEdit work across server ticks
so large edits can progress without running as one uninterrupted task on the
server thread.

It is designed for builders, map makers and administrators working with large maps
and modded 1.7.10 servers, where a normal synchronous paste can cause severe tick
stalls or make the server appear frozen.

## Large edits, usable servers

Overdrive processes supported edits in bounded steps and adjusts its pace to
available server headroom. It can use more time when the server is idle and backs
off when other game work becomes expensive.

**Large edits still take time.** The goal is to keep the server usable while they
finish, rather than block the server until the entire operation is done. Undo and
redo of accelerated edits follow the same paced approach.

## Installation and compatibility

The current build targets:

- Minecraft **1.7.10**.
- Forge **1.7.10-10.13.4.1614-1.7.10**, with Java **8**.
- **WorldEdit Enhanced 6.3.0**, the WorldEdit 6.3 distribution used by this build.

Install WorldEdit Enhanced and its runtime dependencies, including FalsePatternLib,
then place `WorldEditOverdrive-1.0.0.jar` in the server's `mods` folder. For
singleplayer, install it in the Forge instance running that world. Overdrive is
a separate addon; WorldEdit is not bundled in its JAR. Connecting clients do not
need Overdrive.

Use Enhanced as the installed WorldEdit implementation; do not install a second
WorldEdit distribution alongside it. Compatibility with every WorldEdit fork,
addon, custom clipboard or modded block implementation is not guaranteed.
Overdrive checks whether it can handle an operation before taking ownership.
Unsupported operation graphs or unavailable integration hooks may leave the
operation on normal WorldEdit behavior, with its usual synchronous costs.
**Fast-mode pastes currently use normal WorldEdit behavior.**

## What is accelerated?

Use your usual WorldEdit commands. Acceleration is automatic for supported paths;
there is no separate Overdrive paste command.

| Operation | Current behavior |
| --- | --- |
| `//paste` from a standard Enhanced clipboard, including a loaded schematic | Paced preparation and placement, with bounded working memory |
| `//undo` and `//redo` for accelerated paste history | Paced history reading and placement |
| Undo/redo involving only ordinary WorldEdit history | Normal WorldEdit behavior |
| `//set`, `//replace`, `//walls`, `//faces`, `//outline`, `//center`, `//overlay`, `//naturalize`, `//stack`, `//move` | Normal WorldEdit behavior; integration hooks do not currently accelerate these commands |
| Other commands, including `//line`, `//curve`, `//smooth`, `//deform`, `//hollow`, `//regen`, `//forest` | Normal WorldEdit behavior |

Support depends on the actual operation and session, not just its command name.
Loading a schematic and creating the source clipboard remain WorldEdit work;
Overdrive takes over the supported paste after the clipboard is available.

## Paste, undo and redo

Supported accelerated pastes retain normal WorldEdit masks, clipboard transforms,
ignore-air behavior (`//paste -a`), block metadata, tile data, entities and
placement dependencies. Original-position pasting (`//paste -o`) and selecting
the pasted region (`//paste -s`) keep their normal meaning. Standard Enhanced
paste does not copy biomes.

A large paste can spend time preparing before most blocks visibly appear.
Preparation progress and placement progress are separate: a prepared or submitted
block is not necessarily placed yet. Destination chunks can be loaded as the edit
advances, so the whole destination area does not need to be loaded in advance.

WorldEdit still owns your session history. A supported paste is remembered and
reports success after placement and finalization complete. Undo and redo replay
that history over ticks, including block and entity changes. A replay requested
while an earlier paste in the same session is running waits for it to finish.
History is temporary session history; it is not recovered across server restarts.

## Memory and large pastes

The size of a supported paste does not have to fit inside its working-memory
budget. Overdrive prepares and processes it in bounded portions, using temporary
streamed storage when needed. A paste much larger than the per-operation budget
can therefore progress without Overdrive allocating the entire job at once.

The current internal budgets are **64 MiB per operation** and **128 MiB shared**
across paste/replay work and retained history descriptors. When shared working
memory is busy, supported work waits for space instead of switching to a
synchronous paste. These are accounting limits for Overdrive's working data,
not limits on total server RAM: WorldEdit's existing clipboard, Minecraft chunks
and other mods use memory separately.

Temporary disk usage grows with the edit and its retained undo history. Keep
enough free space in the server's temporary storage. Disk failures or an individual
data payload too large to process within the budget can fail an operation;
bounded memory does not make edit size unlimited.

## Progress commands

These read-only commands are available to operators (permission level 2) and the
server console. They inspect progress; they do not start, undo or redo an edit.
Use them with a single slash in game, or without the slash in the console.

| Command | Useful for |
| --- | --- |
| `/overdrive status` | Checking detected versions, integration availability, fallback reasons and overall diagnostics |
| `/overdrive preparation` | Seeing whether the latest paste is still preparing, and checking memory or worker waits |
| `/overdrive placement` | Following actual block, tile and entity placement for the latest paste |
| `/overdrive pacing` | Understanding the latest pacing decision, server headroom and reasons work is waiting |
| `/overdrive history` | Checking the latest undo and redo separately |
| `/overdrive profiling` | Sampled downstream mutation costs and expensive block/region categories for the latest paste or replay |

`/overdrive paste`, `/overdrive undo` and `/overdrive redo` show combined progress
for that operation type. Each also accepts `preparation`, `placement` or `pacing`:

```text
/overdrive paste preparation
/overdrive paste placement
/overdrive paste pacing
/overdrive undo placement
/overdrive undo pacing
/overdrive redo placement
/overdrive redo pacing
```

Focused views describe the latest observed operation of that type, rather than
your own edit or a total of all active edits. `/overdrive pacing` follows the most
recently visited controller; use a scoped pacing command for paste, undo or redo.
In status output, `HOOKED` means an integration hook is present, not that the
command is accelerated. The `operationSupport` line distinguishes this from
`ACTIVE`; individual operations must still pass compatibility checks.

Mutation profiling samples roughly one placement in 64 by default. Operators can
use `/overdrive profiling full`, `sample` or `off` to change detailed sampling.
`/overdrive profiling native` disables the downstream optimizations for comparison;
`optimized` restores them. These controls apply until server restart. Use equivalent
fresh pastes for comparisons, and see the [mutation audit](docs/worldedit-enhanced-integration.md#downstream-world-mutation-audit)
for coverage and timing limits. Profiling controls do not start edits or change the
server pacing budget.

## Configuration

Forge creates `config/worldeditoverdrive.cfg`. Edit it with the server stopped,
then restart to apply changes. The public settings are in the `pacing` category:

| Setting | Default / range | Effect |
| --- | --- | --- |
| `serverBudgetMillis` | 30 ms / 1–40 ms | Maximum shared server-thread time per tick for paced preparation, paste, undo and redo work. Lower values leave more time for gameplay; higher values allow more edit work when headroom permits. |
| `safetyMarginMillis` | 5 ms / 1–25 ms | Minimum time reserved for server headroom. A larger margin is more conservative; Overdrive can increase its reserve automatically when other tick work varies. |

The budget is a ceiling, not time Overdrive always consumes. A busy server can
receive less work, or none that tick. One expensive block mutation, chunk load,
lighting update or mod callback cannot be interrupted mid-call and can exceed a
planned slice.

The per-operation and global memory budgets are **internal values in 1.0.0**;
there are no config-file settings to change them. Worker count is also automatic:
one to four workers, based on the available processors. Phase slice limits,
chunk-load caps and other pacing safeguards are internal. Values reported by
diagnostics are not necessarily configurable settings.

## Limitations

- Not every WorldEdit command or operation graph is accelerated.
- Extremely large edits still take time. Throughput depends on block behavior,
  chunk loading or generation, lighting, neighboring updates and other mods.
- Large edits can still cause client rendering or chunk-update FPS drops even
  while the server remains responsive.
- Pacing reduces uninterrupted server work; it cannot guarantee stall-free ticks
  when individual game or mod calls are expensive.

## Building and technical documentation

Use Java 8 and the checked-in Gradle wrapper:

```sh
./gradlew clean build
```

The installable artifact is `build/libs/WorldEditOverdrive-1.0.0.jar`.
Only the root project is built; the legacy module and reference-source trees are
not compiled or packaged.

For implementation details and integration boundaries, see the
[integration documentation](docs/worldedit-enhanced-integration.md) and the
[current paste and history design](docs/stage-5c-paste-foundation.md#current-streamed-paste-and-history-architecture).
