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
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;
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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AccurateBlockPlacement extends JavaPlugin implements Listener {
	private FileConfiguration config;

	// cached config values - these are read on every block placement, so we avoid
	// re-parsing them out of the FileConfiguration each time (refreshed on reload).
	private volatile boolean debugEnabled;
	private volatile boolean candlesAirPlaceable;

	private final Map<UUID, PacketData> playerPacketDataHashMap = new ConcurrentHashMap<>();

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

		getLogger().info("PaperAccurateBlockPlacement loaded!");

		getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		playerPacketDataHashMap.clear();

		if (PacketEvents.getAPI() != null) {
			PacketEvents.getAPI().terminate();
		}
	}

	private void refreshConfigCache() {
		debugEnabled = config.getBoolean("debug", false);
		candlesAirPlaceable = config.getBoolean("air-placement.candles", false);
	}

	private boolean isAirPlaceableBlock(Material material) {
		// todo: consider other additional blocks to add
		return candlesAirPlaceable && Tag.CANDLES.isTagged(material);
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
			// Carpet "hello" handshake body: VarInt(version) + protocol identifier string.
			// The channel name ("carpet:hello") is a first-class field of the wrapper.
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(body);

			writeVarInt(dos, 69);
			writeMcString(dos, "PAPER-ABP");
			dos.flush();

			WrapperPlayServerPluginMessage packet =
					new WrapperPlayServerPluginMessage("carpet:hello", body.toByteArray());
			PacketEvents.getAPI().getPlayerManager().sendPacket(event.getPlayer(), packet);
		} catch (Exception e) {
			debug("Failed to send carpet hello packet to " + event.getPlayer().getName() + ": " + e.getMessage());
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		playerPacketDataHashMap.remove(event.getPlayer().getUniqueId());
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

		applyProtocolOrientation(event, protocolValue, blockData);

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
		applyProtocolOrientation(event, protocolValue, blockData);
		scheduleApply(block, blockData);
	}

	// Mutates blockData according to the Carpet protocol value: facing, chest merging,
	// stair shape, log axis, repeater delay, comparator/bisected mode. Pure orientation -
	// it does not gate on canPlace or schedule anything, so both the normal and air-placement
	// paths can share it.
	private void applyProtocolOrientation(BlockPlaceEvent event, int protocolValue, BlockData blockData) {
		Block block = event.getBlock();
		Block clickedBlock = event.getBlockAgainst();
		Player player = event.getPlayer();

		if (debugEnabled) {
			debug("accurateBlockProtocol: material=" + blockData.getMaterial() + " protocol=" + protocolValue
					+ " binary=" + Integer.toBinaryString(protocolValue));
		}

		if (blockData instanceof Directional directional) {
			int facingIndex = protocolValue & 0xF;
			BlockFace currentFacing = directional.getFacing();
			debug("Directional: facingIndex=" + facingIndex + " currentFacing=" + currentFacing);

			// handle directional block reversal
			// 6 for most blocks, >6 for stairs
			if (facingIndex == 6) {
				BlockFace newFacing = directional.getFacing().getOppositeFace();
				directional.setFacing(newFacing);
				debug("Reversed facing from " + currentFacing + " to " + newFacing);
			} else if (facingIndex <= 5) {
				BlockFace face = switch (facingIndex) {
					case 0 -> BlockFace.DOWN;
					case 1 -> BlockFace.UP;
					case 2 -> BlockFace.NORTH;
					case 3 -> BlockFace.SOUTH;
					case 4 -> BlockFace.WEST;
					case 5 -> BlockFace.EAST;
					default -> null;
				};
				Set<BlockFace> validFaces = directional.getFaces();
				debug("Trying to set facing to " + face + " valid="
						+ (face != null ? validFaces.contains(face) : "null"));

				if (face != null && validFaces.contains(face)) {
					directional.setFacing(face);
					debug("Set facing to " + face);

					// vert placement debug
					if (face == BlockFace.UP || face == BlockFace.DOWN) {
						debug("Set vertical facing: " + blockData.getMaterial() + " to " + face);
					}
				}
			} else if (blockData instanceof Stairs && facingIndex > 6) {
				// for higher indicie stairs try reversing
				BlockFace newFacing = directional.getFacing().getOppositeFace();
				directional.setFacing(newFacing);
				debug("Stairs special reverse: " + facingIndex + " from " + currentFacing + " to " + newFacing);
			}

			// chest merging
			if (blockData instanceof Chest chest) {
				chest.setType(Chest.Type.SINGLE);
				BlockFace left = rotateCW(chest.getFacing());

				if (!clickedBlock.equals(block) && clickedBlock.getBlockData() instanceof Chest clickChest
						&& clickChest.getMaterial() == chest.getMaterial()) {
					if (clickChest.getType() == Chest.Type.SINGLE && chest.getFacing() == clickChest.getFacing()) {
						BlockFace relation = block.getFace(clickedBlock);
						if (left == relation) {
							chest.setType(Chest.Type.LEFT);
						} else if (left.getOppositeFace() == relation) {
							chest.setType(Chest.Type.RIGHT);
						}
					}
				} else if (!player.isSneaking()) {
					BlockData leftBlock = block.getRelative(left).getBlockData();
					BlockData rightBlock = block.getRelative(left.getOppositeFace()).getBlockData();
					if (leftBlock instanceof Chest lc && lc.getMaterial() == chest.getMaterial()
							&& lc.getType() == Chest.Type.SINGLE && lc.getFacing() == chest.getFacing()) {
						chest.setType(Chest.Type.LEFT);
					} else if (rightBlock instanceof Chest rc && rc.getMaterial() == chest.getMaterial()
							&& rc.getType() == Chest.Type.SINGLE && rc.getFacing() == chest.getFacing()) {
						chest.setType(Chest.Type.RIGHT);
					}
				}
			} else if (blockData instanceof Stairs stairs) {
				stairs.setShape(handleStairs(block, stairs));
			}
		} else if (blockData instanceof Orientable orientable) {
			Set<Axis> validAxes = orientable.getAxes();
			Axis axis = switch (protocolValue % 3) {
				case 0 -> Axis.X;
				case 1 -> Axis.Y;
				case 2 -> Axis.Z;
				default -> null;
			};
			if (axis != null && validAxes.contains(axis)) {
				orientable.setAxis(axis);
				debug("Set axis to " + axis);
			}
		}

		// handle additional properties: stairs half toggle|repeater delay|comparator mode
		protocolValue &= 0xFFFFFFF0;
		if (protocolValue >= 16) {
			if (blockData instanceof Repeater repeater) {
				int delay = protocolValue / 16;
				if (delay >= repeater.getMinimumDelay() && delay <= repeater.getMaximumDelay()) {
					repeater.setDelay(delay);
					debug("Set repeater delay to " + delay);
				}
			} else if (protocolValue == 16) {
				if (blockData instanceof Comparator comparator) {
					comparator.setMode(Comparator.Mode.SUBTRACT);
					debug("Set comparator to subtract mode");
				} else if (blockData instanceof Bisected bisected) {
					bisected.setHalf(Bisected.Half.TOP);
					debug("Set bisected half to TOP");
				}
			}
		}
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

	private BlockFace rotateCW(BlockFace in) {
		return switch (in) {
			case NORTH -> BlockFace.EAST;
			case EAST -> BlockFace.SOUTH;
			case SOUTH -> BlockFace.WEST;
			case WEST -> BlockFace.NORTH;
			case NORTH_EAST -> BlockFace.SOUTH_EAST;
			case SOUTH_EAST -> BlockFace.SOUTH_WEST;
			case SOUTH_WEST -> BlockFace.NORTH_WEST;
			case NORTH_WEST -> BlockFace.NORTH_EAST;
			default -> in; // for UP, DOWN, SELF
		};
	}

	private Stairs.Shape handleStairs(Block block, Stairs stairs) {
		Bisected.Half half = stairs.getHalf();
		BlockFace backFace = stairs.getFacing();
		BlockFace frontFace = backFace.getOppositeFace();
		BlockFace rightFace = rotateCW(backFace);
		BlockFace leftFace = rightFace.getOppositeFace();

		// each neighbour's block data is fetched once via pattern matching
		Stairs backStairs = block.getRelative(backFace).getBlockData() instanceof Stairs s ? s : null;
		Stairs frontStairs = block.getRelative(frontFace).getBlockData() instanceof Stairs s ? s : null;
		Stairs leftStairs = block.getRelative(leftFace).getBlockData() instanceof Stairs s ? s : null;
		Stairs rightStairs = block.getRelative(rightFace).getBlockData() instanceof Stairs s ? s : null;

		if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == leftFace) &&
				!(rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace)) {
			return Stairs.Shape.OUTER_LEFT;
		} else if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == rightFace) &&
				!(leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace)) {
			return Stairs.Shape.OUTER_RIGHT;
		} else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == leftFace) &&
				!(leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace)) {
			return Stairs.Shape.INNER_LEFT;
		} else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == rightFace) &&
				!(rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace)) {
			return Stairs.Shape.INNER_RIGHT;
		} else {
			return Stairs.Shape.STRAIGHT;
		}
	}

	private void onBlockBuildPacket(final PacketReceiveEvent event) {
		try {
			WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
			Vector3f cursor = wrapper.getCursorPosition();

			// Unlike ProtocolLib (which exposes an absolute pos vector), PacketEvents gives the
			// raw block-relative cursor float directly - exactly what Carpet encodes into.
			float relativeX = cursor.getX();

			if (relativeX >= 2) {
				// tweakeroo sends (value * 2) + 2, so we reverse it
				int protocolValue = ((int) relativeX - 2) / 2; // PUT THE DIVISION BACK

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
			getLogger().warning("Error processing block placement packet: " + e.getMessage());
			if (debugEnabled) {
				e.printStackTrace();
			}
		}
	}

	private void onCustomPayload(final PacketReceiveEvent event) {
		try {
			WrapperPlayClientPluginMessage in = new WrapperPlayClientPluginMessage(event);
			// only answer the carpet handshake channel
			if ("carpet:hello".equals(in.getChannelName())) {
				sendCarpetRules(event.getPlayer());
			}
		} catch (Exception ignored) { // honestly don't mind ignoring this one
		}
	}

	private void sendCarpetRules(Player player) {
		try {
			// Carpet rules body: VarInt(count) + a named NBT compound of string rules.
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(body);

			writeVarInt(dos, 1);
			writeNamedStringCompound(dos, "Rules", List.of(
					new String[] { "Value", "true" },
					new String[] { "Manager", "carpet" },
					new String[] { "Rule", "accurateBlockPlacement" }));
			dos.flush();

			WrapperPlayServerPluginMessage rulePacket =
					new WrapperPlayServerPluginMessage("carpet:hello", body.toByteArray());
			PacketEvents.getAPI().getPlayerManager().sendPacket(player, rulePacket);
		} catch (Exception e) {
			debug("Failed to send carpet rules: " + e.getMessage());
		}
	}

	// --- payload serialization helpers (replaces ProtocolLib's StreamSerializer/NBT) ---

	// Minecraft VarInt: 7 data bits per byte, MSB as continuation flag.
	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	// Minecraft protocol string: VarInt(byte length) + UTF-8 bytes.
	private static void writeMcString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	// Named NBT compound of string tags, byte-identical to ProtocolLib's serializeCompound output.
	// DataOutputStream.writeUTF emits exactly an NBT string (unsigned-short length + modified UTF-8).
	private static void writeNamedStringCompound(DataOutputStream out, String name, List<String[]> entries)
			throws IOException {
		out.writeByte(10); // TAG_Compound
		out.writeUTF(name); // root compound name
		for (String[] entry : entries) {
			out.writeByte(8); // TAG_String
			out.writeUTF(entry[0]); // key
			out.writeUTF(entry[1]); // value
		}
		out.writeByte(0); // TAG_End
	}
}
