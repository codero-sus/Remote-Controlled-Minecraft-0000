package dev.rcm.discord;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;

/**
 * Pure Java 8+ RFC 6455 TLS WebSocket client tailored for Discord Gateway v10.
 * Handles SNI, multi-frame continuation reassembly, 16-bit and 64-bit payload lengths,
 * client frame masking, Ping/Pong frames, and clean close codes.
 */
public final class DiscordGatewaySocket {
	public interface Listener {
		void onTextMessage(String jsonText);

		void onClose(int code, String reason);

		void onError(Throwable error);
	}

	private final String url;
	private final Listener listener;
	private final SecureRandom random = new SecureRandom();
	private final Object writeLock = new Object();

	private SSLSocket socket;
	private InputStream in;
	private OutputStream out;
	private volatile boolean open = false;

	public DiscordGatewaySocket(String url, Listener listener) {
		this.url = url;
		this.listener = listener;
	}

	public boolean isOpen() {
		return open;
	}

	public void connectAndReadLoop() {
		int closeCode = 1006;
		String closeReason = "Disconnected";
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			int port = uri.getPort() > 0 ? uri.getPort() : 443;
			String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
			if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
				path = path + "?" + uri.getRawQuery();
			}

			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			socket = (SSLSocket) factory.createSocket();
			socket.connect(new InetSocketAddress(host, port), 15000);
			socket.setKeepAlive(true);
			SSLParameters sslParams = socket.getSSLParameters();
			sslParams.setServerNames(Collections.singletonList(new SNIHostName(host)));
			socket.setSSLParameters(sslParams);
			socket.startHandshake();

			in = socket.getInputStream();
			out = socket.getOutputStream();

			byte[] keyBytes = new byte[16];
			random.nextBytes(keyBytes);
			String wsKey = Base64.getEncoder().encodeToString(keyBytes);

			String handshake = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: " + host + "\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Sec-WebSocket-Key: " + wsKey + "\r\n"
				+ "Sec-WebSocket-Version: 13\r\n\r\n";

			out.write(handshake.getBytes(StandardCharsets.UTF_8));
			out.flush();

			String responseHeaders = readHttpHeaders(in);
			if (!responseHeaders.startsWith("HTTP/1.1 101") && !responseHeaders.contains(" 101 ")) {
				throw new IOException("WebSocket upgrade failed: " + responseHeaders.replace("\r\n", " | "));
			}

			open = true;
			ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream();
			int messageOpcode = -1;

			while (open) {
				int b0 = in.read();
				if (b0 == -1) {
					break;
				}
				int b1 = in.read();
				if (b1 == -1) {
					break;
				}

				boolean fin = (b0 & 0x80) != 0;
				int opcode = b0 & 0x0F;
				boolean masked = (b1 & 0x80) != 0;
				long payloadLen = b1 & 0x7F;

				if (payloadLen == 126L) {
					byte[] ext = readExact(in, 2);
					payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
				} else if (payloadLen == 127L) {
					byte[] ext = readExact(in, 8);
					payloadLen = 0L;
					for (int i = 0; i < 8; i++) {
						payloadLen = (payloadLen << 8) | (ext[i] & 0xFFL);
					}
				}

				byte[] maskKey = masked ? readExact(in, 4) : null;
				byte[] payload = readExact(in, (int) payloadLen);
				if (masked && maskKey != null) {
					for (int i = 0; i < payload.length; i++) {
						payload[i] ^= maskKey[i & 3];
					}
				}

				if (opcode == 0x8) { // Close
					if (payload.length >= 2) {
						closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
						if (payload.length > 2) {
							closeReason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
						}
					}
					open = false;
					break;
				} else if (opcode == 0x9) { // Ping -> Pong
					sendFrame(0xA, payload);
				} else if (opcode == 0xA) { // Pong
					// Ignore
				} else if (opcode == 0x1 || opcode == 0x2) { // Text / Binary start
					messageOpcode = opcode;
					messageBuffer.reset();
					messageBuffer.write(payload);
					if (fin) {
						if (messageOpcode == 0x1) {
							listener.onTextMessage(new String(messageBuffer.toByteArray(), StandardCharsets.UTF_8));
						}
						messageBuffer.reset();
						messageOpcode = -1;
					}
				} else if (opcode == 0x0) { // Continuation frame
					messageBuffer.write(payload);
					if (fin) {
						if (messageOpcode == 0x1) {
							listener.onTextMessage(new String(messageBuffer.toByteArray(), StandardCharsets.UTF_8));
						}
						messageBuffer.reset();
						messageOpcode = -1;
					}
				}
			}
		} catch (Throwable t) {
			if (open) {
				listener.onError(t);
			}
		} finally {
			open = false;
			closeQuietly();
			listener.onClose(closeCode, closeReason);
		}
	}

	public void sendText(String text) throws IOException {
		sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
	}

	public void close() {
		if (open) {
			try {
				sendFrame(0x8, new byte[]{0x03, (byte) 0xE8});
			} catch (IOException ignored) {
			}
		}
		open = false;
		closeQuietly();
	}

	private void closeQuietly() {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void sendFrame(int opcode, byte[] payload) throws IOException {
		synchronized (writeLock) {
			if (out == null) {
				throw new IOException("WebSocket output stream closed");
			}
			ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
			frame.write(0x80 | (opcode & 0x0F));

			int len = payload.length;
			if (len <= 125) {
				frame.write(0x80 | len);
			} else if (len <= 65535) {
				frame.write(0x80 | 126);
				frame.write((len >> 8) & 0xFF);
				frame.write(len & 0xFF);
			} else {
				frame.write(0x80 | 127);
				long l = len;
				for (int i = 7; i >= 0; i--) {
					frame.write((int) ((l >> (8 * i)) & 0xFFL));
				}
			}

			byte[] mask = new byte[4];
			random.nextBytes(mask);
			frame.write(mask);
			for (int i = 0; i < len; i++) {
				frame.write(payload[i] ^ mask[i & 3]);
			}

			out.write(frame.toByteArray());
			out.flush();
		}
	}

	private static String readHttpHeaders(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int matched = 0;
		byte[] target = new byte[]{'\r', '\n', '\r', '\n'};
		while (true) {
			int b = in.read();
			if (b == -1) {
				throw new EOFException("Unexpected EOF reading WebSocket HTTP response");
			}
			buf.write(b);
			if (b == target[matched]) {
				matched++;
				if (matched == 4) {
					break;
				}
			} else {
				matched = (b == '\r') ? 1 : 0;
			}
		}
		return new String(buf.toByteArray(), StandardCharsets.UTF_8);
	}

	private static byte[] readExact(InputStream in, int length) throws IOException {
		byte[] buf = new byte[length];
		int offset = 0;
		while (offset < length) {
			int n = in.read(buf, offset, length - offset);
			if (n == -1) {
				throw new EOFException("WebSocket EOF while reading frame payload");
			}
			offset += n;
		}
		return buf;
	}
}
