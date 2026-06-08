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
	// when true, decode placements with the EasyPlace V3 scheme instead of V2 (Carpet). The two wire
	// formats are indistinguishable per-packet, so this is a server-wide switch (see config.yml).
	private volatile boolean protocolV3;

	private BlockPlacementProtocol protocol;

	// Carpet handshake identity advertised on the carpet:hello channel. The body is never parsed
	// by Litematica (it flips isCarpetServer on the channel id alone), but real Carpet clients do
	// read it, so we keep a stable version/brand.
	private static final int CARPET_PROTOCOL_VERSION = 69;
	private static final String CARPET_BRAND = "PAPER-ABP";

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
		protocol = new BlockPlacementProtocol(this::debug, () -> debugEnabled);

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

		boolean wantV3 = "v3".equalsIgnoreCase(config.getString("easyplace-protocol", "v2"));
		if (wantV3 && !NmsStateModel.isAvailable()) {
			getLogger().warning("easyplace-protocol is set to v3 but the NMS block-state handles could "
					+ "not be resolved on this server; falling back to v2.");
			wantV3 = false;
		}
		protocolV3 = wantV3;
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
		// In V3 mode we must NOT advertise carpet:hello, or AUTO-mode Litematica clients would resolve
		// to V2 and send V2-encoded packets that the V3 decoder mis-reads. V3 users select it manually.
		if (protocolV3) {
			return;
		}
		sendCarpetHello(event.getPlayer());
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
			if (packetData.v3()) {
				handleAirPlacementV3(event, packetData.protocolValue());
			} else {
				handleAirPlacement(event, packetData.protocolValue());
			}
			playerPacketDataHashMap.remove(player.getUniqueId());
			return;
		}

		if (debugEnabled) {
			debug("Accurate placement: " + block.getType() + " protocol=" + packetData.protocolValue() + " v3="
					+ packetData.v3() + " at " + block.getLocation() + " clicked: "
					+ event.getBlockAgainst().getFace(block));
		}

		if (packetData.v3()) {
			accurateBlockProtocolV3(event, packetData.protocolValue());
		} else {
			accurateBlockProtocol(event, packetData.protocolValue());
		}
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

	// EasyPlace V3: generic state-property orientation via the NMS-backed model. No per-block
	// special-casing is needed - V3 transmits chest type, stair shape, etc. explicitly.
	private void accurateBlockProtocolV3(BlockPlaceEvent event, int protocolValue) {
		Block block = event.getBlock();
		if (block.getBlockData() instanceof Bed) {
			return; // let vanilla handle beds (two-block placement)
		}

		NmsStateModel model = NmsStateModel.create(block, this::debug);
		if (model == null) {
			debug("V3 model unavailable for " + block.getType() + "; leaving vanilla placement");
			return;
		}

		protocol.applyV3(model, protocolValue, event.getPlayer().getFacing());
		BlockData blockData = model.result();
		if (blockData == null) {
			return;
		}

		if (block.canPlace(blockData)) {
			scheduleApply(block, blockData);
		} else {
			event.setCancelled(true);
		}
	}

	// V3 air placement (candles): apply orientation and force the placement, bypassing canPlace.
	private void handleAirPlacementV3(BlockPlaceEvent event, int protocolValue) {
		Block block = event.getBlock();
		NmsStateModel model = NmsStateModel.create(block, this::debug);
		if (model == null) {
			return;
		}
		protocol.applyV3(model, protocolValue, event.getPlayer().getFacing());
		BlockData blockData = model.result();
		if (blockData != null) {
			scheduleApply(block, blockData);
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

			// fold the threshold gate and the V2/V3 mode selection into one testable domain call
			BlockPlacementProtocol.DecodedPlacement decoded =
					BlockPlacementProtocol.decodePlacement(relativeX, protocolV3);
			if (decoded != null) {
				UUID uuid = event.getUser().getUUID();
				if (uuid != null) {
					playerPacketDataHashMap.put(uuid,
							new PacketData(wrapper.getBlockPosition(), decoded.protocolValue(), decoded.v3()));
				}

				// fix X to a valid in-block position (relative 0.5) and re-encode the packet
				wrapper.setCursorPosition(new Vector3f(0.5f, cursor.getY(), cursor.getZ()));
				event.markForReEncode(true);

				if (debugEnabled) {
					debug("Fixed X from " + relativeX + " to 0.5 (protocol=" + decoded.protocolValue()
							+ ", v3=" + decoded.v3() + ")");
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
		// V3 mode does not speak the Carpet handshake (see onPlayerJoin), so bail before allocating a
		// wrapper to read the channel - this runs for every plugin-message packet.
		if (protocolV3) {
			return;
		}
		try {
			WrapperPlayClientPluginMessage in = new WrapperPlayClientPluginMessage(event);
			if (!CarpetPayloads.CHANNEL.equals(in.getChannelName())) {
				return;
			}
			// answer the handshake only once per player; carpetGreeted.add() is true only the
			// first time, so a client spamming the channel cannot trigger repeated responses
			UUID uuid = event.getUser().getUUID();
			if (uuid != null && carpetGreeted.add(uuid)) {
				// Mirror genuine Carpet: answer the client's hello on the same channel before the
				// rules, so AUTO-mode Litematica detects the server even if the proactive join-time
				// hello raced the client's channel registration.
				sendCarpetHello(event.getPlayer());
				sendCarpetRules(event.getPlayer());
			}
		} catch (Exception ignored) { // honestly don't mind ignoring this one
		}
	}

	private void sendCarpetHello(Player player) {
		try {
			WrapperPlayServerPluginMessage packet = new WrapperPlayServerPluginMessage(
					CarpetPayloads.CHANNEL, CarpetPayloads.hello(CARPET_PROTOCOL_VERSION, CARPET_BRAND));
			PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
		} catch (Exception e) {
			debug("Failed to send carpet hello packet to " + player.getName() + ": " + e.getMessage());
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
