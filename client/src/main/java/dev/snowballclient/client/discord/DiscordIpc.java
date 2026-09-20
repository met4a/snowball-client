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

	private final RandomAccessFile pipe;
	private final SocketChannel socket;

	private DiscordIpc(RandomAccessFile pipe, SocketChannel socket) {
		this.pipe = pipe;
		this.socket = socket;
	}

	/** @return a connected client, or null when Discord is not running on this computer */
	public static DiscordIpc connect(String applicationId) {
		for (int i = 0; i < MAX_PIPES; i++) {
			DiscordIpc client = open(i);
			if (client == null) continue;
			try {
				JsonObject handshake = new JsonObject();
				handshake.addProperty("v", 1);
				handshake.addProperty("client_id", applicationId);
				client.send(OP_HANDSHAKE, handshake.toString());
				client.readFrame();
				return client;
			} catch (IOException e) {
				client.closeQuietly();
			}
		}
		return null;
	}

	private static DiscordIpc open(int index) {
		try {
			if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
				return new DiscordIpc(new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + index, "rw"), null);
			}
			Path socketPath = unixSocket(index);
			if (socketPath == null) return null;
			SocketChannel channel = SocketChannel.open(java.net.UnixDomainSocketAddress.of(socketPath));
			return new DiscordIpc(null, channel);
		} catch (IOException | RuntimeException e) {
			return null;
		}
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
		readFrame();
	}

	private void send(int opcode, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(opcode).putInt(body.length).put(body).flip();
		if (pipe != null) pipe.write(buffer.array());
		else while (buffer.hasRemaining()) socket.write(buffer);
	}

	private void readFrame() throws IOException {
		ByteBuffer header = readFully(8);
		header.order(ByteOrder.LITTLE_ENDIAN);
		header.getInt();
		int length = header.getInt();
		if (length > 0 && length < 1 << 20) readFully(length);
	}

	private ByteBuffer readFully(int length) throws IOException {
		byte[] data = new byte[length];
		if (pipe != null) {
			pipe.readFully(data);
			return ByteBuffer.wrap(data);
		}
		ByteBuffer buffer = ByteBuffer.wrap(data);
		while (buffer.hasRemaining()) {
			if (socket.read(buffer) < 0) throw new IOException("Discord closed the connection");
		}
		return ByteBuffer.wrap(data);
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
			if (pipe != null) pipe.close();
			if (socket != null) socket.close();
		} catch (IOException ignored) {
			// Nothing useful to do with a failure to close.
		}
	}
}
