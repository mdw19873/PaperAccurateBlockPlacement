package net.dungeondev.accurateblockplacement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the Carpet handshake wire format. No Bukkit server is required.
 *
 * <p>The rule-body test decodes the payload with an <em>independent</em> reader
 * ({@link DataInputStream}, the JDK's own inverse of {@link DataOutputStream}) rather than the
 * production writer, so it genuinely verifies the byte layout that Carpet clients expect.
 */
@DisplayName("CarpetPayloads")
class CarpetPayloadsTest {

	@Nested
	@DisplayName("writeVarInt")
	class WriteVarInt {

		@Test
		@DisplayName("encodes single-byte values verbatim")
		void singleByte() throws IOException {
			assertThat(varInt(0)).containsExactly(0x00);
			assertThat(varInt(1)).containsExactly(0x01);
			assertThat(varInt(69)).containsExactly(0x45);
			assertThat(varInt(127)).containsExactly(0x7F);
		}

		@Test
		@DisplayName("encodes multi-byte values with continuation bits")
		void multiByte() throws IOException {
			assertThat(varInt(128)).containsExactly(0x80, 0x01);
			assertThat(varInt(255)).containsExactly(0xFF, 0x01);
			assertThat(varInt(300)).containsExactly(0xAC, 0x02);
		}
	}

	@Nested
	@DisplayName("hello body")
	class Hello {

		@Test
		@DisplayName("is VarInt(version) followed by the protocol string")
		void exactLayout() {
			byte[] body = CarpetPayloads.hello(69, "PAPER-ABP");

			// 0x45 = 69, 0x09 = string length, then the ASCII bytes of "PAPER-ABP"
			assertThat(body).containsExactly(
					0x45, 0x09, 'P', 'A', 'P', 'E', 'R', '-', 'A', 'B', 'P');
		}
	}

	@Nested
	@DisplayName("rule body")
	class Rule {

		@Test
		@DisplayName("round-trips through an independent NBT reader")
		void roundTrips() throws IOException {
			byte[] body = CarpetPayloads.rule("Rules", "true", "carpet", "accurateBlockPlacement");

			DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
			assertThat(readVarInt(in)).as("rule count").isEqualTo(1);
			assertThat(in.readByte()).as("TAG_Compound").isEqualTo((byte) 10);
			assertThat(in.readUTF()).as("root name").isEqualTo("Rules");
			assertStringTag(in, "Value", "true");
			assertStringTag(in, "Manager", "carpet");
			assertStringTag(in, "Rule", "accurateBlockPlacement");
			assertThat(in.readByte()).as("TAG_End").isEqualTo((byte) 0);
			assertThat(in.available()).as("no trailing bytes").isZero();
		}
	}

	// --- helpers ---

	private static byte[] varInt(int value) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CarpetPayloads.writeVarInt(new DataOutputStream(out), value);
		return out.toByteArray();
	}

	private static void assertStringTag(DataInputStream in, String key, String value) throws IOException {
		assertThat(in.readByte()).as("TAG_String").isEqualTo((byte) 8);
		assertThat(in.readUTF()).as("tag name").isEqualTo(key);
		assertThat(in.readUTF()).as("tag value").isEqualTo(value);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int value = 0;
		int position = 0;
		int currentByte;
		do {
			currentByte = in.readUnsignedByte();
			value |= (currentByte & 0x7F) << position;
			position += 7;
		} while ((currentByte & 0x80) != 0);
		return value;
	}
}
