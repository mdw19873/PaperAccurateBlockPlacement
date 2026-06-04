package net.dungeondev.accurateblockplacement;

import org.bukkit.Material;
import org.bukkit.World;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BlockPlacementProtocol")
class BlockPlacementProtocolTest {

	private static final Consumer<String> NO_DEBUG = message -> {
	};

	/**
	 * Pure decoding/mapping logic. These touch only enums and arithmetic, so no Bukkit server is
	 * needed.
	 */
	@Nested
	@DisplayName("cursor decoding")
	class CursorDecoding {

		@ParameterizedTest
		@ValueSource(floats = { 0f, 0.5f, 1f, 1.99f })
		@DisplayName("treats an ordinary cursor (< 2) as no protocol")
		void ordinaryCursor(float cursorX) {
			assertThat(BlockPlacementProtocol.carriesProtocol(cursorX)).isFalse();
		}

		@ParameterizedTest
		@ValueSource(ints = { 0, 1, 2, 5, 15, 255 })
		@DisplayName("round-trips Tweakeroo's (value * 2) + 2 encoding")
		void roundTrip(int value) {
			float encodedCursorX = value * 2 + 2;

			assertThat(BlockPlacementProtocol.carriesProtocol(encodedCursorX)).isTrue();
			assertThat(BlockPlacementProtocol.decode(encodedCursorX)).isEqualTo(value);
		}
	}

	@Nested
	@DisplayName("facing index mapping")
	class FacingIndexMapping {

		@ParameterizedTest
		@CsvSource({ "0,DOWN", "1,UP", "2,NORTH", "3,SOUTH", "4,WEST", "5,EAST" })
		@DisplayName("maps 0..5 to the expected face")
		void validIndices(int index, BlockFace expected) {
			assertThat(BlockPlacementProtocol.facingFromIndex(index)).isEqualTo(expected);
		}

		@ParameterizedTest
		@ValueSource(ints = { -1, 6, 7, 100 })
		@DisplayName("returns null for out-of-range indices")
		void invalidIndices(int index) {
			assertThat(BlockPlacementProtocol.facingFromIndex(index)).isNull();
		}
	}

	@Nested
	@DisplayName("rotateCW")
	class RotateClockwise {

		@ParameterizedTest
		@CsvSource({
				"NORTH,EAST", "EAST,SOUTH", "SOUTH,WEST", "WEST,NORTH",
				"NORTH_EAST,SOUTH_EAST", "SOUTH_EAST,SOUTH_WEST",
				"SOUTH_WEST,NORTH_WEST", "NORTH_WEST,NORTH_EAST"
		})
		@DisplayName("rotates cardinal and ordinal faces 90 degrees")
		void rotatesHorizontalFaces(BlockFace in, BlockFace expected) {
			assertThat(BlockPlacementProtocol.rotateCW(in)).isEqualTo(expected);
		}

		@ParameterizedTest
		@CsvSource({ "UP,UP", "DOWN,DOWN", "SELF,SELF" })
		@DisplayName("leaves vertical and self faces unchanged")
		void leavesVerticalUnchanged(BlockFace in, BlockFace expected) {
			assertThat(BlockPlacementProtocol.rotateCW(in)).isEqualTo(expected);
		}
	}

	/**
	 * Orientation applied to real {@link BlockData} instances created by MockBukkit's server, so the
	 * behaviour mirrors production exactly.
	 */
	@Nested
	@DisplayName("orientation on real block data")
	class Orientation {

		private ServerMock server;
		private World world;
		private BlockPlacementProtocol protocol;

		@BeforeEach
		void setUp() {
			server = MockBukkit.mock();
			world = server.addSimpleWorld("test");
			protocol = new BlockPlacementProtocol(NO_DEBUG);
		}

		@AfterEach
		void tearDown() {
			MockBukkit.unmock();
		}

		private Block anyBlock() {
			return world.getBlockAt(0, 64, 0);
		}

		private void apply(BlockData data, int protocolValue) {
			Block block = anyBlock();
			protocol.apply(data, protocolValue, block, block, false);
		}

		@Test
		@DisplayName("sets a directional block's facing from the protocol index")
		void setsFacing() {
			Directional furnace = (Directional) server.createBlockData(Material.FURNACE);

			apply(furnace, 2); // index 2 == NORTH

			assertThat(furnace.getFacing()).isEqualTo(BlockFace.NORTH);
		}

		@Test
		@DisplayName("reverses facing when the index is 6")
		void reversesFacing() {
			Directional furnace = (Directional) server.createBlockData(Material.FURNACE);
			furnace.setFacing(BlockFace.NORTH);

			apply(furnace, 6);

			assertThat(furnace.getFacing()).isEqualTo(BlockFace.SOUTH);
		}

		@Test
		@DisplayName("sets the axis of an orientable log")
		void setsLogAxis() {
			Orientable log = (Orientable) server.createBlockData(Material.OAK_LOG);

			apply(log, 2); // 2 % 3 == 2 -> Z

			assertThat(log.getAxis()).isEqualTo(org.bukkit.Axis.Z);
		}

		@Test
		@DisplayName("sets a repeater delay from the high bits")
		void setsRepeaterDelay() {
			Repeater repeater = (Repeater) server.createBlockData(Material.REPEATER);

			apply(repeater, 3 * 16); // delay 3, facing index 0 (ignored)

			assertThat(repeater.getDelay()).isEqualTo(3);
		}

		@Test
		@DisplayName("switches a comparator to subtract mode when the value is 16")
		void setsComparatorMode() {
			Comparator comparator = (Comparator) server.createBlockData(Material.COMPARATOR);

			apply(comparator, 16);

			assertThat(comparator.getMode()).isEqualTo(Comparator.Mode.SUBTRACT);
		}

		@Test
		@DisplayName("sets a bisected block's half to TOP when the value is 16")
		void setsBisectedHalf() {
			Bisected sunflower = (Bisected) server.createBlockData(Material.SUNFLOWER);

			apply(sunflower, 16);

			assertThat(sunflower.getHalf()).isEqualTo(Bisected.Half.TOP);
		}

		@Test
		@DisplayName("recognises candles as air-placeable and ordinary blocks as not")
		void recognisesAirPlaceable() {
			assertThat(BlockPlacementProtocol.isAirPlaceable(Material.CANDLE)).isTrue();
			assertThat(BlockPlacementProtocol.isAirPlaceable(Material.RED_CANDLE)).isTrue();
			assertThat(BlockPlacementProtocol.isAirPlaceable(Material.STONE)).isFalse();
		}

		@Test
		@DisplayName("ignores a facing the block does not support")
		void ignoresUnsupportedFacing() {
			Directional furnace = (Directional) server.createBlockData(Material.FURNACE);
			furnace.setFacing(BlockFace.NORTH);

			apply(furnace, 1); // index 1 == UP, not a valid furnace facing

			assertThat(furnace.getFacing()).isEqualTo(BlockFace.NORTH); // unchanged
		}

		@Test
		@DisplayName("ignores a repeater delay outside the valid range")
		void ignoresOutOfRangeRepeaterDelay() {
			Repeater repeater = (Repeater) server.createBlockData(Material.REPEATER);
			int original = repeater.getDelay();

			apply(repeater, 5 * 16); // delay 5 is above the max (4) -> ignored

			assertThat(repeater.getDelay()).isEqualTo(original);
		}

		@Test
		@DisplayName("reverses a stair's facing for a high facing index (>6)")
		void reversesStairForHighIndex() {
			Stairs stairs = (Stairs) server.createBlockData(Material.OAK_STAIRS);
			stairs.setFacing(BlockFace.NORTH);

			apply(stairs, 7); // index 7 > 6 -> reverse facing

			assertThat(stairs.getFacing()).isEqualTo(BlockFace.SOUTH);
		}
	}

	/**
	 * {@code resolveStairShape} corner detection - the most intricate logic in the class. Each test
	 * places neighbouring stairs in a MockBukkit world and asserts the resolved connecting shape.
	 */
	@Nested
	@DisplayName("stair shape resolution")
	class StairShapes {

		private ServerMock server;
		private World world;

		@BeforeEach
		void setUp() {
			server = MockBukkit.mock();
			world = server.addSimpleWorld("test");
		}

		@AfterEach
		void tearDown() {
			MockBukkit.unmock();
		}

		private Block center() {
			return world.getBlockAt(0, 64, 0);
		}

		private Stairs stair(BlockFace facing) {
			Stairs s = (Stairs) server.createBlockData(Material.OAK_STAIRS);
			s.setFacing(facing);
			s.setHalf(Bisected.Half.BOTTOM);
			return s;
		}

		// place a neighbouring stair (always BOTTOM half) relative to the centre block
		private void putStair(BlockFace dir, BlockFace facing) {
			center().getRelative(dir).setBlockData(stair(facing));
		}

		private Stairs.Shape resolve() {
			// target faces NORTH -> back=NORTH, front=SOUTH, right=EAST, left=WEST
			return BlockPlacementProtocol.resolveStairShape(center(), stair(BlockFace.NORTH));
		}

		@Test
		@DisplayName("straight when nothing connects")
		void straight() {
			assertThat(resolve()).isEqualTo(Stairs.Shape.STRAIGHT);
		}

		@Test
		@DisplayName("outer-left from a back neighbour facing left")
		void outerLeft() {
			putStair(BlockFace.NORTH, BlockFace.WEST);
			assertThat(resolve()).isEqualTo(Stairs.Shape.OUTER_LEFT);
		}

		@Test
		@DisplayName("outer-right from a back neighbour facing right")
		void outerRight() {
			putStair(BlockFace.NORTH, BlockFace.EAST);
			assertThat(resolve()).isEqualTo(Stairs.Shape.OUTER_RIGHT);
		}

		@Test
		@DisplayName("inner-left from a front neighbour facing left")
		void innerLeft() {
			putStair(BlockFace.SOUTH, BlockFace.WEST);
			assertThat(resolve()).isEqualTo(Stairs.Shape.INNER_LEFT);
		}

		@Test
		@DisplayName("inner-right from a front neighbour facing right")
		void innerRight() {
			putStair(BlockFace.SOUTH, BlockFace.EAST);
			assertThat(resolve()).isEqualTo(Stairs.Shape.INNER_RIGHT);
		}
	}

	/**
	 * Chest auto-merge: SINGLE/LEFT/RIGHT across the neighbour-scan path and the clicked-against
	 * path, including the sneaking suppression.
	 */
	@Nested
	@DisplayName("chest auto-merge")
	class ChestMerging {

		private ServerMock server;
		private World world;
		private BlockPlacementProtocol protocol;

		@BeforeEach
		void setUp() {
			server = MockBukkit.mock();
			world = server.addSimpleWorld("test");
			protocol = new BlockPlacementProtocol(NO_DEBUG);
		}

		@AfterEach
		void tearDown() {
			MockBukkit.unmock();
		}

		private Block center() {
			return world.getBlockAt(0, 64, 0);
		}

		private Chest northChest() {
			Chest c = (Chest) server.createBlockData(Material.CHEST);
			c.setFacing(BlockFace.NORTH);
			c.setType(Chest.Type.SINGLE);
			return c;
		}

		private void putChest(BlockFace dir) {
			center().getRelative(dir).setBlockData(northChest());
		}

		// apply with facing index 2 (NORTH) so the chest keeps facing NORTH; left = rotateCW(NORTH) = EAST
		private Chest applyToCentre(Block clicked, boolean sneaking) {
			Chest target = northChest();
			protocol.apply(target, 2, center(), clicked, sneaking);
			return target;
		}

		@Test
		@DisplayName("merges as LEFT from a matching chest on the left (neighbour scan)")
		void mergesLeftFromNeighbour() {
			putChest(BlockFace.EAST); // EAST is the left of a NORTH-facing chest
			assertThat(applyToCentre(center(), false).getType()).isEqualTo(Chest.Type.LEFT);
		}

		@Test
		@DisplayName("merges as RIGHT from a matching chest on the right (neighbour scan)")
		void mergesRightFromNeighbour() {
			putChest(BlockFace.WEST);
			assertThat(applyToCentre(center(), false).getType()).isEqualTo(Chest.Type.RIGHT);
		}

		@Test
		@DisplayName("stays SINGLE when the placer is sneaking")
		void staysSingleWhenSneaking() {
			putChest(BlockFace.EAST);
			assertThat(applyToCentre(center(), true).getType()).isEqualTo(Chest.Type.SINGLE);
		}

		@Test
		@DisplayName("stays SINGLE with no neighbouring chest")
		void staysSingleWithNoNeighbour() {
			assertThat(applyToCentre(center(), false).getType()).isEqualTo(Chest.Type.SINGLE);
		}

		@Test
		@DisplayName("merges as LEFT when clicked against a matching chest on the left")
		void mergesLeftFromClicked() {
			putChest(BlockFace.EAST);
			Block clicked = center().getRelative(BlockFace.EAST);
			assertThat(applyToCentre(clicked, false).getType()).isEqualTo(Chest.Type.LEFT);
		}
	}
}
