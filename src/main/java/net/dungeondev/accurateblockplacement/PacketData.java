package net.dungeondev.accurateblockplacement;

import com.github.retrooper.packetevents.util.Vector3i;

/**
 * Decoded intent carried from the placement packet to the {@link org.bukkit.event.block.BlockPlaceEvent}.
 *
 * @param block         the block position from the packet
 * @param protocolValue the decoded protocol value (its bit layout depends on {@code v3})
 * @param v3            {@code true} if the value was decoded with the EasyPlace V3 scheme,
 *                      {@code false} for the V2 (Carpet) scheme
 */
public record PacketData(Vector3i block, int protocolValue, boolean v3)
{}
