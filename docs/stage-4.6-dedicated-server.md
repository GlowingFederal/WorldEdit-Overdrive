# Stage 4.6 dedicated-server compatibility and observability

## Diagnosis and logging

The missing Stage 4.5 summary was an Overdrive observability defect: it used the
generic static `FMLLog` path, had no stable category or proof point, and existed
only after the complete history/commit/synchronization loop. Consequently there
was no way to distinguish a stale deployed artifact from a skipped/failed
summary path. Stage 4.6 routes coremod, transformer, startup, fallback, operation,
and coordinator diagnostics through the `WorldEditOverdrive` Log4j category used
by Forge 1.7.10. Initialization emits exactly one
`[WorldEditOverdrive] Server diagnostics active` line. Immediately before a
successful summary, a snapshot-published proof line is emitted. The artifact can
be checked without launching it with
`unzip -p WorldEditOverdrive-1.0.0.jar com/glowingfederal/worldeditoverdrive/integration/Stage4SetBridge.class | strings | grep 'summary emission proof'`.

The terminal success line is:

`[WorldEditOverdrive] //set: selected=N changed=N chunks=N dense=N sparse=N raw=N native=N plan=Nms filter=Nms history=Nms commit=Nms light=Nms sync=Nms total=Nms peak=NMiB`

`commit` is the entire writer interval and `light` is a subset of it. Total is
wall time from before bounds/planning through synchronization; it never adds
commit and lighting. A failure publishes a failed latest snapshot and logs its
phase, available chunk, exception, and whether mutation started. It does not log
success, promise rollback, or fall through to Enhanced after mutation.

## Dedicated server diagnostics

`/overdrive status` is a permission-level-2 server command and therefore works
from the dedicated console and for operators. It reports Enhanced's FML version,
Overdrive version, all permanent hook flags/counters, last fallback, coordinator
state, worker count, both prepared-memory limits, and commit tick budget.
`/overdrive stats` renders the single volatile immutable latest terminal
`OverdriveEditSummary`, including packet counts and failure state. No operation
history is retained.

The mod annotation retains `acceptableRemoteVersions = "*"`. Command
registration uses `FMLServerStartingEvent`, logging is server-owned, and updates
remain ordinary Forge/vanilla server packets. Connecting clients do not install
Overdrive.

## Compatibility decisions

All decisions precede limit reservation, history, or mutation. Unknown behavior
returns `null` to the injected bridge and untouched Enhanced handles the entire
operation.

* `SingleBlockPattern` and its subclasses are accepted through its public fixed
  block contract. Enhanced 6.3's legacy parser produces this type. Random,
  weighted, coordinate/expression, clipboard, and unknown patterns remain
  fallback; no reflective plugin-pattern probing was added.
* Bounds are copied to immutable `CuboidBounds`. Enhanced ships the concrete
  `CuboidRegion` and no region subclass. Unknown plugin subclasses remain
  fallback because complete membership cannot be proved from min/max alone.
* Direct `EditSession` and subclasses which do not override any relevant public
  behavior are eligible. Relevant session behavior is classified SAFE,
  INCOMPATIBLE, or UNKNOWN. An override or failed inspection is UNKNOWN.
* Active masks and block bags are INCOMPATIBLE. Survival tool-use is
  INCOMPATIBLE. Fast mode is SAFE: Stage 2 RAW already owns the bounded reduced
  notification policy while retaining server synchronization.
* Enhanced's `MultiStageReorder` queue remains fallback. This intentionally
  keeps native, tile, and order-sensitive cases on Enhanced rather than making
  a speculative inert-fill exception.
* Only direct Enhanced `ForgeWorld` is accepted. Enhanced 6.3 supplies no known
  transparent Forge world wrapper to unwrap; arbitrary field recursion is
  forbidden.
* Stage 4.5 destination filtering and coordinate-normalized tile equality are
  unchanged. Only changed destinations produce history, writes, dirty columns,
  packets, and affected counts. Attempted selected volume still reserves the
  Enhanced limiter, and Stage 2 raw/native eligibility is unchanged.

## Dedicated runtime matrix

| Case | Stage 4.6 classification | Runtime verification |
|---|---|---|
| `//set stone` | ACCELERATED | Confirm proof plus one success summary |
| `//set air` | ACCELERATED | Include unchanged air and verify changed count |
| metadata block | ACCELERATED | Save/reload metadata |
| registered high-ID mod block (<=4095) | ACCELERATED | Save/reload; raw/native counts determine backend path |
| queue disabled | ACCELERATED | Baseline |
| queue enabled | FALLBACK | Confirm pre-mutation reason |
| fast mode | ACCELERATED | Verify lighting and watcher synchronization |
| block bag | FALLBACK | Confirm inventory remains Enhanced-owned |
| active mask | FALLBACK | Confirm Enhanced applies mask |
| behavior-equivalent EditSession subclass | ACCELERATED | Only when relevant methods are inherited |
| subclass overriding relevant behavior | FALLBACK | Inspector reports UNKNOWN |
| custom event extent | FALLBACK/UNSUPPORTED | Do not deploy acceleration unless its semantics are explicitly proven |

No `//replace`, paste, clipboard, general pattern, or other Stage 5 command is
introduced. Remaining restrictions are reorder, masks, block bags, survival tool
use, non-cuboids, unknown session/event behavior, world wrappers, and arbitrary
patterns. On the real server, compare the new `plan/filter`, `history`, `commit`,
`light`, and `sync` fields. Optimize the largest non-overlapping contributor;
given current architecture, history capture is the next likely target if it is
material, otherwise use commit minus its lighting subset. Do not optimize based
on `commit + light`, because that double-counts lighting.
