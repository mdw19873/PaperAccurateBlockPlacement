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

## Configuration advice for Clients

### For Litematica
1. Set `easyPlaceProtocolVersion` to **"Version 2"**
2. Enable easyPlace mode
3. Build with easyPlace like normal

### For Tweakeroo
1. Set `carpetAccuratePlacementProtocol` to **"true"**
2. Enable and use Flexible Block Placement

## License
The original project had no license. This fork's modifications are released under MIT License for the community benefit.
