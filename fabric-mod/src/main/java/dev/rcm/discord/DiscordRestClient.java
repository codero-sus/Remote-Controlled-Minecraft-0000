package dev.rcm.discord;

import dev.rcm.util.MiniJson;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pure Java 8+ Discord HTTP v10 REST API client supporting JSON requests,
 * multipart/form-data file/screenshot uploads, PATCH webhook edits over TLS sockets
 * (avoiding HttpURLConnection's lack of PATCH support), slash command registration,
 * and CDN attachment downloads.
 */
public final class DiscordRestClient {
	private static final String API_HOST = "discord.com";
	private static final String API_BASE = "https://discord.com/api/v10";
	private static final String USER_AGENT = "DiscordBot (https://github.com/codero-sus/Remote-Controlled-Minecraft-0000, 2.0.0)";

	private volatile String botToken;

	public DiscordRestClient(String botToken) {
		this.botToken = botToken;
	}

	public void setBotToken(String botToken) {
		this.botToken = botToken;
	}

	public Map<String, Object> sendMessage(
		String channelId,
		String content,
		List<Object> embeds,
		List<Object> components
	) throws IOException {
		Map<String, Object> payload = buildMessagePayload(content, embeds, components);
		return MiniJson.parseObject(requestJson("POST", "/channels/" + channelId + "/messages", MiniJson.stringify(payload)));
	}

	public Map<String, Object> sendFile(
		String channelId,
		String fileName,
		byte[] fileBytes,
		String content,
		List<Object> embeds,
		List<Object> components
	) throws IOException {
		Map<String, Object> payload = buildMessagePayload(content, embeds, components);
		String jsonPart = MiniJson.stringify(payload);
		return MiniJson.parseObject(requestMultipart("POST", "/channels/" + channelId + "/messages", jsonPart, fileName, fileBytes));
	}

	public void respondInteraction(
		String interactionId,
		String interactionToken,
		int callbackType,
		String content,
		List<Object> embeds,
		List<Object> components
	) throws IOException {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("type", Integer.valueOf(callbackType));
		if (content != null || embeds != null || components != null) {
			body.put("data", buildMessagePayload(content, embeds, components));
		}
		requestJson("POST", "/interactions/" + interactionId + "/" + interactionToken + "/callback", MiniJson.stringify(body));
	}

	public void editOriginalInteraction(
		String applicationId,
		String interactionToken,
		String content,
		List<Object> embeds,
		List<Object> components
	) throws IOException {
		Map<String, Object> payload = buildMessagePayload(content, embeds, components);
		rawTlsRequest(
			"PATCH",
			"/api/v10/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original",
			"application/json; charset=utf-8",
			MiniJson.stringify(payload).getBytes(StandardCharsets.UTF_8)
		);
	}

	public void editOriginalInteractionWithFile(
		String applicationId,
		String interactionToken,
		String fileName,
		byte[] fileBytes,
		String content,
		List<Object> embeds,
		List<Object> components
	) throws IOException {
		Map<String, Object> payload = buildMessagePayload(content, embeds, components);
		payload.put("attachments", Collections.emptyList());
		String boundary = "----RcmDiscordBoundary" + UUID.randomUUID().toString().replace("-", "");
		byte[] multipartBody = buildMultipartBytes(boundary, MiniJson.stringify(payload), fileName, fileBytes);
		rawTlsRequest(
			"PATCH",
			"/api/v10/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original",
			"multipart/form-data; boundary=" + boundary,
			multipartBody
		);
	}

	public void registerGlobalCommands(String applicationId, List<Object> commands) throws IOException {
		requestJson("PUT", "/applications/" + applicationId + "/commands", MiniJson.stringify(commands));
	}

	public byte[] downloadUrl(String urlString) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(30000);
		conn.setRequestProperty("User-Agent", USER_AGENT);
		int code = conn.getResponseCode();
		InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
		try {
			byte[] data = readAll(in);
			if (code < 200 || code >= 300) {
				throw new IOException("Attachment download failed with HTTP " + code);
			}
			return data;
		} finally {
			if (in != null) {
				in.close();
			}
		}
	}

	private static Map<String, Object> buildMessagePayload(
		String content,
		List<Object> embeds,
		List<Object> components
	) {
		Map<String, Object> payload = new LinkedHashMap<String, Object>();
		if (content != null) {
			payload.put("content", truncate(content, 1990));
		}
		if (embeds != null) {
			payload.put("embeds", embeds);
		}
		if (components != null) {
			payload.put("components", components);
		}
		return payload;
	}

	private String requestJson(String method, String endpoint, String jsonBody) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) new URL(API_BASE + endpoint).openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(30000);
		conn.setRequestProperty("Authorization", "Bot " + botToken);
		conn.setRequestProperty("User-Agent", USER_AGENT);
		conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		if (jsonBody != null) {
			conn.setDoOutput(true);
			byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
			OutputStream os = conn.getOutputStream();
			try {
				os.write(out);
			} finally {
				os.close();
			}
		}
		return readResponse(conn);
	}

	private String requestMultipart(
		String method,
		String endpoint,
		String payloadJson,
		String fileName,
		byte[] fileBytes
	) throws IOException {
		String boundary = "----RcmDiscordBoundary" + UUID.randomUUID().toString().replace("-", "");
		byte[] body = buildMultipartBytes(boundary, payloadJson, fileName, fileBytes);
		HttpURLConnection conn = (HttpURLConnection) new URL(API_BASE + endpoint).openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(45000);
		conn.setRequestProperty("Authorization", "Bot " + botToken);
		conn.setRequestProperty("User-Agent", USER_AGENT);
		conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
		conn.setDoOutput(true);
		OutputStream os = conn.getOutputStream();
		try {
			os.write(body);
		} finally {
			os.close();
		}
		return readResponse(conn);
	}

	private static byte[] buildMultipartBytes(
		String boundary,
		String payloadJson,
		String fileName,
		byte[] fileBytes
	) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		String crlf = "\r\n";
		baos.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Disposition: form-data; name=\"payload_json\"" + crlf).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Type: application/json; charset=utf-8" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
		baos.write(payloadJson.getBytes(StandardCharsets.UTF_8));
		baos.write(crlf.getBytes(StandardCharsets.UTF_8));

		String safeName = fileName.replace("\"", "_");
		baos.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + safeName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Type: " + guessMimeType(safeName) + crlf + crlf).getBytes(StandardCharsets.UTF_8));
		baos.write(fileBytes);
		baos.write(crlf.getBytes(StandardCharsets.UTF_8));
		baos.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
		return baos.toByteArray();
	}

	private String rawTlsRequest(String method, String path, String contentType, byte[] body) throws IOException {
		SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
		SSLSocket sock = (SSLSocket) factory.createSocket();
		try {
			sock.connect(new InetSocketAddress(API_HOST, 443), 15000);
			sock.setSoTimeout(30000);
			SSLParameters params = sock.getSSLParameters();
			params.setServerNames(Collections.singletonList(new SNIHostName(API_HOST)));
			sock.setSSLParameters(params);
			sock.startHandshake();

			StringBuilder headers = new StringBuilder();
			headers.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
			headers.append("Host: ").append(API_HOST).append("\r\n");
			headers.append("Authorization: Bot ").append(botToken).append("\r\n");
			headers.append("User-Agent: ").append(USER_AGENT).append("\r\n");
			headers.append("Content-Type: ").append(contentType).append("\r\n");
			headers.append("Content-Length: ").append(body.length).append("\r\n");
			headers.append("Connection: close\r\n\r\n");

			OutputStream out = sock.getOutputStream();
			out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
			out.write(body);
			out.flush();

			byte[] respBytes = readAll(sock.getInputStream());
			String raw = new String(respBytes, StandardCharsets.UTF_8);
			int sep = raw.indexOf("\r\n\r\n");
			return sep >= 0 ? raw.substring(sep + 4) : raw;
		} finally {
			try {
				sock.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static String readResponse(HttpURLConnection conn) throws IOException {
		int status = conn.getResponseCode();
		InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
		String text = "";
		if (in != null) {
			try {
				text = new String(readAll(in), StandardCharsets.UTF_8);
			} finally {
				in.close();
			}
		}
		if (status < 200 || status >= 300) {
			throw new IOException("Discord HTTP " + status + ": " + text);
		}
		return text;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		if (in == null) {
			return new byte[0];
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private static String guessMimeType(String name) {
		String low = name.toLowerCase(Locale.ROOT);
		if (low.endsWith(".png")) {
			return "image/png";
		}
		if (low.endsWith(".jpg") || low.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (low.endsWith(".json")) {
			return "application/json";
		}
		if (low.endsWith(".txt") || low.endsWith(".log") || low.endsWith(".properties") || low.endsWith(".toml")) {
			return "text/plain; charset=utf-8";
		}
		if (low.endsWith(".zip") || low.endsWith(".jar")) {
			return "application/zip";
		}
		return "application/octet-stream";
	}

	public static String truncate(String s, int max) {
		if (s == null || s.length() <= max) {
			return s;
		}
		return s.substring(0, Math.max(0, max - 18)) + "\n...(truncated)...";
	}
}
