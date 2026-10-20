package dev.rcm.core;

import dev.rcm.util.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent configuration and owner pairing authentication for Remote Controlled Minecraft.
 */
public final class RcmConfig {
	private final Path configFile;
	private String botToken = "";
	private final List<String> authorizedUserIds = new ArrayList<String>();
	private String commandChannelId = "";
	private String chatBridgeChannelId = "";
	private boolean chatBridgeEnabled = true;
	private boolean forwardDiscordToGameChat = false;
	private String prefix = "!mc";
	private boolean autoScreenshotOnPanel = true;
	private boolean allowShellCommands = true;
	private boolean allowUnrestrictedPaths = true;
	private String pairingCode;

	public RcmConfig(Path configFile) {
		this.configFile = configFile;
		this.pairingCode = generatePairingCode();
	}

	private static String generatePairingCode() {
		int num = 100000 + new SecureRandom().nextInt(900000);
		return String.valueOf(num);
	}

	public synchronized void regeneratePairingCode() {
		this.pairingCode = generatePairingCode();
	}

	public synchronized String getPairingCode() {
		return pairingCode;
	}

	public synchronized void load() throws IOException {
		if (Files.exists(configFile)) {
			String raw = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			Map<String, Object> map = MiniJson.parseObject(raw);
			this.botToken = MiniJson.getString(map, "botToken", "").trim();
			this.commandChannelId = MiniJson.getString(map, "commandChannelId", "").trim();
			this.chatBridgeChannelId = MiniJson.getString(map, "chatBridgeChannelId", "").trim();
			this.chatBridgeEnabled = MiniJson.getBoolean(map, "chatBridgeEnabled", true);
			this.forwardDiscordToGameChat = MiniJson.getBoolean(map, "forwardDiscordToGameChat", false);
			this.prefix = MiniJson.getString(map, "prefix", "!mc").trim();
			if (this.prefix.isEmpty()) {
				this.prefix = "!mc";
			}
			this.autoScreenshotOnPanel = MiniJson.getBoolean(map, "autoScreenshotOnPanel", true);
			this.allowShellCommands = MiniJson.getBoolean(map, "allowShellCommands", true);
			this.allowUnrestrictedPaths = MiniJson.getBoolean(map, "allowUnrestrictedPaths", true);
			this.authorizedUserIds.clear();
			for (Object o : MiniJson.asList(map.get("authorizedUserIds"))) {
				String id = String.valueOf(o).trim();
				if (!id.isEmpty() && !this.authorizedUserIds.contains(id)) {
					this.authorizedUserIds.add(id);
				}
			}
		} else {
			save();
		}

		// Allow environment variable overrides for CI / headless Docker servers
		String envToken = System.getenv("RCM_DISCORD_TOKEN");
		if (envToken != null && !envToken.trim().isEmpty()) {
			this.botToken = envToken.trim();
		}
		String envChannel = System.getenv("RCM_CHANNEL_ID");
		if (envChannel != null && !envChannel.trim().isEmpty()) {
			this.commandChannelId = envChannel.trim();
		}
		String envUsers = System.getenv("RCM_AUTHORIZED_USERS");
		if (envUsers != null && !envUsers.trim().isEmpty()) {
			for (String part : envUsers.split(",")) {
				String id = part.trim();
				if (!id.isEmpty() && !this.authorizedUserIds.contains(id)) {
					this.authorizedUserIds.add(id);
				}
			}
		}
	}

	public synchronized void save() throws IOException {
		if (configFile.getParent() != null) {
			Files.createDirectories(configFile.getParent());
		}
		Map<String, Object> out = new LinkedHashMap<String, Object>();
		out.put("botToken", botToken);
		out.put("authorizedUserIds", new ArrayList<String>(authorizedUserIds));
		out.put("commandChannelId", commandChannelId);
		out.put("chatBridgeChannelId", chatBridgeChannelId);
		out.put("chatBridgeEnabled", Boolean.valueOf(chatBridgeEnabled));
		out.put("forwardDiscordToGameChat", Boolean.valueOf(forwardDiscordToGameChat));
		out.put("prefix", prefix);
		out.put("autoScreenshotOnPanel", Boolean.valueOf(autoScreenshotOnPanel));
		out.put("allowShellCommands", Boolean.valueOf(allowShellCommands));
		out.put("allowUnrestrictedPaths", Boolean.valueOf(allowUnrestrictedPaths));
		String json = MiniJson.stringifyPretty(out) + "\n";
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
	}

	public synchronized boolean isAuthorized(String userId) {
		return userId != null && authorizedUserIds.contains(userId.trim());
	}

	public synchronized boolean isChannelAllowed(String channelId) {
		if (commandChannelId == null || commandChannelId.trim().isEmpty()) {
			return true;
		}
		return commandChannelId.trim().equals(channelId);
	}

	public synchronized boolean tryPair(String userId, String suppliedCode) {
		if (userId == null || suppliedCode == null) {
			return false;
		}
		if (pairingCode.equals(suppliedCode.trim())) {
			if (!authorizedUserIds.contains(userId.trim())) {
				authorizedUserIds.add(userId.trim());
			}
			regeneratePairingCode();
			try {
				save();
			} catch (IOException ignored) {
			}
			return true;
		}
		return false;
	}

	public synchronized Path getConfigFile() {
		return configFile;
	}

	public synchronized String getBotToken() {
		return botToken;
	}

	public synchronized void setBotToken(String botToken) throws IOException {
		this.botToken = botToken == null ? "" : botToken.trim();
		save();
	}

	public synchronized List<String> getAuthorizedUserIds() {
		return new ArrayList<String>(authorizedUserIds);
	}

	public synchronized String getCommandChannelId() {
		return commandChannelId;
	}

	public synchronized void setCommandChannelId(String channelId) throws IOException {
		this.commandChannelId = channelId == null ? "" : channelId.trim();
		save();
	}

	public synchronized String getChatBridgeChannelId() {
		return chatBridgeChannelId;
	}

	public synchronized void setChatBridgeChannelId(String channelId) throws IOException {
		this.chatBridgeChannelId = channelId == null ? "" : channelId.trim();
		save();
	}

	public synchronized boolean isChatBridgeEnabled() {
		return chatBridgeEnabled;
	}

	public synchronized void setChatBridgeEnabled(boolean enabled) throws IOException {
		this.chatBridgeEnabled = enabled;
		save();
	}

	public synchronized boolean isForwardDiscordToGameChat() {
		return forwardDiscordToGameChat;
	}

	public synchronized void setForwardDiscordToGameChat(boolean forward) throws IOException {
		this.forwardDiscordToGameChat = forward;
		save();
	}

	public synchronized String getPrefix() {
		return prefix;
	}

	public synchronized boolean isAutoScreenshotOnPanel() {
		return autoScreenshotOnPanel;
	}

	public synchronized boolean isAllowShellCommands() {
		return allowShellCommands;
	}

	public synchronized boolean isAllowUnrestrictedPaths() {
		return allowUnrestrictedPaths;
	}
}
