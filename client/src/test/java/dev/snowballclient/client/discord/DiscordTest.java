package dev.snowballclient.client.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiscordTest {
	private static final String READY = "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\",\"data\":{\"v\":1}}";
	private static final String INVALID_ID = "{\"code\":4000,\"message\":\"Invalid Client ID\"}";

	/** One of Discord's pipes, answering with the frames it was given and recording what it was sent. */
	private static final class FakePipe implements DiscordIpc.Channel {
		private final Deque<Byte> answers = new ArrayDeque<>();
		final ByteArrayOutputStream sent = new ByteArrayOutputStream();
		boolean closed;

		FakePipe answer(int opcode, String json) {
			byte[] body = json.getBytes(StandardCharsets.UTF_8);
			ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
			frame.putInt(opcode).putInt(body.length).put(body);
			for (byte b : frame.array()) answers.add(b);
			return this;
		}

		@Override
		public void write(byte[] data) throws IOException {
			if (closed) throw new IOException("closed");
			sent.write(data);
		}

		@Override
		public void readFully(byte[] into) throws IOException {
			for (int i = 0; i < into.length; i++) {
				Byte b = answers.poll();
				if (b == null) throw new EOFException("Discord said nothing more");
				into[i] = b;
			}
		}

		@Override
		public void close() {
			closed = true;
		}

		/** The opcodes of the frames this pipe was sent, in order. */
		List<Integer> opcodes() {
			List<Integer> out = new ArrayList<>();
			ByteBuffer all = ByteBuffer.wrap(sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
			while (all.remaining() >= 8) {
				out.add(all.getInt());
				int length = all.getInt();
				all.position(all.position() + length);
			}
			return out;
		}
	}

	@Test
	void acceptsOnlyWhenDiscordSaysReady() throws IOException {
		FakePipe pipe = new FakePipe().answer(1, READY);
		DiscordIpc client = DiscordIpc.connect("123456789012345678", i -> i == 0 ? pipe : null);
		assertNotNull(client);
		assertEquals(List.of(0), pipe.opcodes(), "a handshake and nothing else before READY");
		assertTrue(pipe.sent.toString(StandardCharsets.UTF_8).contains("\"client_id\":\"123456789012345678\""));
		client.close();
		assertTrue(pipe.closed);
	}

	@Test
	void aRefusedApplicationIdIsReportedNotTakenForAConnection() {
		FakePipe pipe = new FakePipe().answer(2, INVALID_ID);
		DiscordIpc.RejectedException rejected = assertThrows(DiscordIpc.RejectedException.class,
				() -> DiscordIpc.connect("123456789012345678", i -> i == 0 ? pipe : null));
		assertEquals("Invalid Client ID", rejected.getMessage());
		assertTrue(pipe.closed, "the refused pipe is closed");
	}

	@Test
	void noDiscordMeansNoClientAndNoError() throws IOException {
		assertNull(DiscordIpc.connect("123456789012345678", i -> null));
	}

	@Test
	void aPipeThatHangsUpIsSkippedForTheNextOne() throws IOException {
		FakePipe silent = new FakePipe();
		FakePipe canary = new FakePipe().answer(1, READY);
		DiscordIpc client = DiscordIpc.connect("123456789012345678", i -> i == 0 ? silent : i == 1 ? canary : null);
		assertNotNull(client);
		assertTrue(silent.closed);
		assertFalse(canary.closed);
	}

	@Test
	void activityIsSentAndAClosedPipeIsAnError() throws IOException {
		FakePipe pipe = new FakePipe().answer(1, READY).answer(1, "{\"cmd\":\"SET_ACTIVITY\"}").answer(2, "{\"code\":1000}");
		DiscordIpc client = DiscordIpc.connect("123456789012345678", i -> i == 0 ? pipe : null);
		assertNotNull(client);
		JsonObject activity = new JsonObject();
		activity.addProperty("details", "Playing with Snowball Client");
		client.setActivity(activity);
		assertEquals(List.of(0, 1), pipe.opcodes());
		assertTrue(pipe.sent.toString(StandardCharsets.UTF_8).contains("Playing with Snowball Client"));
		assertThrows(IOException.class, () -> client.setActivity(activity), "Discord closing the pipe must not look like success");
	}

	@Test
	void onlyPublicDomainsReachDiscord() {
		assertEquals("mc.hypixel.net", ServerLabel.publicName("mc.hypixel.net:25565"));
		assertEquals("play.example.com", ServerLabel.publicName(" Play.Example.com. "));
		assertEquals("play.example.com", ServerLabel.publicName("play.example.com:25566"));
		for (String secret : new String[]{
				null, "", "   ", "127.0.0.1", "192.168.1.20:25565", "10.0.0.5", "8.8.8.8", "[::1]:25565", "::1",
				"2001:db8::1", "localhost", "localhost:25565", "minecraft", "nas.local", "server.lan", "box.home.arpa",
				"mysmp.duckdns.org", "friend.ddns.net:25565", "abc.playit.gg", "cool-name.joinmc.link", "x.ngrok-free.app",
				"white space.com", "a".repeat(70) + ".com"}) {
			assertNull(ServerLabel.publicName(secret), () -> "must stay private: " + secret);
		}
	}
}
