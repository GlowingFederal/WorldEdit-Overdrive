# WorldEdit Enhanced 6.3.0 integration and ownership

KAWE is an acceleration layer for WorldEdit Enhanced 6.3.0. Enhanced is the
authoritative WorldEdit implementation and Forge platform; KAWE is not a
standalone WorldEdit distribution.

## Dependencies and packaging

The build uses the verified Enhanced artifact
`curse.maven:worldedit-legacy-enhanced-1135144:5879351` (file
`worldedit-mc1.7.10-6.3.0.jar`). The Curse Maven repository is declared as a
plain Maven repository because ForgeGradle 1.2 has no `fg.deobf` API.

The old `worldedit-core:6.1.3-SNAPSHOT` and published
`worldedit-forge-mc1.7.10` dependencies are removed. The Forge shadow task has
no WorldEdit includes, implementation allow-list, or duplicate-ordering rule.
It adds `project(':core').sourceSets.main.output` directly to the canonical jar,
alongside the Forge source-set output and private shaded libraries. Direct output
inclusion is required because the legacy Shadow plugin's dependency filter does
not reliably expand Gradle project output: `compile project(':core')` makes those
classes available while compiling but does not itself put them in the shadow
archive. This includes every KAWE-owned `com.boydti.fawe` class and every retained
`com.sk89q.worldedit` override/addition without nesting the core jar or copying
Enhanced wholesale. `ReferenceSRC` is not part of any source set and is never
compiled or packaged.

Enhanced requires and directly uses FalsePatternLib. KAWE does not call an FPL
API, so it deliberately does not duplicate Enhanced's FPL dependency metadata
or embed FPL. Development and runtime installations must provide FPL as required
by Enhanced.

Enhanced's source declares Forge mod ID **`worldedit`**. KAWE declares
`required-after:worldedit`, so a missing Enhanced installation produces Forge's
normal missing-required-mod error before KAWE initializes.

## Source ownership audit

The audit compared all 174 Java sources under
`core/src/main/java/com/sk89q/worldedit` by relative path with the supplied
Enhanced core and Forge sources. It also inspected imports and call sites from
`com.boydti.fawe` to distinguish replacement hooks from standalone additions.

* **Unchanged legacy copies:** none were byte-identical to the Enhanced 6.3.0
  source. No unmodified class is intentionally retained merely to fill out a
  WorldEdit jar.
* **FAWE-modified replacements (134):** the retained conflicts cover FAWE's
  edit/session pipeline, extents, parsers and commands, operations and visitors,
  masks and patterns, clipboards, history/entity changes, regions/selectors,
  expression execution, and supporting vector/block APIs. In particular this
  includes `EditSession`, `LocalSession`, `CommandManager`, `PlatformManager`,
  `Extent`, `AbstractDelegateExtent`, `ForwardExtentCopy`, `Operations`,
  `RegionVisitor`, `FlatRegionVisitor`, `BlockArrayClipboard`, and
  `EditSessionEvent`. These implementations contain FAWE entry points used by
  the queue, limits, cancellation, history, and asynchronous execution code;
  replacing them wholesale with Enhanced versions would disable acceleration.
* **FAWE-specific additions (40):** these have no Enhanced counterpart. They
  include `AbstractChunkUpdater`, `SimpleWorld`, mutable/immutable fast block
  and vector types, FAWE events, delegate clipboard/session helpers, extra
  operation/visitor/mask/pattern types, command processing helpers, and
  `RoundedTransform`.
* **Forge platform replacements:** the sole project-owned class in the
  `com.sk89q.worldedit.forge` tree was `ForgePlayer`; it has been removed.
  Enhanced now owns bootstrap, world/player wrappers, configuration, commands,
  permissions, CUI/networking, NBT and tile/entity handling, and its Forge
  Multipart, ArchitectureCraft, and Carpenter's Blocks integrations.

This is intentionally a narrow collision surface, not a second complete
implementation. A class-name conflict was retained only where the current FAWE
code calls behavior added by that replacement. Enhanced remains authoritative
for every non-overridden WorldEdit class and for all Forge platform classes.

## `AbstractChunkUpdater`

Enhanced 6.3.0 has no `com.sk89q.worldedit.world.AbstractChunkUpdater` or an
equivalent queue-to-chunk update contract. It is a FAWE-specific three-argument
abstraction used by FAWE's modified `CommandManager` and implemented by
`ForgeChunkUpdater`. KAWE therefore retains it, including the FAWE queue/chunk
types in its signature. This resolves the former `NoClassDefFoundError` without
expecting Enhanced to provide a class from the abandoned partial fork.

## Compatibility boundary

KAWE's Forge chunk-writing layer remains responsible for buffered writes,
server-thread commits, section reference counts, affected heightmaps, and tile
lifecycle updates. Above that boundary, Enhanced owns NBT conversion, platform
adapters, compatibility integrations, entity handling, CUI, and network
synchronization. Removing the old `ForgePlayer` replacement also prevents KAWE
from restoring older CUI and player-wrapper behavior over Enhanced's fixes.

The retained override sources predate Enhanced and necessarily remain a binary
compatibility surface. The supplied 6.3.0 source comparison found no Enhanced
equivalent for the FAWE-only updater contract; the remaining conflicts use the
same legacy WorldEdit package/API shapes consumed by KAWE. Runtime regression
testing is still required for third-party command/parser extensions and unusual
modded blocks. Follow the existing Forge correctness checklist for live-world
validation.
