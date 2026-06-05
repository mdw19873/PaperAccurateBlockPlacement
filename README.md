# PaperAccurateBlockPlacement

> A maintained fork of [DungeonDev's SpigotAccurateBlockPlacement](https://github.com/DungeonDev/SpigotAccurateBlockPlacement)

Check us out on [Modrinth :)](https://modrinth.com/plugin/paper-accurate-block-placement)

## Original Credit
Original implementation by DungeonDev. This fork maintains compatibility while adding support for newer Minecraft versions. 

## Plugin Features
Implements Carpet's Accurate Block Placement Protocol for Paper/Purpur-based servers.
Similarly adds support for FlexibleBlockPlacement from Tweakeroo and easyPlace from Litematica.

## Version Compatibility
- **Minecraft**: 1.21 through 26.1 (the full 1.21 line and newer)
- **Java**: 21 or higher
- **Server Software**: Paper/Purpur
- **PacketEvents**: 2.12.2 or higher

## Installation

### Prerequisites
1. **Java 21** or higher installed
2. **PacketEvents 2.12.2** or higher

### Building the Plugin
```
./gradlew clean build
```

The compiled JAR will be in `build/libs/`

### Testing
```
./gradlew test
```

See [TESTING.md](TESTING.md) for the testing stack, conventions, and how to run individual tests.

## EasyPlace protocol version (server `config.yml`)

The server-side `easyplace-protocol` option selects which Litematica EasyPlace protocol the
plugin decodes:

- **`v2`** (default) — Carpet's accurate block placement. Works with Litematica's
  `easyPlaceProtocol` set to **Version 2** or **Auto** (the server advertises itself on
  `carpet:hello`, so Auto resolves to V2), and with Tweakeroo's FlexibleBlockPlacement.
- **`v3`** — Litematica's newer, more complete protocol. It orients many more blocks
  (buttons, levers, grindstones, hoppers, crafters, rails, doors, note blocks, bells,
  cake, daylight detectors, …) that V2 cannot.

> **V3 is a server-wide switch.** The V2 and V3 wire formats are indistinguishable
> per-packet, so the server can only decode one at a time. In `v3` mode the plugin stops
> advertising `carpet:hello`, and **each client must set `easyPlaceProtocol` to
> "Version 3" manually** (Auto will not pick it without a Servux server). While `v3` is
> active, **Tweakeroo FlexibleBlockPlacement and Carpet/Auto clients will not orient
> correctly** — use `v2` mode for those. V3 requires a Mojang-mapped Paper/Purpur server
> (1.20.5+); if the server's block-state internals can't be resolved, the plugin logs a
> warning and falls back to `v2`.

Run `/abp reload` after changing the value.

## Configuration advice for Clients

### For Litematica (server in `v2` mode — default)
1. Set `easyPlaceProtocol` to **"Version 2"** (or leave it on **"Auto"** — the server
   announces itself on the `carpet:hello` channel, so Auto resolves to Version 2 here)
2. Enable easyPlace mode
3. Build with easyPlace like normal

### For Litematica (server in `v3` mode)
1. Set `easyPlaceProtocol` to **"Version 3"** (Auto will not select V3 here)
2. Enable easyPlace mode
3. Build with easyPlace like normal

### For Tweakeroo
1. Set `carpetAccuratePlacementProtocol` to **"true"**
2. Enable and use Flexible Block Placement (server must be in `v2` mode)

## License
The original project had no license. This fork's modifications are released under MIT License for the community benefit.
