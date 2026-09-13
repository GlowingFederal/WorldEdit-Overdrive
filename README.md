# WorldEdit Overdrive

Enhanced 6.3.0 has exact runtime hooks for `//set`, `//paste`, `//replace`,
`//walls`, `//faces`/`//outline`, `//center`, `//stack`, `//move`, `//overlay`,
and `//naturalize`. Only the compatible constant `//set` path and exact standard
`PasteBuilder` graph currently have implemented acceleration owners. The remaining
hooks preserve Enhanced's native behavior and must not be interpreted as deferred,
tick-bounded acceleration. `/overdrive status` distinguishes installed hooks from
completed acceleration counters.

The Stage 5 backend includes a generalized phased plan, immutable snapshot/history
foundations, and a shared coordinator used by deferred paste. See
[`docs/stage-5a-phased-engine.md`](docs/stage-5a-phased-engine.md) and
[`docs/stage-5c-paste-foundation.md`](docs/stage-5c-paste-foundation.md).

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

## Shared operation coordinator

All currently admitted asynchronous work uses one `OverdriveCoordinator`. The
coordinator owns the bounded preparation pool and queue, one adaptive deadline for the
entire server tick, round-robin retained-operation scheduling, global/per-operation
memory accounting, and a two-operation per-player admission cap. Deferred paste no
longer owns a worker pool, queue, budget, or memory counter. Reservations are acquired
before clipboard capture and are released on completion, rejection, failure,
cancellation, or server shutdown.

Operators can inspect admitted operation IDs, owners, phases, and retained bytes with
`/overdrive status`, and request safe pre-mutation cancellation with
`/overdrive cancel <id>`. Cancellation never reports command success. A paste already
committing cannot currently be cancelled because Enhanced exposes no proven safe way to
discard its retained reorder graph while publishing committed-prefix history.

The support matrix at the top of this file describes installed hooks, not a claim that
all commands are deferred. In this source revision, the shared retained-owner path is
wired only for the exact standard `PasteBuilder` graph described in the Stage 5C
documentation. `//copy`, schematic/structure loading, and the replace, geometry,
overlay, naturalize, stack, and move hooks do not yet have complete bounded adapters and
must not be reported as asynchronously accelerated. Unsupported graphs are rejected
before Overdrive mutation or left on Enhanced's original path only where that unchanged
native behavior is explicitly safe for the command boundary.
