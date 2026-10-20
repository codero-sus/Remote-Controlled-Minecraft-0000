package dev.rcm.core;

import dev.rcm.discord.DiscordBotService;
import dev.rcm.discord.DiscordRestClient;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Singleton coordinator wiring the active MinecraftPlatform adapter, Discord bot service,
 * tick-based motion controller, Brigadier in-game commands, and chat bridge.
 */
public final class RcmRuntime {
	private static volatile MinecraftPlatform platform;
	private static volatile RcmConfig config;
	private static volatile MotionController motionController;
	private static volatile FileControlManager fileManager;
	private static volatile DiscordBotService discordService;
	private static volatile CommandRouter commandRouter;
	private static volatile boolean initialized = false;

	private RcmRuntime() {
	}

	public static synchronized void initialize(MinecraftPlatform platformImpl) {
		if (initialized) {
			return;
		}
		platform = platformImpl;
		config = new RcmConfig(platformImpl.getConfigDir().resolve("rcm.json"));
		try {
			config.load();
		} catch (Exception e) {
			System.err.println("[RCM] Warning loading config: " + e.getMessage());
		}

		motionController = new MotionController();
		fileManager = new FileControlManager(platformImpl.getGameDir(), config);
		DiscordRestClient restClient = new DiscordRestClient(config.getBotToken());
		discordService = new DiscordBotService(config, platformImpl, restClient);
		commandRouter = new CommandRouter(
			config,
			platformImpl,
			motionController,
			fileManager,
			restClient,
			new Consumer<String>() {
				@Override
				public void accept(String msg) {
					discordService.sendNotification(msg);
				}
			}
		);
		discordService.setRouter(commandRouter);
		initialized = true;

		System.out.println("[RCM] Initialized Remote Controlled Minecraft for MC " + platformImpl.getMinecraftVersion());
		System.out.println("[RCM] Config path: " + config.getConfigFile());
		System.out.println("[RCM] 6-digit Discord pairing code: " + config.getPairingCode());

		if (!config.getBotToken().isEmpty()) {
			discordService.startOrRestart();
		} else {
			System.out.println("[RCM] Set your Discord bot token via `/rcm token <BOT_TOKEN>` in-game or in " + config.getConfigFile());
		}

		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				if (discordService != null) {
					discordService.stop();
				}
			}
		}, "RCM-Shutdown"));
	}

	public static void onClientTick(Object clientInstance) {
		if (!initialized || platform == null) {
			return;
		}
		platform.onClientTick(clientInstance);
		motionController.tick(platform);
	}

	public static void onServerTick(Object serverInstance) {
		if (!initialized || platform == null) {
			return;
		}
		boolean firstBind = platform.getActiveServer() != serverInstance;
		platform.onServerTick(serverInstance);
		if (firstBind && serverInstance != null) {
			BrigadierRegistrar.registerOnServer(serverInstance, new Function<String, String>() {
				@Override
				public String apply(String rawArgs) {
					return handleLocalCommand(rawArgs);
				}
			}, platform);
		}
	}

	public static void onServerStop(Object serverInstance) {
		if (!initialized || platform == null) {
			return;
		}
		platform.onServerStop(serverInstance);
	}

	public static void onChatMessage(String text) {
		if (!initialized || discordService == null || text == null) {
			return;
		}
		discordService.forwardGameChatToDiscord(text);
	}

	public static boolean interceptOutgoingClientChat(String message) {
		if (!initialized || message == null) {
			return false;
		}
		String trimmed = message.trim();
		if (!trimmed.startsWith(".rcm")) {
			return false;
		}
		String args = trimmed.length() > 4 ? trimmed.substring(4).trim() : "status";
		String reply = handleLocalCommand(args);
		if (platform != null) {
			platform.displayLocalMessage(reply);
		}
		return true;
	}

	public static String handleLocalCommand(String rawArgs) {
		if (rawArgs == null || rawArgs.trim().isEmpty()) {
			rawArgs = "status";
		}
		String trimmed = rawArgs.trim();
		int sp = trimmed.indexOf(' ');
		String cmd = (sp < 0 ? trimmed : trimmed.substring(0, sp)).toLowerCase(Locale.ROOT);
		String rest = sp < 0 ? "" : trimmed.substring(sp + 1).trim();

		try {
			switch (cmd) {
				case "status":
					return "Discord: " + discordService.getBotTag()
						+ " | Pairing code: " + config.getPairingCode()
						+ " | Authorized IDs: " + config.getAuthorizedUserIds().size()
						+ " | Config: " + config.getConfigFile();
				case "pair":
					return "Run in Discord: `" + config.getPrefix() + " pair " + config.getPairingCode()
						+ "` or `/mc pair code:" + config.getPairingCode() + "`";
				case "token":
					if (rest.isEmpty()) {
						return "Usage: `/rcm token <DISCORD_BOT_TOKEN>` (or `.rcm token <DISCORD_BOT_TOKEN>` on multiplayer servers)";
					}
					config.setBotToken(rest);
					discordService.startOrRestart();
					return "Saved Discord Bot Token to `" + config.getConfigFile() + "` and connecting to Discord Gateway!";
				case "channel":
					config.setCommandChannelId(rest);
					return "Set command channel filter to: " + (rest.isEmpty() ? "(any channel)" : rest);
				case "bridge":
					if ("off".equalsIgnoreCase(rest)) {
						config.setChatBridgeEnabled(false);
						return "Chat bridge disabled.";
					}
					if (!rest.isEmpty() && !"on".equalsIgnoreCase(rest)) {
						config.setChatBridgeChannelId(rest);
					}
					config.setChatBridgeEnabled(true);
					return "Chat bridge enabled (channel: " + config.getChatBridgeChannelId() + ").";
				case "reload":
					config.load();
					discordService.startOrRestart();
					return "Reloaded config from `" + config.getConfigFile() + "` and restarted Discord connection.";
				default:
					return "Commands: `status`, `pair`, `token <bot_token>`, `channel [channel_id]`, `bridge <on|off|channel_id>`, `reload`";
			}
		} catch (Exception e) {
			return "RCM error: " + e.getMessage();
		}
	}
}
