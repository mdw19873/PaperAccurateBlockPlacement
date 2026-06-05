package net.dungeondev.accurateblockplacement;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decodes the Carpet accurate-placement protocol value and applies the resulting orientation to a
 * block's {@link BlockData}.
 *
 * <p>This class operates purely on Bukkit types - no PacketEvents, no plugin lifecycle - so the
 * decoding helpers can be unit-tested directly and the orientation logic can be exercised against
 * real {@link BlockData} under MockBukkit. The caller owns transport (reading the packet) and
 * commit policy (canPlace gating / scheduling).
 */
final class BlockPlacementProtocol {

	/**
	 * Carpet smuggles the protocol value through the click cursor's X coordinate. A value of
	 * {@code >= 2} signals encoded protocol data; anything below is an ordinary placement.
	 */
	static final float PROTOCOL_CURSOR_THRESHOLD = 2.0f;

	private final Consumer<String> debug;

	/**
	 * @param debug sink for diagnostic messages (the caller decides whether/how to surface them)
	 */
	BlockPlacementProtocol(Consumer<String> debug) {
		this.debug = debug;
	}

	/** True if the cursor X coordinate carries an encoded protocol value. */
	static boolean carriesProtocol(float cursorX) {
		return cursorX >= PROTOCOL_CURSOR_THRESHOLD;
	}

	/** Reverses Tweakeroo/Litematica's {@code (value * 2) + 2} cursor encoding (EasyPlace V2). */
	static int decode(float cursorX) {
		return ((int) cursorX - 2) / 2;
	}

	/**
	 * Reverses Litematica's EasyPlace V3 {@code value + 2} cursor encoding. Unlike V2 the value is
	 * not doubled, so the decode is a plain {@code (int) x - 2}.
	 */
	static int decodeV3(float cursorX) {
		return (int) cursorX - 2;
	}

	/**
	 * The decoded outcome of inspecting a placement packet's cursor X: the protocol value together
	 * with which EasyPlace version produced it. Deliberately free of any transport (PacketEvents)
	 * types so {@link #decodePlacement} can be unit-tested in isolation.
	 */
	record DecodedPlacement(int protocolValue, boolean v3) {
	}

	/**
	 * Decodes a placement packet's block-relative cursor X, selecting the V2 or V3 scheme by
	 * {@code v3Mode}, or returns {@code null} if the cursor carries no protocol value.
	 *
	 * <p>This is the single decision point the transport adapter delegates to: it folds the
	 * {@link #carriesProtocol} gate and the mode-dependent {@link #decode}/{@link #decodeV3} choice
	 * into one pure, testable call. The two wire formats are indistinguishable per-packet, so the
	 * caller's cached mode is the only thing that disambiguates them.
	 */
	static DecodedPlacement decodePlacement(float cursorX, boolean v3Mode) {
		if (!carriesProtocol(cursorX)) {
			return null;
		}
		return v3Mode
				? new DecodedPlacement(decodeV3(cursorX), true)
				: new DecodedPlacement(decode(cursorX), false);
	}

	/**
	 * Number of bits the V3 protocol uses to index a property with {@code valueCount} possible
	 * values: {@code floorLog2(smallestEncompassingPowerOfTwo(valueCount))}, mirroring Minecraft's
	 * {@code MathHelper}. Examples: 1&rarr;0, 2&rarr;1, 3&rarr;2, 4&rarr;2, 5..8&rarr;3.
	 */
	static int requiredBits(int valueCount) {
		if (valueCount <= 1) {
			return 0;
		}
		int pow2 = Integer.highestOneBit(valueCount);
		if (pow2 < valueCount) {
			pow2 <<= 1;
		}
		return Integer.numberOfTrailingZeros(pow2);
	}

	/** Maps a protocol facing index (0..5) to a {@link BlockFace}, or {@code null} if out of range. */
	static BlockFace facingFromIndex(int index) {
		return switch (index) {
			case 0 -> BlockFace.DOWN;
			case 1 -> BlockFace.UP;
			case 2 -> BlockFace.NORTH;
			case 3 -> BlockFace.SOUTH;
			case 4 -> BlockFace.WEST;
			case 5 -> BlockFace.EAST;
			default -> null;
		};
	}

	/** Rotates a cardinal/ordinal block face 90 degrees clockwise; UP/DOWN/SELF are unchanged. */
	static BlockFace rotateCW(BlockFace in) {
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

	/** True for blocks the protocol allows to be placed mid-air (currently candles). */
	static boolean isAirPlaceable(Material material) {
		// todo: consider other additional blocks to add
		return Tag.CANDLES.isTagged(material);
	}

	/**
	 * Applies the protocol orientation to {@code blockData} in place: facing, chest merging, stair
	 * shape, log axis, repeater delay, comparator mode and bisected half.
	 *
	 * <p>This performs no canPlace validation and schedules nothing - the caller decides how to
	 * commit the mutated block data.
	 *
	 * @param blockData      block data to mutate (typically the freshly placed block's data)
	 * @param protocolValue  decoded Carpet protocol value
	 * @param block          the block being placed
	 * @param clickedBlock   the block that was clicked against
	 * @param placerSneaking whether the placing player was sneaking (suppresses chest auto-merge)
	 */
	void apply(BlockData blockData, int protocolValue, Block block, Block clickedBlock, boolean placerSneaking) {
		debug.accept("apply: material=" + blockData.getMaterial() + " protocol=" + protocolValue
				+ " binary=" + Integer.toBinaryString(protocolValue));

		if (blockData instanceof Directional directional) {
			int facingIndex = protocolValue & 0xF;
			BlockFace currentFacing = directional.getFacing();
			debug.accept("Directional: facingIndex=" + facingIndex + " currentFacing=" + currentFacing);

			// handle directional block reversal: 6 for most blocks, >6 for stairs
			if (facingIndex == 6) {
				BlockFace newFacing = directional.getFacing().getOppositeFace();
				directional.setFacing(newFacing);
				debug.accept("Reversed facing from " + currentFacing + " to " + newFacing);
			} else if (facingIndex <= 5) {
				BlockFace face = facingFromIndex(facingIndex);
				Set<BlockFace> validFaces = directional.getFaces();
				debug.accept("Trying to set facing to " + face + " valid="
						+ (face != null ? validFaces.contains(face) : "null"));

				if (face != null && validFaces.contains(face)) {
					directional.setFacing(face);
					debug.accept("Set facing to " + face);

					if (face == BlockFace.UP || face == BlockFace.DOWN) {
						debug.accept("Set vertical facing: " + blockData.getMaterial() + " to " + face);
					}
				}
			} else if (blockData instanceof Stairs && facingIndex > 6) {
				// for higher indices stairs try reversing
				BlockFace newFacing = directional.getFacing().getOppositeFace();
				directional.setFacing(newFacing);
				debug.accept("Stairs special reverse: " + facingIndex + " from " + currentFacing + " to " + newFacing);
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
				} else if (!placerSneaking) {
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
				stairs.setShape(resolveStairShape(block, stairs));
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
				debug.accept("Set axis to " + axis);
			}
		}

		// additional properties: repeater delay | comparator mode | bisected half toggle
		protocolValue &= 0xFFFFFFF0;
		if (protocolValue >= 16) {
			if (blockData instanceof Repeater repeater) {
				int delay = protocolValue / 16;
				if (delay >= repeater.getMinimumDelay() && delay <= repeater.getMaximumDelay()) {
					repeater.setDelay(delay);
					debug.accept("Set repeater delay to " + delay);
				}
			} else if (protocolValue == 16) {
				if (blockData instanceof Comparator comparator) {
					comparator.setMode(Comparator.Mode.SUBTRACT);
					debug.accept("Set comparator to subtract mode");
				} else if (blockData instanceof Bisected bisected) {
					bisected.setHalf(Bisected.Half.TOP);
					debug.accept("Set bisected half to TOP");
				}
			}
		}
	}

	/**
	 * Applies the EasyPlace <b>V3</b> orientation to {@code model}. V3 packs every whitelisted
	 * block-state property generically, so unlike {@link #apply} this needs no per-block
	 * special-casing - the {@link StateModel} owns the state table and the placement validation.
	 * Mirrors Litematica's {@code PlacementHandler.applyPlacementProtocolV3}.
	 *
	 * <p>Bit layout (LSB first): bit 0 reserved; the first direction property occupies 3 bits at
	 * offset 1 as {@code facingId << 1}; then each whitelisted non-direction property, in ascending
	 * property-name order, occupies {@link #requiredBits} bits holding its value index.
	 *
	 * @param model         the block's state-property table (mutated in place via the model)
	 * @param protocolValue the decoded V3 protocol value
	 * @param playerFacing  the placing player's horizontal facing (for the direction fallback)
	 */
	void applyV3(StateModel model, int protocolValue, BlockFace playerFacing) {
		debug.accept("applyV3: protocol=" + protocolValue + " binary=" + Integer.toBinaryString(protocolValue));

		if (protocolValue < 0) {
			return; // no orientation requested
		}

		if (model.hasDirectionProperty()) {
			int facingIndex = (protocolValue & 0xF) >> 1;
			model.applyDirection(facingIndex, playerFacing);
			protocolValue >>>= 3;
		}

		// consume the reserved low bit (bit 0 in the wire layout)
		protocolValue >>>= 1;

		for (StateModel.Prop prop : model.sortedProperties()) {
			int bits = requiredBits(prop.valueCount());
			int index = protocolValue & ((1 << bits) - 1);
			// match Litematica: only apply and consume bits when the index is in range. Real client
			// input is always in range; on malformed input we simply stop tracking that property.
			if (index >= 0 && index < prop.valueCount()) {
				prop.trySetIndex(index);
				protocolValue >>>= bits;
			}
		}
	}

	/**
	 * Abstraction over a block's state-property table, used by {@link #applyV3}. Implementations
	 * decide how to read and mutate the underlying block state (production uses NMS reflection in
	 * {@code NmsStateModel}); keeping the bit-walking in {@link #applyV3} lets it be unit-tested
	 * against a simple in-memory fake.
	 */
	interface StateModel {

		/** True if the block has a "first" direction-valued property (excluding vertical_direction). */
		boolean hasDirectionProperty();

		/**
		 * Apply a decoded facing index to the first direction property, validating placement and
		 * keeping the change only if it survives.
		 *
		 * @param facingIndex  {@code 6} &rarr; opposite of the current facing; {@code 0..5} &rarr; the
		 *                     face from {@link #facingFromIndex}; anything else &rarr; the opposite of
		 *                     {@code playerFacing}
		 * @param playerFacing the placing player's horizontal facing, for the out-of-range fallback
		 */
		void applyDirection(int facingIndex, BlockFace playerFacing);

		/** Whitelisted, non-direction properties in ascending property-name order. */
		List<Prop> sortedProperties();

		/** The resulting block data after all mutations. */
		BlockData result();

		/** A single whitelisted, non-direction property of the block. */
		interface Prop {

			/** Number of possible values, sorted the same way the client sorts them. */
			int valueCount();

			/**
			 * Set this property to the value at {@code index} in the client's sorted order, applying
			 * the same guards as Litematica: no-op if already equal, skip a slab {@code DOUBLE} value
			 * (to avoid slab duplication), and revert if the candidate would not survive placement.
			 */
			void trySetIndex(int index);
		}
	}

	/**
	 * Resolves the connecting shape of a placed stair block by inspecting its four neighbours,
	 * matching vanilla's corner-stair behaviour.
	 */
	static Stairs.Shape resolveStairShape(Block block, Stairs stairs) {
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
}
