# WorldEdit Overdrive

Enhanced 6.3.0 runtime acceleration now covers `//set`, `//paste`, `//replace`,
`//walls`, `//faces`/`//outline`, `//center`, `//stack`, `//move`, `//overlay`,
and `//naturalize`. `/overdrive status` derives each ACTIVE label from the exact
EditSession hook installation and exposes per-family bridge and completion counters.

The Stage 5A backend now includes a generalized, tick-budgeted phased operation
plan for future reorder-aware operations. See
[`docs/stage-5a-phased-engine.md`](docs/stage-5a-phased-engine.md). Clipboard
paste, destination snapshots, and history redesign remain future stages.

Stage 4.6 dedicated-server installation, diagnostics, compatibility decisions,
and the live verification matrix are documented in
[`docs/stage-4.6-dedicated-server.md`](docs/stage-4.6-dedicated-server.md).

The Stage 3 bounded execution/coordinator architecture is documented in
[`docs/stage-3-execution.md`](docs/stage-3-execution.md). It remains an internal
API and does not integrate WorldEdit commands or sessions.

WorldEdit Overdrive is a Forge 1.7.10 addon for **WorldEdit Enhanced 6.3.0**.
The addon now contains the Stage 2 operation-owned chunk buffer and Forge 1.7.10
commit engine. It is intentionally not connected to WorldEdit commands or
`EditSession` yet; see [the backend design](docs/stage-2-backend.md).

## Runtime installation

Install these mods on Forge `1.7.10-10.13.4.1614-1.7.10`:

1. WorldEdit Enhanced 6.3.0
2. FalsePatternLib (required by Enhanced)
3. WorldEdit Overdrive

Enhanced is the authoritative WorldEdit implementation. Do not install another
legacy WorldEdit distribution alongside it. Overdrive has its own
`worldeditoverdrive` mod ID and requires Enhanced's `worldedit` mod ID.

## Building

Use Java 8 and the checked-in Gradle wrapper:

```sh
./gradlew clean build
```

The normal installable artifact is
`build/libs/WorldEditOverdrive-1.0.0.jar`. WorldEdit Enhanced is a compile-time
dependency and is not embedded in this JAR.

## Repository layout

The root project is the only active Gradle project. Its Java source set has an
explicit `com/glowingfederal/worldeditoverdrive/**` include, so only owned addon
and backend classes are compiled. The `core`, `bukkit`, `forge1710`, `favs`, and
`ReferenceSRC` directories—including the old `com.boydti.fawe.forge` sources—are
retained solely as legacy migration reference and are not compiled or packaged.

## Stage 4 integration

The first constant-cuboid `//set` integration, its conservative eligibility/fallback contract, history model, and outstanding runtime validation are documented in [`docs/stage-4-constant-fill.md`](docs/stage-4-constant-fill.md).
The active hook is fail-open across unsupported Enhanced bytecode: `/overdrive status` reports ACTIVE only when the single, exact `SelectionCommand` completion boundary was patched, and otherwise reports INACTIVE plus `hookReason` while WorldEdit retains its native traversal.

## Architectural roadmap

The Stage 5 design gate for generalized hybrid WorldEdit operations and large
clipboard pastes is documented in
[`docs/stage-5-generalized-operation-plan.md`](docs/stage-5-generalized-operation-plan.md).
