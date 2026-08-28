# WorldEdit ownership and Forge 1.7.10 packaging

## Authoritative layout

The Forge distribution is a single runtime unit with three layers:

1. `core/src/main/java/com/sk89q/worldedit` contains the FAWE fork's overrides,
   including `EditSession`, the accelerated extent/visitor changes, and
   `AbstractChunkUpdater`.
2. The pinned WorldEdit `6.1.3-SNAPSHOT` core artifact supplies only baseline
   classes not overridden by the fork. This is the exact core baseline used by
   the legacy FAWE implementation and is resolved from EngineHub's WorldEdit
   repository. It is embedded in the distribution; it is not a server-side
   prerequisite.
3. The pinned official Forge 1.7.10 platform artifact supplies platform
   bootstrap, configuration, sessions, commands, permissions, CUI, and the
   world/entity/NBT adapters. It too is embedded. The local Forge player wins
   where the fork intentionally overrides the platform implementation.

Shadow input order and `DuplicatesStrategy.EXCLUDE` make repository output
authoritative instead of allowing ZIP/class-path order to choose an
implementation. `verifyWorldEditJar` additionally rejects duplicate entries.
Reference material below `ReferenceSRC` is not a source set and the verifier
rejects it if it ever enters the distribution.

## Previous failure

Previously Gradle compiled Forge against `:core`, `worldedit-core:6.1.3-SNAPSHOT`,
and the published Forge platform. The shadow allow-list embedded `:core` but
explicitly omitted both WorldEdit artifacts. Consequently compilation could
resolve the mixed API while the runtime jar lacked all non-local core and Forge
implementation classes. `AbstractChunkUpdater` itself resolves from the local
source file (it was not supplied by the Forge dependency); seeing
`NoClassDefFoundError` for it proves that a thin/non-canonical Forge jar or an
incomplete shadow output was installed. There was no artifact-level check to
prevent that output from being distributed.

The canonical build now embeds the baseline core and Forge platform and makes
the artifact verifier a dependency of both `check` and `build`. It specifically
requires `AbstractChunkUpdater`, `WorldEdit`, the Forge mod/platform/world/player
adapters, the KAWE mod container, and the Forge queue.

The root repository order consults `https://maven.enginehub.org/repo/` before
legacy aggregate mirrors so WorldEdit snapshot inputs do not depend on the
DestroyTokyo mirror. The coordinate remains a build input only: the canonical
Forge shadow jar contains the non-overridden core implementation and does not
require a separately installed WorldEdit jar.

## Dependencies and coexistence

No separately installed WorldEdit implementation is supported. The embedded
platform retains the `worldedit` Forge mod container; Forge 1.7.10 therefore
reports a duplicate mod ID and stops if an administrator also installs ordinary
WorldEdit or WorldEdit Enhanced. This fail-fast behavior is preferable to
loading duplicate `com.sk89q.worldedit` packages. KAWE metadata no longer lists
WorldEdit as an external dependency and explicitly describes the combined mod.

Non-WorldEdit libraries remain ordinary build/runtime inputs according to the
existing shadow policy: SnakeYAML, fastutil-lite, and zstd-jni are embedded;
Minecraft/Forge are provided by the server. SQLite remains declared but is not
currently included by the Forge shadow allow-list.

## Enhanced comparison

WorldEdit Enhanced remains reference-only. No `ReferenceSRC` file is compiled or
packaged. The preceding correctness pass already adapted the directly relevant
Forge Multipart tile synchronization and tile lifecycle behavior in the local
chunk writer. No additional Enhanced code was copied in this ownership pass.
Potential later work includes its ArchitectureCraft and Carpenter's transform
hooks, fuller multipart abstraction, platform initialization refinements, and
network synchronization; these should be evaluated separately rather than
mixed into packaging normalization.

## Manual clean-server regression checklist

1. Start Forge 1.7.10 with only the canonical KAWE Forge jar and confirm both
   KAWE and its embedded WorldEdit platform reach the server-started lifecycle.
2. Confirm `//wand`, selections, `//set`, `//replace`, undo, and redo work for an
   operator and that permission denial works for a non-operator.
3. Exercise copy/paste and schematic save/load, then restart and repeat to check
   configuration and session persistence.
4. Run edits across chunk/section boundaries and unloaded chunks; verify queue
   completion, lighting, heightmaps, block counts, tile creation/removal, client
   updates, and saved-world reload.
5. Exercise CUI with a compatible client and confirm vanilla clients can join.
6. Repeat with Forge Multipart and representative mod tiles. Check multipart
   client synchronization; separately test ArchitectureCraft and Carpenter's
   blocks before claiming transform compatibility.
7. Add a separate WorldEdit jar deliberately and confirm Forge stops with a
   duplicate `worldedit` diagnostic instead of starting an ambiguous runtime.
