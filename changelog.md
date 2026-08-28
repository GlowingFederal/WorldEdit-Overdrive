# Changelog

Changes are listed oldest to newest.

## (abcc128 Fix Forge chunk commit correctness)

- Assigned live Forge chunk commits and queued lighting to the server thread.
- Recalculated vanilla section counters and affected height-map columns.
- Added explicit removal, construction, validation, dirtying, and optional Forge
  Multipart synchronization for changed tile entities.
- Added the Forge 1.7.10 manual regression checklist and documented deferred risks.

## (51f3513 Normalize self-contained WorldEdit packaging)

- Replaced snapshot WorldEdit core coordinates with one pinned 6.1.3 baseline.
- Embedded the WorldEdit core remainder and Forge 1.7.10 platform in the
  canonical KAWE Forge distribution while retaining local FAWE overrides.
- Added artifact checks for required WorldEdit/Forge/FAWE classes, duplicate
  entries, and accidental reference-source packaging.
- Corrected Forge metadata and documented ownership, coexistence, remaining
  dependencies, Enhanced follow-ups, and clean-server regression coverage.

## (88ec106 Restore legacy WorldEdit snapshot baseline)

- Restored `com.sk89q.worldedit:worldedit-core:6.1.3-SNAPSHOT` in every module
  changed by the packaging-normalization pass.
- Prioritized EngineHub's WorldEdit Maven repository ahead of legacy aggregate
  mirrors while preserving the self-contained Forge shadow packaging rules.
- Corrected the build and ownership documentation to identify the snapshot as
  an embedded build input rather than an external runtime dependency.

## (a5d10cc Layer KAWE on WorldEdit Enhanced 6.3.0)

- Replaced the legacy WorldEdit core and Forge dependencies with the external
  WorldEdit Enhanced 6.3.0 Curse Maven artifact.
- Removed WorldEdit shadow assembly and KAWE's competing Forge player wrapper.
- Required Enhanced's `worldedit` Forge mod and documented FalsePatternLib,
  source ownership, retained FAWE overrides, and `AbstractChunkUpdater`.

## (44d66ab Fix Forge 1.7.10 core output packaging)

- Added the complete compiled `:core` source-set output directly to the
  canonical Forge shadow jar instead of relying on project dependency filtering.
- Kept WorldEdit Enhanced and FalsePatternLib external while preserving KAWE's
  WorldEdit overrides, FAWE classes, Forge output, and private shaded libraries.
- Documented the assembled artifact topology and Enhanced integration boundary.

## (b20782e Establish WorldEdit Overdrive addon foundation)

- Replaced the active KAWE multi-module topology with one conventional root
  Forge 1.7.10 addon project.
- Added minimal `worldeditoverdrive` metadata and an owned entry point that
  verifies the WorldEdit Enhanced API at initialization without acceleration.
- Kept Enhanced external, retired Shadow and custom packaging from the active
  build, and documented the normal `build/libs` runtime artifact.

## (9c93864 Restrict compilation to Overdrive addon sources)

- Limited the active Java source set to the owned WorldEdit Overdrive package.
- Kept the legacy KAWE/FAWE Forge implementation on disk solely as excluded
  reference material for later porting.
- Updated the project and integration documentation to describe the minimal
  addon boundary and deferred acceleration work.
