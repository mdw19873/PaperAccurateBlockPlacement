package net.dungeondev.accurateblockplacement;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin entry point and transport adapter. Wires PacketEvents (the placement packet and the Carpet
 * handshake) and Bukkit events to the domain logic in {@link BlockPlacementProtocol} and
 * {@link CarpetPayloads}; it deliberately holds no protocol decoding of its own.
 */
public class AccurateBlockPlacement extends JavaPlugin implements Listener {
	private FileConfiguration config;

	// cached config values - these are read on every block placement, so we avoid
	// re-parsing them out of the FileConfiguration each time (refreshed on reload).
	private volatile boolean debugEnabled;
	private volatile boolean candlesAirPlaceable;

	private BlockPlacementProtocol protocol;

	private final Map<UUID, PacketData> playerPacketDataHashMap = new ConcurrentHashMap<>();

	// players we have already answered the carpet:hello handshake for, so a client cannot
	// spam the channel to make the server repeatedly build and send rule packets.
	private final Set<UUID> carpetGreeted = ConcurrentHashMap.newKeySet();

	@Override
	public void onLoad() {
		// PacketEvents must be set up and loaded as early as possible (onLoad),
		// then init()'d in onEnable. Listeners are registered after load().
		PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
		PacketEvents.getAPI().load();

		// single listener for both packet types - one dispatch per received packet
		PacketEvents.getAPI().getEventManager().registerListener(
				new PacketListenerAbstract(PacketListenerPriority.LOWEST) {
					@Override
					public void onPacketReceive(PacketReceiveEvent event) {
						PacketTypeCommon type = event.getPacketType();
						if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
							onBlockBuildPacket(event);
						} else if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
							onCustomPayload(event);
						}
					}
				});
	}

	@Override
	public void onEnable() {
		PacketEvents.getAPI().init();

		saveDefaultConfig();
		config = getConfig();
		refreshConfigCache();
		protocol = new BlockPlacementProtocol(this::debug);

		getLogger().info("PaperAccurateBlockPlacement loaded!");

		getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		playerPacketDataHashMap.clear();
		carpetGreeted.clear();

		if (PacketEvents.getAPI() != null) {
			PacketEvents.getAPI().terminate();
		}
	}

	private void refreshConfigCache() {
		debugEnabled = config.getBoolean("debug", false);
		candlesAirPlaceable = config.getBoolean("air-placement.candles", false);
	}

	private boolean isAirPlaceableBlock(Material material) {
		return candlesAirPlaceable && BlockPlacementProtocol.isAirPlaceable(material);
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
			@NotNull String[] args) {
		if (command.getName().equalsIgnoreCase("abp")) {
			if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
				if (!sender.hasPermission("accurateblockplacement.reload")) {
					sender.sendMessage("<red>You don't have permission to reload the config!</red>");
					return true;
				}

				reloadConfig();
				config = getConfig();
				refreshConfigCache();
				sender.sendMessage("<green>PaperAccurateBlockPlacement config reloaded</green>");
				return true;
			}

			sender.sendMessage("PaperAccurateBlockPlacement v" + getPluginMeta().getVersion());
			return true;
		}
		return false;
	}

	private void debug(String message) {
		if (debugEnabled) {
			getLogger().info("[DEBUG] " + message);
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		try {
			WrapperPlayServerPluginMessage packet = new WrapperPlayServerPluginMessage(
					CarpetPayloads.CHANNEL, CarpetPayloads.hello(69, "PAPER-ABP"));
			PacketEvents.getAPI().getPlayerManager().sendPacket(event.getPlayer(), packet);
		} catch (Exception e) {
			debug("Failed to send carpet hello packet to " + event.getPlayer().getName() + ": " + e.getMessage());
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		playerPacketDataHashMap.remove(uuid);
		carpetGreeted.remove(uuid);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBuildEvent(BlockPlaceEvent event) {
		Player player = event.getPlayer();
		PacketData packetData = playerPacketDataHashMap.get(player.getUniqueId());

		if (packetData == null) {
			return;
		}

		Vector3i packetBlock = packetData.block();
		Block block = event.getBlock();
		Block clickedBlock = event.getBlockAgainst();

		boolean positionMatches = (packetBlock.getX() == block.getX() && packetBlock.getY() == block.getY()
				&& packetBlock.getZ() == block.getZ()) ||
				(packetBlock.getX() == clickedBlock.getX() && packetBlock.getY() == clickedBlock.getY()
						&& packetBlock.getZ() == clickedBlock.getZ());

		if (!positionMatches) {
			if (debugEnabled) {
				debug("Position mismatch: packet=" + packetBlock + " placed=" + block.getLocation() + " clicked="
						+ clickedBlock.getLocation());
			}
			playerPacketDataHashMap.remove(player.getUniqueId());
			return;
		}

		if (isAirPlaceableBlock(block.getType())) {
			handleAirPlacement(event, packetData.protocolValue());
			playerPacketDataHashMap.remove(player.getUniqueId());
			return;
		}

		if (debugEnabled) {
			debug("Accurate placement: " + block.getType() + " protocol=" + packetData.protocolValue() + " at "
					+ block.getLocation() + " clicked: " + event.getBlockAgainst().getFace(block));
		}

		accurateBlockProtocol(event, packetData.protocolValue());
		playerPacketDataHashMap.remove(player.getUniqueId());
	}

	private void accurateBlockProtocol(BlockPlaceEvent event, int protocolValue) {
		Block block = event.getBlock();
		BlockData blockData = block.getBlockData();

		if (blockData instanceof Bed) {
			return; // let vanilla handle beds
		}

		protocol.apply(blockData, protocolValue, block, event.getBlockAgainst(), event.getPlayer().isSneaking());

		// validate and apply block data
		boolean canPlace = block.canPlace(blockData);
		if (!canPlace && debugEnabled && blockData instanceof Directional dir
				&& (dir.getFacing() == BlockFace.UP || dir.getFacing() == BlockFace.DOWN)) {
			debug("canPlace=false for vertical: " + blockData.getMaterial() + " facing " + dir.getFacing() + " at "
					+ block.getLocation());
		}

		if (canPlace) {
			scheduleApply(block, blockData);
		} else {
			event.setCancelled(true);
		}
	}

	// specifically for blocks you can't normally place without placing against
	// something i.e. candles
	private void handleAirPlacement(BlockPlaceEvent event, int protocolValue) {
		Block block = event.getBlock();
		BlockData blockData = block.getBlockData();

		// apply any orientation, then force the placement - air-placeable blocks
		// intentionally bypass the canPlace gate (that's the whole point).
		protocol.apply(blockData, protocolValue, block, event.getBlockAgainst(), event.getPlayer().isSneaking());
		scheduleApply(block, blockData);
	}

	// Applies the finished block data next tick to bypass Paper's placement validation.
	private void scheduleApply(Block block, BlockData blockData) {
		getServer().getScheduler().runTask(this, () -> {
			if (block.getType() == blockData.getMaterial()) {
				block.setBlockData(blockData, false);
				debug("Applied scheduled blockdata update");
			}
		});
	}

	private void onBlockBuildPacket(final PacketReceiveEvent event) {
		try {
			WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
			Vector3f cursor = wrapper.getCursorPosition();

			// Unlike ProtocolLib (which exposes an absolute pos vector), PacketEvents gives the
			// raw block-relative cursor float directly - exactly what Carpet encodes into.
			float relativeX = cursor.getX();

			if (BlockPlacementProtocol.carriesProtocol(relativeX)) {
				int protocolValue = BlockPlacementProtocol.decode(relativeX);

				UUID uuid = event.getUser().getUUID();
				if (uuid != null) {
					playerPacketDataHashMap.put(uuid, new PacketData(wrapper.getBlockPosition(), protocolValue));
				}

				// fix X to a valid in-block position (relative 0.5) and re-encode the packet
				wrapper.setCursorPosition(new Vector3f(0.5f, cursor.getY(), cursor.getZ()));
				event.markForReEncode(true);

				if (debugEnabled) {
					debug("Fixed X from " + relativeX + " to 0.5 (protocol=" + protocolValue + ")");
				}
			}
		} catch (Exception e) {
			// debug-level so a client cannot flood the log by sending malformed packets;
			// the packet is simply skipped either way
			debug("Error processing block placement packet: " + e.getMessage());
			if (debugEnabled) {
				e.printStackTrace();
			}
		}
	}

	private void onCustomPayload(final PacketReceiveEvent event) {
		try {
			WrapperPlayClientPluginMessage in = new WrapperPlayClientPluginMessage(event);
			if (!CarpetPayloads.CHANNEL.equals(in.getChannelName())) {
				return;
			}
			// answer the handshake only once per player; carpetGreeted.add() is true only the
			// first time, so a client spamming the channel cannot trigger repeated responses
			UUID uuid = event.getUser().getUUID();
			if (uuid != null && carpetGreeted.add(uuid)) {
				sendCarpetRules(event.getPlayer());
			}
		} catch (Exception ignored) { // honestly don't mind ignoring this one
		}
	}

	private void sendCarpetRules(Player player) {
		try {
			WrapperPlayServerPluginMessage rulePacket = new WrapperPlayServerPluginMessage(
					CarpetPayloads.CHANNEL,
					CarpetPayloads.rule("Rules", "true", "carpet", "accurateBlockPlacement"));
			PacketEvents.getAPI().getPlayerManager().sendPacket(player, rulePacket);
		} catch (Exception e) {
			debug("Failed to send carpet rules: " + e.getMessage());
		}
	}
}
