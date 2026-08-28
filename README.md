<p align="center">
  <img src="https://i.imgur.com/Fog5fDB.png">
</p>

---

# This is the legacy version of FAWE (1.7.10) Nicknamed: KAWE ;D . Built and Backported to work with 1.7.10 Forge (an alpha version from the original team)
> It is not maintained by the FAWE team. You can find the newer version of FAWE [here](https://github.com/IntellectualSites/FastAsyncWorldEdit).

FAWE is a fork of WorldEdit that has huge speed and memory improvements and considerably more features
It is available for Bukkit, Forge, Sponge and Nukkit.

## Links 

* [Spigot Page](https://www.spigotmc.org/threads/fast-async-worldedit.100104/)
* [Discord](https://discord.gg/ngZCzbU)
* [Wiki](https://github.com/boy0001/FastAsyncWorldedit/wiki)

## Developer Resources
* [Maven Repo](http://ci.athion.net/job/FastAsyncWorldEdit/ws/mvn/)
* [API Documentation](https://github.com/boy0001/FastAsyncWorldedit/wiki/API)

## Building
FAWE uses gradle to build

```
$ gradlew setupDecompWorkspace
$ gradlew build
```

The Forge artifact in `target/` is an acceleration add-on. A server must install
**WorldEdit Enhanced 6.3.0** (mod ID `worldedit`) and its required
**FalsePatternLib** dependency alongside KAWE. KAWE does not embed WorldEdit's
core or Forge platform. Do not install ordinary legacy WorldEdit beside Enhanced:
both implementations own the same packages and the same Forge mod ID.

The build consumes Enhanced from
`curse.maven:worldedit-legacy-enhanced-1135144:5879351` as a compile dependency.
Enhanced owns its FalsePatternLib runtime/development dependency declaration;
KAWE does not directly use FalsePatternLib APIs or package either mod. See
[`docs/worldedit-enhanced-integration.md`](docs/worldedit-enhanced-integration.md)
for the ownership audit and integration boundary.

## Forge 1.7.10 commit safety

Live Forge chunk commits and their queued relighting are owned by the server thread. The
queue remains chunk-buffered and tick-budgeted; only the final interaction with live
Minecraft state is serialized. CPU-only edit preparation may still run asynchronously.

The first correctness pass also recalculates vanilla section reference counts, affected
height-map columns, and tile-entity lifecycles during commit. Operators upgrading an
existing server should follow the checklist in
[`docs/forge-1710-correctness-checklist.md`](docs/forge-1710-correctness-checklist.md).

## Contributing
Have an idea for an optimization, or a cool feature?
 - I'll accept most PR's
 - Let me know what you've tested / what may need further testing
 - If you need any help, create a ticket or discuss on [Discord](https://discord.gg/ngZCzbU)
