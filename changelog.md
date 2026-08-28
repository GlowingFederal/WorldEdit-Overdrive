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
