package dev.snowballclient.client.discord;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Discord app on this computer over its local pipe, the same way Discord's own library
 * does: a four byte opcode, a four byte length and a JSON body. Nothing here reaches the network,
 * and every call is made from Snowball's own thread so the game never waits for Discord.
 */
public final class DiscordIpc implements Closeable {
	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;
	private static final int OP_CLOSE = 2;
	/** Discord numbers its pipes when several clients (stable, PTB, Canary) run at once. */
	private static final int MAX_PIPES = 10;

	/** Thrown when Discord answers the handshake by refusing the application id. */
	public static final class RejectedException extends IOException {
		public RejectedException(String message) {
			super(message);
		}
	}

	/** One end of a pipe: Windows' named pipe, or the Unix socket everywhere else. */
	interface Channel extends Closeable {
		void write(byte[] data) throws IOException;

		void readFully(byte[] into) throws IOException;
	}

	/** Opens Discord's pipe number {@code index}, or returns null when there is nothing listening. */
	interface Opener {
		Channel open(int index);
	}

	private final Channel channel;

	private DiscordIpc(Channel channel) {
		this.channel = channel;
	}

	/**
	 * @return a client Discord has accepted, or null when Discord is not running on this computer
	 * @throws RejectedException when Discord is running but does not accept the application id
	 */
	public static DiscordIpc connect(String applicationId) throws RejectedException {
		return connect(applicationId, DiscordIpc::openPipe);
	}

	static DiscordIpc connect(String applicationId, Opener opener) throws RejectedException {
		for (int i = 0; i < MAX_PIPES; i++) {
			Channel channel = opener.open(i);
			if (channel == null) continue;
			DiscordIpc client = new DiscordIpc(channel);
			try {
				JsonObject handshake = new JsonObject();
				handshake.addProperty("v", 1);
				handshake.addProperty("client_id", applicationId);
				client.send(OP_HANDSHAKE, handshake.toString());
				// Discord answers READY when it accepts the application, and closes when it does not.
				// Taking any answer as a yes used to report "connected" to a Discord that had said no.
				Frame answer = client.readFrame();
				if (answer.opcode() == OP_CLOSE) {
					client.closeQuietly();
					throw new RejectedException(message(answer.body()));
				}
				if (answer.opcode() == OP_FRAME && answer.body().contains("\"READY\"")) return client;
				client.closeQuietly();
			} catch (RejectedException e) {
				throw e;
			} catch (IOException e) {
				client.closeQuietly();
			}
		}
		return null;
	}

	/** Discord's reason, read without a JSON parser: 1.8.9 ships a Gson too old for the modern calls. */
	private static String message(String body) {
		Matcher m = REASON.matcher(body);
		return m.find() ? m.group(1) : body;
	}

	private static final Pattern REASON = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

	private static Channel openPipe(int index) {
		try {
			if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
				RandomAccessFile pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + index, "rw");
				return new Channel() {
					public void write(byte[] data) throws IOException {
						pipe.write(data);
					}

					public void readFully(byte[] into) throws IOException {
						pipe.readFully(into);
					}

					public void close() throws IOException {
						pipe.close();
					}
				};
			}
			SocketChannel socket = SocketChannel.open(java.net.UnixDomainSocketAddress.of(unixSocket(index)));
			return socketChannel(socket);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static Channel socketChannel(SocketChannel socket) {
		return new Channel() {
			public void write(byte[] data) throws IOException {
				ByteBuffer buffer = ByteBuffer.wrap(data);
				while (buffer.hasRemaining()) socket.write(buffer);
			}

			public void readFully(byte[] into) throws IOException {
				ByteBuffer buffer = ByteBuffer.wrap(into);
				while (buffer.hasRemaining()) {
					if (socket.read(buffer) < 0) throw new IOException("Discord closed the connection");
				}
			}

			public void close() throws IOException {
				socket.close();
			}
		};
	}

	private static Path unixSocket(int index) {
		for (String variable : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
			String dir = System.getenv(variable);
			if (dir != null && !dir.isEmpty()) return Path.of(dir, "discord-ipc-" + index);
		}
		return Path.of("/tmp", "discord-ipc-" + index);
	}

	/** Sets what Discord shows, or clears it when {@code activity} is null. */
	public void setActivity(JsonObject activity) throws IOException {
		JsonObject args = new JsonObject();
		args.addProperty("pid", (int) ProcessHandle.current().pid());
		if (activity != null) args.add("activity", activity);
		JsonObject frame = new JsonObject();
		frame.addProperty("cmd", "SET_ACTIVITY");
		frame.add("args", args);
		frame.addProperty("nonce", UUID.randomUUID().toString());
		send(OP_FRAME, frame.toString());
		// Discord answers every command; reading it keeps the pipe from filling up.
		Frame answer = readFrame();
		if (answer.opcode() == OP_CLOSE) throw new IOException("Discord closed the connection: " + message(answer.body()));
	}

	private record Frame(int opcode, String body) {
	}

	private void send(int opcode, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(opcode).putInt(body.length).put(body);
		channel.write(buffer.array());
	}

	private Frame readFrame() throws IOException {
		byte[] header = new byte[8];
		channel.readFully(header);
		ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int opcode = h.getInt();
		int length = h.getInt();
		if (length < 0 || length >= 1 << 20) throw new IOException("Discord sent a frame of " + length + " bytes");
		byte[] body = new byte[length];
		channel.readFully(body);
		return new Frame(opcode, new String(body, StandardCharsets.UTF_8));
	}

	@Override
	public void close() {
		try {
			send(OP_CLOSE, "{}");
		} catch (IOException ignored) {
			// Closing is best effort: Discord drops the presence when the pipe goes anyway.
		}
		closeQuietly();
	}

	private void closeQuietly() {
		try {
			channel.close();
		} catch (IOException ignored) {
			// Nothing useful to do with a failure to close.
		}
	}
}
