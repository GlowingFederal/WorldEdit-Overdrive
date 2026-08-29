# WorldEdit Enhanced 6.3.0 integration and ownership

WorldEdit Overdrive is currently a minimal Forge 1.7.10 addon for WorldEdit
Enhanced 6.3.0. Enhanced is the authoritative WorldEdit implementation;
Overdrive does not yet provide KAWE/FAWE acceleration or override WorldEdit.

## Active build boundary

The root project is the sole project included by `settings.gradle`. Its main
Java source set explicitly includes only
`com/glowingfederal/worldeditoverdrive/**`. The active class is the small
`WorldEditOverdrive` Forge entry point, which declares a required dependency on
the `worldedit` mod and logs the detected WorldEdit API version during Forge
initialization.

The legacy `core`, `bukkit`, `forge1710`, `favs`, and `ReferenceSRC` trees stay
in the repository only as future porting reference. In particular, none of the
old `forge1710/src/main/java/com/boydti/fawe/forge` queue, chunk, command,
player, metrics, or bootstrap implementations belongs to an active source set.

## Dependencies and packaging

The build uses the pinned WorldEdit Enhanced artifact
`curse.maven:worldedit-legacy-enhanced-1135144:5879351`
(`worldedit-mc1.7.10-6.3.0.jar`) as an external compile-time dependency. It is
not copied into the Overdrive JAR. Enhanced requires FalsePatternLib at runtime;
server installations must provide it alongside Enhanced and Overdrive.

The installable artifact is `build/libs/WorldEditOverdrive-1.0.0.jar`. It should
contain only the owned `com/glowingfederal/worldeditoverdrive` class tree and
`mcmod.info`; it must not contain `com/boydti/fawe`, project-owned
`com/sk89q/worldedit`, or dependency implementation classes.

## LaunchWrapper-safe frame computation

The `EditSession` transformer recomputes frames for the complete class, including
untouched methods. Frame hierarchy queries are answered from `.class` resource
headers exposed by LaunchWrapper rather than by loading classes. This avoids both
class initialization and a recursive transformation request while retaining the
actual WorldEdit inheritance graph; resolved headers are cached.

This matters in Enhanced 6.3.0's `fillXZ`: local 9 receives either
`RecursiveVisitor` or `DownwardVisitor`, and `DownwardVisitor` extends
`RecursiveVisitor`. The branch merge must therefore retain `RecursiveVisitor`,
which is the receiver required by the subsequent `visit` and `getAffected`
calls. Widening that local to `Object` produces invalid bytecode even though
Overdrive does not modify `fillXZ` itself.

Hierarchy metadata failure is fail-open. If every type needed for safe frame
emission cannot be read, Overdrive discards the attempted `EditSession` rewrite,
returns the original Enhanced bytes, and reports all associated command hooks as
unavailable.

## Deferred work

This baseline intentionally does not port Forge chunk writers, FAWE queues,
history, asynchronous editing, or WorldEdit overrides. Those features require
separate compatibility work against Enhanced after the clean addon can build
and start successfully.
