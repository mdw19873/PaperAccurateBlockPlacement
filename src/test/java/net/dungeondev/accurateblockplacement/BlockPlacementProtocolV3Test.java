package net.dungeondev.accurateblockplacement;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the EasyPlace <b>V3</b> decode/bit-walk logic. No Bukkit server is required:
 * {@link BlockPlacementProtocol#applyV3} is exercised against an in-memory {@link FakeStateModel},
 * so we verify the exact bit consumption (3 bits direction, 1 reserved, then per-property widths)
 * independently of the NMS-backed model.
 *
 * <p>Expected values are derived by hand from Litematica's encode
 * ({@code WorldUtils.applyPlacementProtocolV3}): {@code value} is built LSB-first as
 * {@code (facingId << 1)} then each non-direction property's index shifted by the running width.
 */
@DisplayName("BlockPlacementProtocol (V3)")
class BlockPlacementProtocolV3Test {

	private final BlockPlacementProtocol protocol = new BlockPlacementProtocol(s -> {
	});

	@Nested
	@DisplayName("decodeV3")
	class DecodeV3 {

		@ParameterizedTest(name = "cursorX {0} -> {1}")
		@CsvSource({
				"2.0, 0",
				"2.9, 0",
				"3.0, 1",
				"4.5, 2",
				"28.0, 26",
				"28.9, 26",
		})
		@DisplayName("reverses the (value + 2) encoding without doubling")
		void decodes(float cursorX, int expected) {
			assertThat(BlockPlacementProtocol.decodeV3(cursorX)).isEqualTo(expected);
		}
	}

	@Nested
	@DisplayName("requiredBits")
	class RequiredBits {

		@ParameterizedTest(name = "{0} values -> {1} bits")
		@CsvSource({
				"0, 0",
				"1, 0",
				"2, 1",
				"3, 2",
				"4, 2",
				"5, 3",
				"8, 3",
				"9, 4",
				"16, 4",
		})
		@DisplayName("is floorLog2(smallestEncompassingPowerOfTwo(n))")
		void bits(int valueCount, int expectedBits) {
			assertThat(BlockPlacementProtocol.requiredBits(valueCount)).isEqualTo(expectedBits);
		}
	}

	@Nested
	@DisplayName("applyV3 bit-walk")
	class ApplyV3 {

		@Test
		@DisplayName("decodes facing + properties for a stairs-like block (value 26)")
		void stairsLike() {
			// EAST(id 5)<<1 = 10; half index 1 (<<4) = 16; shape index 0 (<<5) = 0  => 26
			FakeProp half = new FakeProp("half", 2);
			FakeProp shape = new FakeProp("shape", 5);
			FakeStateModel model = new FakeStateModel(true, List.of(half, shape));

			protocol.applyV3(model, 26, BlockFace.NORTH);

			assertThat(model.appliedFacingIndex).isEqualTo(5);
			assertThat(half.appliedIndex).isEqualTo(1);
			assertThat(shape.appliedIndex).isEqualTo(0);
		}

		@Test
		@DisplayName("skips the 3 facing bits when there is no direction property (value 10)")
		void noDirection() {
			// no direction: reserved bit consumed, then a(1 bit) idx1, b(2 bits) idx2
			// encode: a idx1 <<1 = 2; b idx2 <<2 = 8 => 10
			FakeProp a = new FakeProp("a", 2);
			FakeProp b = new FakeProp("b", 4);
			FakeStateModel model = new FakeStateModel(false, List.of(a, b));

			protocol.applyV3(model, 10, BlockFace.NORTH);

			assertThat(model.appliedFacingIndex).isNull();
			assertThat(a.appliedIndex).isEqualTo(1);
			assertThat(b.appliedIndex).isEqualTo(2);
		}

		@Test
		@DisplayName("value 0 is valid: facing DOWN (id 0) and all property indices 0")
		void zeroIsValid() {
			FakeProp p = new FakeProp("p", 4);
			FakeStateModel model = new FakeStateModel(true, List.of(p));

			protocol.applyV3(model, 0, BlockFace.NORTH);

			assertThat(model.appliedFacingIndex).isEqualTo(0);
			assertThat(p.appliedIndex).isEqualTo(0);
		}

		@Test
		@DisplayName("negative value applies nothing")
		void negativeIsNoOp() {
			FakeProp p = new FakeProp("p", 4);
			FakeStateModel model = new FakeStateModel(true, List.of(p));

			protocol.applyV3(model, -1, BlockFace.NORTH);

			assertThat(model.appliedFacingIndex).isNull();
			assertThat(p.appliedIndex).isNull();
		}

		@Test
		@DisplayName("does not apply a property whose decoded index is out of range")
		void outOfRangePropertyIndexIsSkipped() {
			// no direction: reserved bit consumed, then prop p (3 values -> 2 bits). value 6 = 110:
			// after consuming the reserved bit -> 11 (3), and index 3 >= valueCount 3, so p is skipped.
			FakeProp p = new FakeProp("p", 3);
			FakeStateModel model = new FakeStateModel(false, List.of(p));

			protocol.applyV3(model, 6, BlockFace.NORTH);

			assertThat(p.appliedIndex).isNull();
		}

		@Test
		@DisplayName("facing index 6 (reverse) is passed through to the model")
		void reverseFacing() {
			// facingId 6 << 1 = 12; no properties
			FakeStateModel model = new FakeStateModel(true, List.of());

			protocol.applyV3(model, 12, BlockFace.NORTH);

			assertThat(model.appliedFacingIndex).isEqualTo(6);
		}
	}

	// --- fakes ---

	/** Records the operations {@link BlockPlacementProtocol#applyV3} drives, without any real state. */
	private static final class FakeStateModel implements BlockPlacementProtocol.StateModel {
		private final boolean hasDirection;
		private final List<Prop> props;
		private Integer appliedFacingIndex;

		FakeStateModel(boolean hasDirection, List<FakeProp> props) {
			this.hasDirection = hasDirection;
			this.props = new ArrayList<>(props);
		}

		@Override
		public boolean hasDirectionProperty() {
			return hasDirection;
		}

		@Override
		public void applyDirection(int facingIndex, BlockFace playerFacing) {
			this.appliedFacingIndex = facingIndex;
		}

		@Override
		public List<Prop> sortedProperties() {
			return props;
		}

		@Override
		public BlockData result() {
			return null;
		}
	}

	private static final class FakeProp implements BlockPlacementProtocol.StateModel.Prop {
		private final String name;
		private final int count;
		private Integer appliedIndex;

		FakeProp(String name, int count) {
			this.name = name;
			this.count = count;
		}

		@Override
		public int valueCount() {
			return count;
		}

		@Override
		public void trySetIndex(int index) {
			this.appliedIndex = index;
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
