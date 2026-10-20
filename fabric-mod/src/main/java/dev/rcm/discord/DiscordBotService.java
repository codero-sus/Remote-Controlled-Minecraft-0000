package dev.rcm.discord;

import dev.rcm.core.CommandRouter;
import dev.rcm.core.MinecraftPlatform;
import dev.rcm.core.RcmConfig;
import dev.rcm.util.MiniJson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the persistent Discord Gateway v10 connection, Heartbeat scheduling,
 * automatic reconnect with fallback intents, Slash Command registration (`/mc`),
 * Interactive Button Panel updates, Prefix/DM command dispatch, and Live Chat Bridge.
 */
public final class DiscordBotService {
	private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
	// GUILDS (1<<0) | GUILD_MESSAGES (1<<9) | DIRECT_MESSAGES (1<<12) | MESSAGE_CONTENT (1<<15)
	private static final int FULL_INTENTS = (1 << 0) | (1 << 9) | (1 << 12) | (1 << 15);
	private static final int FALLBACK_INTENTS = (1 << 0) | (1 << 9) | (1 << 12);

	private final RcmConfig config;
	private final MinecraftPlatform platform;
	private final DiscordRestClient restClient;
	private CommandRouter router;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final ExecutorService dispatchExecutor = Executors.newCachedThreadPool();
	private ScheduledExecutorService heartbeatScheduler;
	private ScheduledFuture<?> heartbeatTask;
	private Thread gatewayThread;
	private volatile DiscordGatewaySocket activeSocket;

	private volatile Long lastSequence = null;
	private volatile String botUserId = "";
	private volatile String botTag = "Offline";
	private volatile String applicationId = "";
	private volatile boolean useFallbackIntents = false;

	public DiscordBotService(RcmConfig config, MinecraftPlatform platform, DiscordRestClient restClient) {
		this.config = config;
		this.platform = platform;
		this.restClient = restClient;
	}

	public void setRouter(CommandRouter router) {
		this.router = router;
	}

	public boolean isConnected() {
		DiscordGatewaySocket s = activeSocket;
		return s != null && s.isOpen();
	}

	public String getBotTag() {
		return botTag;
	}

	public synchronized void startOrRestart() {
		stop();
		String token = config.getBotToken();
		if (token == null || token.trim().isEmpty()) {
			botTag = "Unconfigured (set token via /rcm token <TOKEN> or config/rcm.json)";
			return;
		}
		restClient.setBotToken(token);
		running.set(true);
		useFallbackIntents = false;
		heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
		gatewayThread = new Thread(new Runnable() {
			@Override
			public void run() {
				connectionLoop();
			}
		}, "RCM-Discord-Gateway");
		gatewayThread.setDaemon(true);
		gatewayThread.start();
	}

	public synchronized void stop() {
		running.set(false);
		cancelHeartbeat();
		if (heartbeatScheduler != null) {
			heartbeatScheduler.shutdownNow();
			heartbeatScheduler = null;
		}
		DiscordGatewaySocket s = activeSocket;
		activeSocket = null;
		if (s != null) {
			s.close();
		}
		botTag = "Stopped";
	}

	public void forwardGameChatToDiscord(final String chatMessage) {
		if (!config.isChatBridgeEnabled() || !isConnected()) {
			return;
		}
		final String channelId = config.getChatBridgeChannelId();
		if (channelId == null || channelId.trim().isEmpty()) {
			return;
		}
		if (chatMessage == null || chatMessage.trim().isEmpty() || chatMessage.startsWith("[RCM]")) {
			return;
		}
		dispatchExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					restClient.sendMessage(channelId, "**[MC]** " + chatMessage, null, null);
				} catch (Exception ignored) {
				}
			}
		});
	}

	public void sendNotification(final String text) {
		final String channelId = !config.getCommandChannelId().isEmpty()
			? config.getCommandChannelId()
			: config.getChatBridgeChannelId();
		if (channelId == null || channelId.trim().isEmpty() || !isConnected()) {
			return;
		}
		dispatchExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					restClient.sendMessage(channelId, text, null, null);
				} catch (Exception ignored) {
				}
			}
		});
	}

	private void connectionLoop() {
		long backoffMs = 2000L;
		while (running.get()) {
			final AtomicBoolean disallowedIntents = new AtomicBoolean(false);
			DiscordGatewaySocket socket = new DiscordGatewaySocket(GATEWAY_URL, new DiscordGatewaySocket.Listener() {
				@Override
				public void onTextMessage(String jsonText) {
					handleGatewayPayload(jsonText);
				}

				@Override
				public void onClose(int code, String reason) {
					cancelHeartbeat();
					if (code == 4004) {
						botTag = "Authentication Failed (Invalid Bot Token)";
						running.set(false);
						platform.displayLocalMessage("Discord Bot Token rejected by Discord (4004). Check config/rcm.json.");
					} else if (code == 4014 && !useFallbackIntents) {
						useFallbackIntents = true;
						disallowedIntents.set(true);
						platform.displayLocalMessage("MESSAGE_CONTENT intent disabled in Discord Portal; reconnecting with Slash Commands + DMs + @mention mode.");
					}
				}

				@Override
				public void onError(Throwable error) {
					cancelHeartbeat();
				}
			});
			activeSocket = socket;
			socket.connectAndReadLoop();

			if (!running.get()) {
				break;
			}
			try {
				Thread.sleep(disallowedIntents.get() ? 500L : backoffMs);
			} catch (InterruptedException e) {
				break;
			}
			backoffMs = Math.min(30_000L, backoffMs * 2L);
		}
	}

	private void handleGatewayPayload(String jsonText) {
		Map<String, Object> packet = MiniJson.parseObject(jsonText);
		int op = MiniJson.getInt(packet, "op", -1);
		Object seqObj = packet.get("s");
		if (seqObj instanceof Number) {
			lastSequence = Long.valueOf(((Number) seqObj).longValue());
		}

		if (op == 10) { // HELLO
			Map<String, Object> d = MiniJson.asMap(packet.get("d"));
			long interval = MiniJson.getLong(d, "heartbeat_interval", 41250L);
			startHeartbeat(interval);
			sendIdentify();
		} else if (op == 1) { // Heartbeat requested immediately
			sendHeartbeatNow();
		} else if (op == 7) { // Reconnect requested
			DiscordGatewaySocket s = activeSocket;
			if (s != null) {
				s.close();
			}
		} else if (op == 0) { // Dispatch event
			String eventType = MiniJson.getString(packet, "t", "");
			final Map<String, Object> d = MiniJson.asMap(packet.get("d"));
			if ("READY".equals(eventType)) {
				Map<String, Object> user = MiniJson.asMap(d.get("user"));
				botUserId = MiniJson.getString(user, "id", "");
				String username = MiniJson.getString(user, "username", "Bot");
				Map<String, Object> app = MiniJson.asMap(d.get("application"));
				applicationId = MiniJson.getString(app, "id", botUserId);
				botTag = username + " (Online)";
				registerSlashCommandsAsync();
				String pairNotice = config.getAuthorizedUserIds().isEmpty()
					? "Connected to Discord as @" + username + "! Pair your account in Discord with: /mc run query:\"pair " + config.getPairingCode() + "\" or " + config.getPrefix() + " pair " + config.getPairingCode()
					: "Connected to Discord as @" + username + " (" + config.getAuthorizedUserIds().size() + " authorized user(s)).";
				platform.displayLocalMessage(pairNotice);
			} else if ("MESSAGE_CREATE".equals(eventType)) {
				dispatchExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handleMessageCreate(d);
					}
				});
			} else if ("INTERACTION_CREATE".equals(eventType)) {
				dispatchExecutor.submit(new Runnable() {
					@Override
					public void run() {
						handleInteractionCreate(d);
					}
				});
			}
		}
	}

	private void handleMessageCreate(Map<String, Object> d) {
		if (router == null) {
			return;
		}
		Map<String, Object> author = MiniJson.asMap(d.get("author"));
		if (MiniJson.getBoolean(author, "bot", false)) {
			return;
		}
		String authorId = MiniJson.getString(author, "id", "");
		String authorName = MiniJson.getString(author, "username", "Discord");
		String channelId = MiniJson.getString(d, "channel_id", "");
		String content = MiniJson.getString(d, "content", "").trim();

		List<CommandRouter.AttachmentInput> attachments = new ArrayList<CommandRouter.AttachmentInput>();
		for (Object rawAtt : MiniJson.asList(d.get("attachments"))) {
			Map<String, Object> att = MiniJson.asMap(rawAtt);
			String fn = MiniJson.getString(att, "filename", "upload.bin");
			String url = MiniJson.getString(att, "url", "");
			if (!url.isEmpty()) {
				attachments.add(new CommandRouter.AttachmentInput(fn, url));
			}
		}

		String prefix = config.getPrefix();
		String mentionA = "<@" + botUserId + ">";
		String mentionB = "<@!" + botUserId + ">";
		String commandBody = null;

		if (content.startsWith(prefix) && (content.length() == prefix.length() || Character.isWhitespace(content.charAt(prefix.length())))) {
			commandBody = content.substring(prefix.length()).trim();
		} else if (!botUserId.isEmpty() && content.startsWith(mentionA)) {
			commandBody = content.substring(mentionA.length()).trim();
		} else if (!botUserId.isEmpty() && content.startsWith(mentionB)) {
			commandBody = content.substring(mentionB.length()).trim();
		}

		if (commandBody != null) {
			CommandRouter.CommandResponse resp = router.dispatch(authorId, channelId, commandBody, attachments);
			try {
				if (resp.fileBytes != null && resp.fileName != null) {
					restClient.sendFile(channelId, resp.fileName, resp.fileBytes, resp.content, resp.embeds, resp.components);
				} else {
					restClient.sendMessage(channelId, resp.content, resp.embeds, resp.components);
				}
			} catch (Exception e) {
				System.err.println("[RCM] Failed sending Discord reply: " + e.getMessage());
			}
			return;
		}

		// Forward normal messages in 2-way chat bridge channel into Minecraft chat
		if (config.isChatBridgeEnabled()
			&& config.isForwardDiscordToGameChat()
			&& channelId.equals(config.getChatBridgeChannelId())
			&& !content.isEmpty()) {
			final String formatted = "[Discord] <" + authorName + "> " + content;
			platform.executeOnGameThread(new Runnable() {
				@Override
				public void run() {
					platform.sendChatMessage(formatted);
				}
			});
		}
	}

	private void handleInteractionCreate(Map<String, Object> d) {
		if (router == null) {
			return;
		}
		int type = MiniJson.getInt(d, "type", 0);
		String interactionId = MiniJson.getString(d, "id", "");
		String interactionToken = MiniJson.getString(d, "token", "");
		String channelId = MiniJson.getString(d, "channel_id", "");

		Map<String, Object> member = MiniJson.asMap(d.get("member"));
		Map<String, Object> user = member.containsKey("user")
			? MiniJson.asMap(member.get("user"))
			: MiniJson.asMap(d.get("user"));
		String userId = MiniJson.getString(user, "id", "");
		Map<String, Object> data = MiniJson.asMap(d.get("data"));

		try {
			if (type == 3) { // Button component press
				String customId = MiniJson.getString(data, "custom_id", "");
				// Type 6 = DEFERRED_UPDATE_MESSAGE so button spinner resolves smoothly even with screenshot upload
				restClient.respondInteraction(interactionId, interactionToken, 6, null, null, null);
				CommandRouter.CommandResponse resp = router.handleButtonPress(userId, customId);
				if (resp.fileBytes != null && resp.fileName != null) {
					restClient.editOriginalInteractionWithFile(
						applicationId, interactionToken, resp.fileName, resp.fileBytes,
						resp.content, resp.embeds, resp.components
					);
				} else {
					restClient.editOriginalInteraction(
						applicationId, interactionToken, resp.content, resp.embeds, resp.components
					);
				}
			} else if (type == 2) { // Application Slash Command `/mc`
				String cmdName = MiniJson.getString(data, "name", "");
				if (!"mc".equals(cmdName)) {
					return;
				}
				restClient.respondInteraction(interactionId, interactionToken, 5, null, null, null);
				String builtLine = extractSlashCommandLine(data);
				CommandRouter.CommandResponse resp = router.dispatch(
					userId, channelId, builtLine, Collections.<CommandRouter.AttachmentInput>emptyList()
				);
				if (resp.fileBytes != null && resp.fileName != null) {
					restClient.editOriginalInteractionWithFile(
						applicationId, interactionToken, resp.fileName, resp.fileBytes,
						resp.content, resp.embeds, resp.components
					);
				} else {
					restClient.editOriginalInteraction(
						applicationId, interactionToken, resp.content, resp.embeds, resp.components
					);
				}
			}
		} catch (Exception e) {
			System.err.println("[RCM] Interaction handling error: " + e.getMessage());
		}
	}

	private static String extractSlashCommandLine(Map<String, Object> data) {
		List<Object> opts = MiniJson.asList(data.get("options"));
		if (opts.isEmpty()) {
			return "panel";
		}
		Map<String, Object> sub = MiniJson.asMap(opts.get(0));
		String subName = MiniJson.getString(sub, "name", "panel");
		List<Object> subOpts = MiniJson.asList(sub.get("options"));
		if ("run".equals(subName)) {
			return subOpts.isEmpty() ? "help" : MiniJson.getString(MiniJson.asMap(subOpts.get(0)), "value", "help");
		}
		StringBuilder sb = new StringBuilder(subName);
		for (Object o : subOpts) {
			Map<String, Object> param = MiniJson.asMap(o);
			Object v = param.get("value");
			if (v != null) {
				sb.append(' ').append(v);
			}
		}
		return sb.toString();
	}

	private void registerSlashCommandsAsync() {
		if (applicationId == null || applicationId.isEmpty()) {
			return;
		}
		dispatchExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					Map<String, Object> rootCmd = new LinkedHashMap<String, Object>();
					rootCmd.put("name", "mc");
					rootCmd.put("description", "Control and monitor Minecraft remotely");
					List<Object> subcommands = Arrays.<Object>asList(
						subcommand("panel", "Open interactive live remote control pad with viewport"),
						subcommand("status", "Show live player, server, coordinates, and vitals telemetry"),
						subcommand("screenshot", "Capture a live PNG screenshot of Minecraft"),
						subcommand("inventory", "Inspect equipped items, armor, hotbar, and full inventory"),
						subcommand("stop", "Emergency stop all held keys, macros, and navigation"),
						subcommandWithArg("pair", "Pair your Discord account with the 6-digit code", "code", "6-digit pairing code"),
						subcommandWithArg("cmd", "Execute any Minecraft command", "command", "Command e.g. gamemode creative"),
						subcommandWithArg("say", "Send a chat message in Minecraft", "message", "Message text"),
						subcommandWithArg("run", "Run any full RCM command (move, goto, files read/write/edit, exec, macro)", "query", "Full RCM command e.g. files ls mods")
					);
					rootCmd.put("options", subcommands);
					restClient.registerGlobalCommands(applicationId, Collections.<Object>singletonList(rootCmd));
				} catch (Exception ignored) {
				}
			}
		});
	}

	private static Map<String, Object> subcommand(String name, String desc) {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		m.put("type", Integer.valueOf(1));
		m.put("name", name);
		m.put("description", desc);
		return m;
	}

	private static Map<String, Object> subcommandWithArg(String name, String desc, String argName, String argDesc) {
		Map<String, Object> m = subcommand(name, desc);
		Map<String, Object> opt = new LinkedHashMap<String, Object>();
		opt.put("type", Integer.valueOf(3));
		opt.put("name", argName);
		opt.put("description", argDesc);
		opt.put("required", Boolean.TRUE);
		m.put("options", Collections.<Object>singletonList(opt));
		return m;
	}

	private void sendIdentify() {
		DiscordGatewaySocket s = activeSocket;
		if (s == null || !s.isOpen()) {
			return;
		}
		Map<String, Object> props = new LinkedHashMap<String, Object>();
		props.put("os", System.getProperty("os.name", "linux"));
		props.put("browser", "rcm-fabric");
		props.put("device", "rcm-fabric");

		Map<String, Object> presenceGame = new LinkedHashMap<String, Object>();
		presenceGame.put("name", "Minecraft " + platform.getMinecraftVersion());
		presenceGame.put("type", Integer.valueOf(0));

		Map<String, Object> presence = new LinkedHashMap<String, Object>();
		presence.put("activities", Collections.<Object>singletonList(presenceGame));
		presence.put("status", "online");
		presence.put("afk", Boolean.FALSE);

		Map<String, Object> d = new LinkedHashMap<String, Object>();
		d.put("token", config.getBotToken());
		d.put("intents", Integer.valueOf(useFallbackIntents ? FALLBACK_INTENTS : FULL_INTENTS));
		d.put("properties", props);
		d.put("presence", presence);

		Map<String, Object> identify = new LinkedHashMap<String, Object>();
		identify.put("op", Integer.valueOf(2));
		identify.put("d", d);
		try {
			s.sendText(MiniJson.stringify(identify));
		} catch (Exception ignored) {
		}
	}

	private void startHeartbeat(long intervalMs) {
		cancelHeartbeat();
		if (heartbeatScheduler == null || heartbeatScheduler.isShutdown()) {
			return;
		}
		heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				sendHeartbeatNow();
			}
		}, intervalMs / 2L, intervalMs, TimeUnit.MILLISECONDS);
	}

	private void sendHeartbeatNow() {
		DiscordGatewaySocket s = activeSocket;
		if (s == null || !s.isOpen()) {
			return;
		}
		Map<String, Object> hb = new LinkedHashMap<String, Object>();
		hb.put("op", Integer.valueOf(1));
		hb.put("d", lastSequence);
		try {
			s.sendText(MiniJson.stringify(hb));
		} catch (Exception ignored) {
		}
	}

	private void cancelHeartbeat() {
		if (heartbeatTask != null) {
			heartbeatTask.cancel(true);
			heartbeatTask = null;
		}
	}
}
