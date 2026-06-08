package net.dungeondev.accurateblockplacement;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke tests for the V3 NMS reflection layer.
 *
 * <p>The suite splits in two:
 * <ul>
 *   <li><b>Fail-soft contract</b> - runs everywhere, including CI/MockBukkit (where the
 *       Mojang-mapped {@code CraftBlockData}/{@code net.minecraft...} classes are <em>not</em> on the
 *       classpath, so {@link NmsStateModel#isAvailable()} is {@code false}). These prove that the
 *       reflection layer degrades quietly and never disturbs the V2 path.</li>
 *   <li><b>Reflection round-trip</b> - {@link EnabledIf}-gated on {@link #nmsAvailable()}, so it is
 *       <em>disabled</em> on CI and only executes inside a real Mojang-mapped Paper/Purpur server
 *       (e.g. when the suite is run by an in-server integration harness). MockBukkit never produces a
 *       {@code CraftBlockData}, so the round-trip additionally guards itself with an assumption.</li>
 * </ul>
 *
 * <p>The pure V3 bit-walk is covered separately in {@link BlockPlacementProtocolV3Test}; this class
 * exercises only the reflection-backed {@link NmsStateModel} adapter.
 */
@DisplayName("NmsStateModel")
class NmsStateModelTest {

	private static final Consumer<String> NO_DEBUG = message -> {
	};

	/** Condition method for {@link EnabledIf}: the NMS block-state handles resolved on this server. */
	static boolean nmsAvailable() {
		return NmsStateModel.isAvailable();
	}

	/**
	 * Contract that holds on any server, including one without the NMS internals: the model declines
	 * quietly so EasyPlace V2 keeps working.
	 */
	@Nested
	@DisplayName("fail-soft (no NMS internals / non-CraftBlockData)")
	class FailSoft {

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

		@Test
		@DisplayName("isAvailable() never throws (the static initializer is fail-soft)")
		void isAvailableNeverThrows() {
			// The handles are resolved in a static initializer that must swallow any failure; merely
			// reaching this assertion proves the class loaded without propagating an exception.
			assertThatCode(NmsStateModel::isAvailable).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("create() returns null for block data that is not a CraftBlockData")
		void createReturnsNullForMockData() {
			Block block = world.getBlockAt(0, 64, 0);
			block.setType(Material.OAK_STAIRS);

			// MockBukkit's BlockData is not a CraftBlockData, so the model must decline (return null)
			// rather than attempt reflection - this is exactly the guarantee the V2 path relies on.
			assertThat(NmsStateModel.create(block, NO_DEBUG)).isNull();
		}
	}

	/**
	 * The genuine reflection round-trip: {@code BlockData -> NMS BlockState -> mutate -> BlockData}.
	 * Only meaningful where the server hands out real {@code CraftBlockData}, hence the gate.
	 */
	@Nested
	@EnabledIf("net.dungeondev.accurateblockplacement.NmsStateModelTest#nmsAvailable")
	@DisplayName("reflection round-trip (real Mojang-mapped server only)")
	class ReflectionRoundTrip {

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

		@Test
		@DisplayName("builds a model from oak_stairs, mutates facing, and round-trips back to BlockData")
		void roundTripsOakStairs() {
			Block block = world.getBlockAt(0, 64, 0);
			block.setType(Material.OAK_STAIRS);

			NmsStateModel model = NmsStateModel.create(block, NO_DEBUG);
			// Even with the NMS handles present, a mock BlockData would not be a CraftBlockData; skip
			// rather than NPE so this stays an honest "real server only" check.
			assumeTrue(model != null, "block data is not a CraftBlockData under this harness");

			assertThat(model.hasDirectionProperty())
					.as("oak_stairs exposes a horizontal FACING direction property")
					.isTrue();
			assertThat(model.sortedProperties())
					.as("oak_stairs has whitelisted non-direction properties (half, shape)")
					.isNotEmpty();

			// facing index 2 == NORTH (matches BlockPlacementProtocol.facingFromIndex)
			model.applyDirection(2, BlockFace.NORTH);
			BlockData result = model.result();

			assertThat(result).as("conversion back to Bukkit BlockData succeeds").isNotNull();
			assertThat(result.getMaterial()).isEqualTo(Material.OAK_STAIRS);
			assertThat(result).isInstanceOf(Stairs.class);
			assertThat(((Stairs) result).getFacing())
					.as("the facing mutation survived the NMS round-trip")
					.isEqualTo(BlockFace.NORTH);
		}
	}
}
