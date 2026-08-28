# WorldEdit Overdrive

WorldEdit Overdrive is a Forge 1.7.10 addon for **WorldEdit Enhanced 6.3.0**.
This repository is currently at its addon-foundation stage: it verifies the
WorldEdit API dependency but does not yet provide FAWE/KAWE acceleration.

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

The root `src/main/java` and `src/main/resources` directories form the only
active Gradle project. The `core`, `bukkit`, `forge1710`, `favs`, and
`ReferenceSRC` directories are retained solely as legacy migration reference;
none of their sources are compiled by the active build.
