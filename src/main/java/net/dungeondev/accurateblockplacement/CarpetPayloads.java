package net.dungeondev.accurateblockplacement;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the raw plugin-message bodies for the Carpet {@code carpet:hello} handshake channel.
 *
 * <p>This is pure wire-format serialization with no Bukkit or PacketEvents types, so it is fully
 * unit-testable. The byte layout is deliberately identical to what ProtocolLib's
 * {@code StreamSerializer} / NBT serializer produced before the PacketEvents migration, so existing
 * Carpet/Tweakeroo/Litematica clients keep recognising the server.
 */
final class CarpetPayloads {

	/** The Carpet handshake plugin-message channel. */
	static final String CHANNEL = "carpet:hello";

	private CarpetPayloads() {
	}

	/**
	 * Server &rarr; client hello body: {@code VarInt(version)} followed by a protocol string.
	 *
	 * @param version protocol version advertised to the client
	 * @param brand   server brand identifier
	 * @return the payload bytes (excluding the channel name)
	 */
	static byte[] hello(int version, String brand) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(body);
			writeVarInt(dos, version);
			writeMcString(dos, brand);
			dos.flush();
		} catch (IOException e) {
			// a ByteArrayOutputStream never actually throws
			throw new UncheckedIOException(e);
		}
		return body.toByteArray();
	}

	/**
	 * Server &rarr; client rules body: {@code VarInt(1)} followed by a single named NBT compound of
	 * string tags describing one Carpet rule.
	 *
	 * @param compoundName name of the root NBT compound
	 * @param value        rule value
	 * @param manager      rule manager
	 * @param rule         rule name
	 * @return the payload bytes (excluding the channel name)
	 */
	static byte[] rule(String compoundName, String value, String manager, String rule) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(body);
			writeVarInt(dos, 1);
			writeNamedStringCompound(dos, compoundName, List.of(
					new String[] { "Value", value },
					new String[] { "Manager", manager },
					new String[] { "Rule", rule }));
			dos.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return body.toByteArray();
	}

	// --- low-level writers (package-private for direct byte-parity testing) ---

	/** Minecraft VarInt: 7 data bits per byte, MSB as the continuation flag. */
	static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	/** Minecraft protocol string: {@code VarInt(byte length)} followed by UTF-8 bytes. */
	static void writeMcString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	/**
	 * Named NBT compound of string tags. {@link DataOutputStream#writeUTF} emits exactly an NBT
	 * string (unsigned-short length + modified UTF-8), so the output matches ProtocolLib's
	 * {@code serializeCompound}.
	 */
	static void writeNamedStringCompound(DataOutputStream out, String name, List<String[]> entries)
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
