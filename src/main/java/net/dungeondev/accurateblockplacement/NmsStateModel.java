package net.dungeondev.accurateblockplacement;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link BlockPlacementProtocol.StateModel} backed by the server's NMS block-state table, accessed
 * entirely through reflection on Mojang-mapped names so the plugin keeps its single-jar,
 * {@code paper-api}-only compile setup across the supported Minecraft range.
 *
 * <p>EasyPlace V3 encodes <em>generic</em> block-state properties (every whitelisted property packed
 * by bit width), which Bukkit's {@code BlockData} abstraction cannot enumerate. This class reaches
 * into {@code net.minecraft...BlockState} / {@code StateDefinition} / {@code Property} to list the
 * properties, set them by index, and convert the result back to a Bukkit {@link BlockData}.
 *
 * <p>All reflection handles are resolved once in a static initializer. If anything is missing
 * (an unexpected mapping change), {@link #isAvailable()} reports {@code false} and {@link #create}
 * returns {@code null}, so the V2 path is never affected.
 */
final class NmsStateModel implements BlockPlacementProtocol.StateModel {

	// --- cached reflection handles (resolved once) ---
	private static final boolean AVAILABLE;
	private static final Class<?> CRAFT_BLOCK_DATA;
	private static final Class<?> DIRECTION_CLASS;
	private static final Method CBD_GET_STATE;       // CraftBlockData#getState() -> BlockState
	private static final Method CBD_CREATE_DATA;     // static CraftBlockData#createData(BlockState)
	private static final Method BS_AS_BLOCKDATA;     // BlockState#asBlockData() (fallback)
	private static final Method BS_GET_BLOCK;        // BlockState#getBlock()
	private static final Method BLOCK_GET_STATEDEF;  // Block#getStateDefinition()
	private static final Method SD_GET_PROPERTIES;   // StateDefinition#getProperties()
	private static final Method PROP_GET_NAME;       // Property#getName()
	private static final Method PROP_GET_VALUES;     // Property#getPossibleValues()
	private static final Method PROP_GET_VALUECLASS; // Property#getValueClass()
	private static final Method BS_GET_VALUE;        // BlockState#getValue(Property)
	private static final Method BS_SET_VALUE;        // BlockState#setValue(Property, Comparable)
	private static final Method DIR_GET_OPPOSITE;    // Direction#getOpposite()
	private static final Method DIR_FROM_3D;         // static Direction#from3DDataValue(int)
	private static final Set<Object> WHITELIST;      // NMS Property instances (identity set)

	/**
	 * Litematica's {@code PlacementHandler.WHITELISTED_PROPERTIES}, mapped from its Yarn
	 * {@code Properties.*} constants to the Mojang {@code BlockStateProperties} field names.
	 * Direction-valued entries (HORIZONTAL_FACING, FACING_HOPPER) are inert here - they are handled
	 * by the first-direction-property step - but kept for fidelity. Missing fields are skipped so a
	 * single rename cannot disable V3 wholesale.
	 */
	private static final String[] WHITELIST_FIELDS = {
			"INVERTED", "OPEN", "BELL_ATTACHMENT", "AXIS", "HALF", "ATTACH_FACE", "CHEST_TYPE",
			"MODE", "DOOR_HINGE", "HORIZONTAL_FACING", "FACING_HOPPER", "ORIENTATION", "RAIL_SHAPE",
			"RAIL_SHAPE_STRAIGHT", "SLAB_TYPE", "STAIRS_SHAPE", "BITES", "DELAY", "NOTE", "ROTATION_16"
	};

	static {
		boolean ok = false;
		Class<?> craftBlockData = null;
		Class<?> directionClass = null;
		Method cbdGetState = null;
		Method cbdCreateData = null;
		Method bsAsBlockData = null;
		Method bsGetBlock = null;
		Method blockGetStateDef = null;
		Method sdGetProperties = null;
		Method propGetName = null;
		Method propGetValues = null;
		Method propGetValueClass = null;
		Method bsGetValue = null;
		Method bsSetValue = null;
		Method dirGetOpposite = null;
		Method dirFrom3d = null;
		Set<Object> whitelist = Collections.emptySet();

		try {
			craftBlockData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
			Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
			Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
			Class<?> stateDef = Class.forName("net.minecraft.world.level.block.state.StateDefinition");
			Class<?> property = Class.forName("net.minecraft.world.level.block.state.properties.Property");
			Class<?> blockStateProps = Class.forName(
					"net.minecraft.world.level.block.state.properties.BlockStateProperties");
			directionClass = Class.forName("net.minecraft.core.Direction");

			cbdGetState = craftBlockData.getMethod("getState");
			try {
				cbdCreateData = craftBlockData.getMethod("createData", blockState);
			} catch (NoSuchMethodException ignored) {
				bsAsBlockData = blockState.getMethod("asBlockData");
			}
			bsGetBlock = blockState.getMethod("getBlock");
			blockGetStateDef = blockClass.getMethod("getStateDefinition");
			sdGetProperties = stateDef.getMethod("getProperties");
			propGetName = property.getMethod("getName");
			propGetValues = property.getMethod("getPossibleValues");
			propGetValueClass = property.getMethod("getValueClass");
			bsGetValue = blockState.getMethod("getValue", property);
			bsSetValue = blockState.getMethod("setValue", property, Comparable.class);
			dirGetOpposite = directionClass.getMethod("getOpposite");
			dirFrom3d = directionClass.getMethod("from3DDataValue", int.class);

			whitelist = Collections.newSetFromMap(new IdentityHashMap<>());
			for (String name : WHITELIST_FIELDS) {
				try {
					Field f = blockStateProps.getField(name);
					Object value = f.get(null);
					if (value != null) {
						whitelist.add(value);
					}
				} catch (NoSuchFieldException ignored) {
					// mapping changed for this one entry; skip it rather than failing all of V3
				}
			}

			ok = cbdCreateData != null || bsAsBlockData != null;
		} catch (Throwable t) {
			ok = false;
		}

		AVAILABLE = ok;
		CRAFT_BLOCK_DATA = craftBlockData;
		DIRECTION_CLASS = directionClass;
		CBD_GET_STATE = cbdGetState;
		CBD_CREATE_DATA = cbdCreateData;
		BS_AS_BLOCKDATA = bsAsBlockData;
		BS_GET_BLOCK = bsGetBlock;
		BLOCK_GET_STATEDEF = blockGetStateDef;
		SD_GET_PROPERTIES = sdGetProperties;
		PROP_GET_NAME = propGetName;
		PROP_GET_VALUES = propGetValues;
		PROP_GET_VALUECLASS = propGetValueClass;
		BS_GET_VALUE = bsGetValue;
		BS_SET_VALUE = bsSetValue;
		DIR_GET_OPPOSITE = dirGetOpposite;
		DIR_FROM_3D = dirFrom3d;
		WHITELIST = whitelist;
	}

	/** True if the NMS reflection handles resolved, so V3 decoding can run. */
	static boolean isAvailable() {
		return AVAILABLE;
	}

	private final Block block;
	private final Consumer<String> debug;
	private Object state;                 // current NMS BlockState (immutable; re-assigned on change)
	private final Object directionProp;   // first non-vertical direction Property, or null
	private final Collection<?> directionValues; // possible values of directionProp, or null
	private final List<Prop> props;       // whitelisted non-direction props, sorted by name

	private NmsStateModel(Block block, Consumer<String> debug, Object state, Object directionProp,
			Collection<?> directionValues, List<Prop> props) {
		this.block = block;
		this.debug = debug;
		this.state = state;
		this.directionProp = directionProp;
		this.directionValues = directionValues;
		this.props = props;
	}

	/**
	 * Builds a model for the block's current data, or {@code null} if reflection is unavailable or the
	 * block data is not a {@code CraftBlockData} (so the caller can fall back / skip).
	 */
	static NmsStateModel create(Block block, Consumer<String> debug) {
		if (!AVAILABLE) {
			return null;
		}
		try {
			BlockData data = block.getBlockData();
			if (!CRAFT_BLOCK_DATA.isInstance(data)) {
				return null;
			}
			Object state = CBD_GET_STATE.invoke(data);
			Object nmsBlock = BS_GET_BLOCK.invoke(state);
			Object stateDef = BLOCK_GET_STATEDEF.invoke(nmsBlock);
			Collection<?> properties = (Collection<?>) SD_GET_PROPERTIES.invoke(stateDef);

			// first direction-valued property in the (name-sorted) state definition; excluded if it
			// is vertical_direction (pointed dripstone), matching applyPlacementProtocolV3.
			Object firstDirection = null;
			for (Object p : properties) {
				if (PROP_GET_VALUECLASS.invoke(p) == DIRECTION_CLASS) {
					firstDirection = p;
					break;
				}
			}
			Object directionProp = null;
			Collection<?> directionValues = null;
			if (firstDirection != null
					&& !"vertical_direction".equals(PROP_GET_NAME.invoke(firstDirection))) {
				directionProp = firstDirection;
				directionValues = (Collection<?>) PROP_GET_VALUES.invoke(directionProp);
			}

			NmsStateModel model = new NmsStateModel(block, debug, state, directionProp, directionValues,
					new ArrayList<>());
			for (Object p : properties) {
				if (PROP_GET_VALUECLASS.invoke(p) == DIRECTION_CLASS) {
					continue; // direction props are handled separately and skipped by the loop
				}
				if (!WHITELIST.contains(p)) {
					continue;
				}
				model.props.add(model.new NmsProp(p));
			}
			model.props.sort(Comparator.comparing(prop -> ((NmsProp) prop).name));
			return model;
		} catch (Throwable t) {
			debug.accept("V3 NMS model unavailable: " + t);
			return null;
		}
	}

	@Override
	public boolean hasDirectionProperty() {
		return directionProp != null;
	}

	@Override
	public void applyDirection(int facingIndex, BlockFace playerFacing) {
		if (directionProp == null) {
			return;
		}
		try {
			Object currentFacing = BS_GET_VALUE.invoke(state, directionProp);
			Object newFacing;
			if (facingIndex == 6) {
				newFacing = DIR_GET_OPPOSITE.invoke(currentFacing);
			} else if (facingIndex >= 0 && facingIndex <= 5) {
				newFacing = DIR_FROM_3D.invoke(null, facingIndex);
				if (!directionValues.contains(newFacing)) {
					newFacing = toNmsDirection(playerFacing != null ? playerFacing.getOppositeFace() : null);
				}
			} else {
				return; // out of range -> leave facing unchanged
			}

			if (newFacing == null || newFacing.equals(currentFacing) || !directionValues.contains(newFacing)) {
				return;
			}
			Object candidate = BS_SET_VALUE.invoke(state, directionProp, newFacing);
			commitIfPlaceable(candidate);
		} catch (Throwable t) {
			debug.accept("V3 direction apply failed: " + t);
		}
	}

	@Override
	public List<Prop> sortedProperties() {
		return props;
	}

	@Override
	public BlockData result() {
		return toBlockData(state);
	}

	/** Keeps {@code candidateState} as the current state only if it survives at the block's position. */
	private void commitIfPlaceable(Object candidateState) {
		BlockData candidate = toBlockData(candidateState);
		if (candidate != null && block.canPlace(candidate)) {
			this.state = candidateState;
		}
		// otherwise revert: leave this.state untouched (mirrors Litematica's per-step canPlaceAt check)
	}

	private BlockData toBlockData(Object nmsState) {
		try {
			Object data = (CBD_CREATE_DATA != null)
					? CBD_CREATE_DATA.invoke(null, nmsState)
					: BS_AS_BLOCKDATA.invoke(nmsState);
			return (BlockData) data;
		} catch (Throwable t) {
			debug.accept("V3 block-data conversion failed: " + t);
			return null;
		}
	}

	/** Maps a cardinal/vertical {@link BlockFace} to the matching NMS {@code Direction}, or null. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object toNmsDirection(BlockFace face) {
		if (face == null) {
			return null;
		}
		try {
			return Enum.valueOf((Class<? extends Enum>) DIRECTION_CLASS.asSubclass(Enum.class), face.name());
		} catch (IllegalArgumentException e) {
			return null; // non-cardinal face (e.g. NORTH_EAST) has no Direction equivalent
		}
	}

	/** A whitelisted, non-direction property, with its values pre-sorted to match the client. */
	private final class NmsProp implements Prop {
		private final Object property;
		private final String name;
		private final List<Object> values;

		@SuppressWarnings({ "unchecked", "rawtypes" })
		private NmsProp(Object property) throws Exception {
			this.property = property;
			this.name = (String) PROP_GET_NAME.invoke(property);
			List<Object> sorted = new ArrayList<>((Collection<?>) PROP_GET_VALUES.invoke(property));
			sorted.sort((a, b) -> ((Comparable) a).compareTo(b));
			this.values = sorted;
		}

		@Override
		public int valueCount() {
			return values.size();
		}

		@Override
		public void trySetIndex(int index) {
			try {
				Object value = values.get(index);
				// never force a double slab via the protocol (would duplicate the slab item)
				if (value instanceof Enum<?> e && "DOUBLE".equals(e.name())) {
					return;
				}
				Object current = BS_GET_VALUE.invoke(state, property);
				if (value.equals(current)) {
					return;
				}
				Object candidate = BS_SET_VALUE.invoke(state, property, value);
				commitIfPlaceable(candidate);
			} catch (Throwable t) {
				debug.accept("V3 property apply failed for " + name + ": " + t);
			}
		}
	}
}
