package net.dungeondev.accurateblockplacement;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
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
	}
}
