package dev.rcm.core;

import dev.rcm.discord.DiscordRestClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Central command, button-panel, file-management, macro, and shell-control router.
 */
public final class CommandRouter {
	public static final class AttachmentInput {
		public final String fileName;
		public final String url;

		public AttachmentInput(String fileName, String url) {
			this.fileName = fileName;
			this.url = url;
		}
	}

	public static final class CommandResponse {
		public String content;
		public List<Object> embeds;
		public List<Object> components;
		public String fileName;
		public byte[] fileBytes;

		public static CommandResponse text(String msg) {
			CommandResponse r = new CommandResponse();
			r.content = msg;
			return r;
		}
	}

	private final RcmConfig config;
	private final MinecraftPlatform platform;
	private final MotionController motion;
	private final FileControlManager files;
	private final DiscordRestClient restClient;
	private final Consumer<String> asyncChannelNotifier;
	private final ExecutorService workerPool = Executors.newCachedThreadPool();

	public CommandRouter(
		RcmConfig config,
		MinecraftPlatform platform,
		MotionController motion,
		FileControlManager files,
		DiscordRestClient restClient,
		Consumer<String> asyncChannelNotifier
	) {
		this.config = config;
		this.platform = platform;
		this.motion = motion;
		this.files = files;
		this.restClient = restClient;
		this.asyncChannelNotifier = asyncChannelNotifier;
	}

	public CommandResponse dispatch(
		String userId,
		String channelId,
		String commandLine,
		List<AttachmentInput> attachments
	) {
		if (commandLine == null) {
			commandLine = "help";
		}
		String trimmed = commandLine.trim();
		String firstToken = firstWord(trimmed).toLowerCase(Locale.ROOT);
		String rest = afterFirstWord(trimmed);

		if ("pair".equals(firstToken)) {
			if (config.tryPair(userId, rest)) {
				return CommandResponse.text(
					"Paired successfully! Your Discord user ID (`" + userId + "`) is now saved in `"
						+ config.getConfigFile() + "`. Run `!mc panel` or `/mc panel` to open the live controller!"
				);
			}
			return CommandResponse.text(
				"Invalid pairing code. Run `/rcm pair` in Minecraft (or check the game console log) for the current 6-digit pairing code."
			);
		}

		if (!config.isAuthorized(userId)) {
			return CommandResponse.text(
				"Access denied for Discord user `" + userId + "`. Pair first with `/mc pair <code>` or `"
					+ config.getPrefix() + " pair <code>` using the 6-digit code shown in the Minecraft console / `/rcm pair`."
			);
		}

		if (!config.isChannelAllowed(channelId)) {
			return CommandResponse.text(
				"Commands are restricted to channel `<#" + config.getCommandChannelId() + ">`."
			);
		}

		try {
			switch (firstToken) {
				case "":
				case "help":
					return buildHelpResponse();
				case "status":
				case "info":
					return buildStatusResponse(false);
				case "panel":
				case "pad":
				case "gui":
					return buildPanelResponse("Live Remote Control Panel ready.", config.isAutoScreenshotOnPanel());
				case "screenshot":
				case "screen":
				case "snap":
					return buildScreenshotResponse("Live Minecraft Screenshot");
				case "move":
					return handleMove(rest);
				case "look":
					return handleRotation(rest, false);
				case "turn":
					return handleRotation(rest, true);
				case "goto":
				case "walkto":
					return handleGoto(rest);
				case "attack":
				case "hit":
					return handleAttackOrUse("attack", rest);
				case "mine":
					return CommandResponse.text(runOnGameThreadSync(() ->
						motion.holdKey("attack", parseLongOrDefault(rest, 2500L))));
				case "use":
				case "interact":
				case "place":
					return handleAttackOrUse("use", rest);
				case "eat":
					return CommandResponse.text(runOnGameThreadSync(() ->
						motion.holdKey("use", parseLongOrDefault(rest, 1800L))));
				case "slot":
				case "hotbar": {
					int slot = Integer.parseInt(rest.trim());
					if (slot < 1 || slot > 9) {
						return CommandResponse.text("Hotbar slot must be between 1 and 9.");
					}
					return CommandResponse.text(runOnGameThreadSync(() -> platform.selectHotbarSlot(slot - 1)));
				}
				case "swap":
				case "offhand":
					return CommandResponse.text(runOnGameThreadSync(() -> platform.triggerAction("swap", false)));
				case "drop": {
					final boolean stack = "all".equalsIgnoreCase(rest.trim()) || "stack".equalsIgnoreCase(rest.trim());
					return CommandResponse.text(runOnGameThreadSync(() -> platform.triggerAction("drop", stack)));
				}
				case "respawn":
					return CommandResponse.text(runOnGameThreadSync(() -> platform.triggerAction("respawn", false)));
				case "disconnect":
				case "leave":
					return CommandResponse.text(runOnGameThreadSync(() -> platform.triggerAction("disconnect", false)));
				case "connect":
				case "join":
					if (rest.trim().isEmpty()) {
						return CommandResponse.text("Usage: `connect <server_ip[:port]>`");
					}
					return CommandResponse.text(runOnGameThreadSync(() -> platform.connectToServer(rest.trim())));
				case "inventory":
				case "inv":
					return buildInventoryResponse();
				case "players":
				case "entities":
					return buildEntitiesResponse();
				case "say":
				case "chat":
					if (rest.trim().isEmpty()) {
						return CommandResponse.text("Usage: `say <message>`");
					}
					return CommandResponse.text(runOnGameThreadSync(() -> platform.sendChatMessage(rest)));
				case "cmd":
				case "command":
					if (rest.trim().isEmpty()) {
						return CommandResponse.text("Usage: `cmd <minecraft_command>` (e.g. `cmd gamemode creative` or `cmd tp @s 0 80 0`)");
					}
					return CommandResponse.text(runOnGameThreadSync(() -> platform.executeCommand(rest)));
				case "stop":
				case "panic":
					return CommandResponse.text(runOnGameThreadSync(() -> motion.stopAll(platform)));
				case "macro":
					return handleMacro(rest);
				case "files":
				case "file":
				case "fs":
					return handleFiles(rest, attachments);
				case "exec":
				case "shell":
				case "sh":
					return handleExec(rest);
				case "bridge":
					return handleBridgeConfig(rest, channelId);
				default:
					return CommandResponse.text(
						"Unknown subcommand `" + firstToken + "`. Run `" + config.getPrefix() + " help` or `/mc help` for all commands."
					);
			}
		} catch (Exception e) {
			return CommandResponse.text("Error executing `" + firstToken + "`: `" + e.getMessage() + "`");
		}
	}

	public CommandResponse handleButtonPress(String userId, String customId) {
		if (!config.isAuthorized(userId)) {
			return CommandResponse.text("Unauthorized: run `/mc pair <code>` first.");
		}
		String note;
		switch (customId) {
			case "rcm:fwd":
				note = runOnGameThreadSync(() -> motion.holdKey("forward", 850L));
				break;
			case "rcm:back":
				note = runOnGameThreadSync(() -> motion.holdKey("back", 850L));
				break;
			case "rcm:left":
				note = runOnGameThreadSync(() -> motion.holdKey("left", 650L));
				break;
			case "rcm:right":
				note = runOnGameThreadSync(() -> motion.holdKey("right", 650L));
				break;
			case "rcm:jump":
				note = runOnGameThreadSync(() -> motion.holdKey("jump", 450L));
				break;
			case "rcm:turn_l":
				note = runOnGameThreadSync(() -> platform.setRotation(-45f, 0f, true));
				break;
			case "rcm:turn_r":
				note = runOnGameThreadSync(() -> platform.setRotation(45f, 0f, true));
				break;
			case "rcm:attack":
				note = runOnGameThreadSync(() -> platform.triggerAction("attack", false));
				break;
			case "rcm:use":
				note = runOnGameThreadSync(() -> platform.triggerAction("use", false));
				break;
			case "rcm:sprint":
				note = runOnGameThreadSync(() -> motion.setToggle("sprint", null));
				break;
			case "rcm:sneak":
				note = runOnGameThreadSync(() -> motion.setToggle("sneak", null));
				break;
			case "rcm:slot_prev":
				note = runOnGameThreadSync(() -> {
					PlayerSnapshot s = platform.captureSnapshot();
					int next = ((s.selectedSlot - 2 + 9) % 9);
					return platform.selectHotbarSlot(next);
				});
				break;
			case "rcm:slot_next":
				note = runOnGameThreadSync(() -> {
					PlayerSnapshot s = platform.captureSnapshot();
					int next = (s.selectedSlot % 9);
					return platform.selectHotbarSlot(next);
				});
				break;
			case "rcm:swap":
				note = runOnGameThreadSync(() -> platform.triggerAction("swap", false));
				break;
			case "rcm:drop":
				note = runOnGameThreadSync(() -> platform.triggerAction("drop", false));
				break;
			case "rcm:respawn":
				note = runOnGameThreadSync(() -> platform.triggerAction("respawn", false));
				break;
			case "rcm:stop":
				note = runOnGameThreadSync(() -> motion.stopAll(platform));
				break;
			case "rcm:inv": {
				CommandResponse inv = buildInventoryResponse();
				inv.components = buildControlPanelComponents();
				return inv;
			}
			case "rcm:status":
			case "rcm:screen":
			default:
				note = "Refreshed view & telemetry.";
				break;
		}
		return buildPanelResponse(note, config.isAutoScreenshotOnPanel() || "rcm:screen".equals(customId));
	}

	private CommandResponse handleMove(String rest) {
		String key = firstWord(rest);
		String arg = afterFirstWord(rest).trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty()) {
			return CommandResponse.text(
				"Usage: `move <forward|back|left|right|jump|sneak|sprint|attack|use> [duration_ms | toggle | on | off]`"
			);
		}
		if ("toggle".equals(arg)) {
			return CommandResponse.text(runOnGameThreadSync(() -> motion.setToggle(key, null)));
		}
		if ("on".equals(arg) || "true".equals(arg)) {
			return CommandResponse.text(runOnGameThreadSync(() -> motion.setToggle(key, Boolean.TRUE)));
		}
		if ("off".equals(arg) || "false".equals(arg)) {
			return CommandResponse.text(runOnGameThreadSync(() -> motion.setToggle(key, Boolean.FALSE)));
		}
		final long ms = parseLongOrDefault(arg, 1000L);
		return CommandResponse.text(runOnGameThreadSync(() -> motion.holdKey(key, ms)));
	}

	private CommandResponse handleRotation(String rest, final boolean relative) {
		String[] parts = rest.trim().split("\\s+");
		if (parts.length < 2) {
			return CommandResponse.text("Usage: `" + (relative ? "turn <delta_yaw> <delta_pitch>" : "look <yaw> <pitch>") + "`");
		}
		final float yaw = Float.parseFloat(parts[0]);
		final float pitch = Float.parseFloat(parts[1]);
		return CommandResponse.text(runOnGameThreadSync(() -> platform.setRotation(yaw, pitch, relative)));
	}

	private CommandResponse handleGoto(String rest) {
		String[] parts = rest.trim().split("\\s+");
		if (parts.length < 3) {
			return CommandResponse.text("Usage: `goto <x> <y> <z> [timeout_seconds]`");
		}
		final double x = Double.parseDouble(parts[0]);
		final double y = Double.parseDouble(parts[1]);
		final double z = Double.parseDouble(parts[2]);
		final long timeoutMs = parts.length >= 4 ? Long.parseLong(parts[3]) * 1000L : 60_000L;
		return CommandResponse.text(runOnGameThreadSync(() -> motion.startGoto(x, y, z, timeoutMs, asyncChannelNotifier)));
	}

	private CommandResponse handleAttackOrUse(final String action, String rest) {
		String clean = rest.trim();
		if (clean.toLowerCase(Locale.ROOT).startsWith("hold")) {
			final long ms = parseLongOrDefault(afterFirstWord(clean), 2000L);
			return CommandResponse.text(runOnGameThreadSync(() -> motion.holdKey(action, ms)));
		}
		final int count = Math.max(1, Math.min(20, (int) parseLongOrDefault(clean, 1L)));
		return CommandResponse.text(runOnGameThreadSync(() -> {
			String last = "";
			for (int i = 0; i < count; i++) {
				last = platform.triggerAction(action, false);
			}
			return last + (count > 1 ? " (x" + count + ")" : "");
		}));
	}

	private CommandResponse handleMacro(final String macroSpec) {
		if (macroSpec == null || macroSpec.trim().isEmpty()) {
			return CommandResponse.text("Usage: `macro <step1 ; step2 ; wait 500 ; step3>`");
		}
		final String[] steps = macroSpec.split(";");
		workerPool.submit(new Runnable() {
			@Override
			public void run() {
				StringBuilder summary = new StringBuilder("Macro completed (" + steps.length + " steps):\n");
				for (String rawStep : steps) {
					String step = rawStep.trim();
					if (step.isEmpty()) {
						continue;
					}
					if (step.toLowerCase(Locale.ROOT).startsWith("wait ")) {
						long ms = parseLongOrDefault(afterFirstWord(step), 500L);
						try {
							Thread.sleep(Math.min(ms, 30_000L));
						} catch (InterruptedException ignored) {
							break;
						}
						summary.append("- Slept ").append(ms).append(" ms\n");
					} else {
						CommandResponse res = dispatch(config.getAuthorizedUserIds().get(0), "", step, Collections.<AttachmentInput>emptyList());
						summary.append("- `").append(step).append("` -> ").append(res.content != null ? res.content : "OK").append('\n');
					}
				}
				if (asyncChannelNotifier != null) {
					asyncChannelNotifier.accept(summary.toString());
				}
			}
		});
		return CommandResponse.text("Started async macro with " + steps.length + " steps.");
	}

	private CommandResponse handleFiles(String rest, List<AttachmentInput> attachments) throws Exception {
		String sub = firstWord(rest).toLowerCase(Locale.ROOT);
		String arg = afterFirstWord(rest);
		switch (sub) {
			case "":
			case "ls":
			case "dir":
			case "list":
				return CommandResponse.text("```\n" + DiscordRestClient.truncate(files.listDirectory(arg), 1920) + "\n```");
			case "read":
			case "cat":
			case "view": {
				String[] tokens = arg.trim().split("\\s+");
				if (tokens.length == 0 || tokens[0].isEmpty()) {
					return CommandResponse.text("Usage: `files read <path> [start_line] [end_line]`");
				}
				int start = tokens.length >= 2 ? Integer.parseInt(tokens[1]) : 1;
				int end = tokens.length >= 3 ? Integer.parseInt(tokens[2]) : start + 119;
				return CommandResponse.text("```\n" + DiscordRestClient.truncate(files.readFile(tokens[0], start, end), 1920) + "\n```");
			}
			case "write":
			case "save": {
				SplitTarget split = splitPathAndBody(arg);
				if (split.path.isEmpty()) {
					return CommandResponse.text("Usage: `files write <path> <content>` (supports multiline codeblocks!)");
				}
				return CommandResponse.text(files.writeFile(split.path, split.body));
			}
			case "append": {
				SplitTarget split = splitPathAndBody(arg);
				if (split.path.isEmpty()) {
					return CommandResponse.text("Usage: `files append <path> <content>`");
				}
				return CommandResponse.text(files.appendFile(split.path, split.body));
			}
			case "edit":
			case "replace": {
				SplitTarget split = splitPathAndBody(arg);
				String body = split.body;
				String delimiter = body.contains("===") ? "===" : (body.contains("->") ? "->" : null);
				if (split.path.isEmpty() || delimiter == null) {
					return CommandResponse.text("Usage: `files edit <path> <old_text> -> <new_text>` (or `<old> === <new>` for multi-line edits)");
				}
				int idx = body.indexOf(delimiter);
				String oldPart = body.substring(0, idx).trim();
				String newPart = body.substring(idx + delimiter.length()).trim();
				return CommandResponse.text(files.editFile(split.path, oldPart, newPart));
			}
			case "upload":
			case "put": {
				if (attachments == null || attachments.isEmpty() || restClient == null) {
					return CommandResponse.text("Attach one or more files to your Discord message with `files upload [target_folder_or_filepath]`.");
				}
				StringBuilder out = new StringBuilder();
				for (AttachmentInput att : attachments) {
					byte[] bytes = restClient.downloadUrl(att.url);
					String dest = arg.trim().isEmpty()
						? att.fileName
						: (arg.trim().endsWith("/") || java.nio.file.Files.isDirectory(files.resolvePath(arg.trim()))
							? arg.trim() + "/" + att.fileName
							: arg.trim());
					out.append(files.writeBinary(dest, bytes)).append('\n');
				}
				return CommandResponse.text(out.toString().trim());
			}
			case "download":
			case "get": {
				if (arg.trim().isEmpty()) {
					return CommandResponse.text("Usage: `files download <file_or_folder_path>`");
				}
				FileControlManager.TransferPayload payload = files.prepareDownload(arg.trim());
				CommandResponse r = new CommandResponse();
				r.content = "Downloaded `" + payload.fileName + "` (" + FileControlManager.formatBytes(payload.data.length) + ")";
				r.fileName = payload.fileName;
				r.fileBytes = payload.data;
				return r;
			}
			case "mkdir":
				return CommandResponse.text(files.mkdir(arg.trim()));
			case "mv":
			case "move":
			case "rename": {
				String[] t = arg.trim().split("\\s+", 2);
				if (t.length < 2) {
					return CommandResponse.text("Usage: `files mv <src> <dst>`");
				}
				return CommandResponse.text(files.move(t[0], t[1]));
			}
			case "cp":
			case "copy": {
				String[] t = arg.trim().split("\\s+", 2);
				if (t.length < 2) {
					return CommandResponse.text("Usage: `files cp <src> <dst>`");
				}
				return CommandResponse.text(files.copy(t[0], t[1]));
			}
			case "rm":
			case "del":
			case "delete":
				if (arg.trim().isEmpty()) {
					return CommandResponse.text("Usage: `files rm <path>`");
				}
				return CommandResponse.text(files.delete(arg.trim()));
			default:
				return CommandResponse.text(
					"File subcommands: `ls`, `read`, `write`, `append`, `edit`, `upload`, `download`, `mkdir`, `mv`, `cp`, `rm`."
				);
		}
	}

	private CommandResponse handleExec(String command) throws Exception {
		if (!config.isAllowShellCommands()) {
			return CommandResponse.text("Shell command execution is disabled (`allowShellCommands=false` in `rcm.json`).");
		}
		if (command == null || command.trim().isEmpty()) {
			return CommandResponse.text("Usage: `exec <shell_command>`");
		}
		boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		ProcessBuilder pb = isWin
			? new ProcessBuilder("cmd.exe", "/c", command)
			: new ProcessBuilder("sh", "-c", command);
		pb.directory(files.getBaseDir().toFile());
		pb.redirectErrorStream(true);
		Process proc = pb.start();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final InputStream in = proc.getInputStream();
		Thread reader = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					byte[] buf = new byte[4096];
					int n;
					while ((n = in.read(buf)) != -1) {
						baos.write(buf, 0, n);
					}
				} catch (Exception ignored) {
				}
			}
		}, "rcm-exec-reader");
		reader.setDaemon(true);
		reader.start();

		boolean finished = proc.waitFor(25L, TimeUnit.SECONDS);
		if (!finished) {
			proc.destroyForcibly();
		}
		reader.join(1500L);
		String output = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
		if (output.isEmpty()) {
			output = "(no stdout/stderr)";
		}
		String header = finished ? "Exit code: " + proc.exitValue() : "Process timed out after 25s (killed)";
		if (output.length() > 1800) {
			CommandResponse r = new CommandResponse();
			r.content = header + " — full output attached:";
			r.fileName = "exec-output.txt";
			r.fileBytes = output.getBytes(StandardCharsets.UTF_8);
			return r;
		}
		return CommandResponse.text(header + "\n```\n" + output + "\n```");
	}

	private CommandResponse handleBridgeConfig(String rest, String channelId) throws Exception {
		String arg = rest.trim().toLowerCase(Locale.ROOT);
		if ("here".equals(arg)) {
			config.setChatBridgeChannelId(channelId);
			config.setChatBridgeEnabled(true);
			return CommandResponse.text("Live Minecraft <-> Discord chat bridge bound to `<#" + channelId + ">`!");
		}
		if ("on".equals(arg)) {
			config.setChatBridgeEnabled(true);
			return CommandResponse.text("Chat bridge enabled.");
		}
		if ("off".equals(arg)) {
			config.setChatBridgeEnabled(false);
			return CommandResponse.text("Chat bridge disabled.");
		}
		if ("2way".equals(arg) || "twoway".equals(arg)) {
			config.setChatBridgeChannelId(channelId);
			config.setChatBridgeEnabled(true);
			config.setForwardDiscordToGameChat(true);
			return CommandResponse.text("2-way chat bridge active in `<#" + channelId + ">` (Discord messages forward to Minecraft chat!).");
		}
		return CommandResponse.text("Usage: `bridge here` | `bridge 2way` | `bridge on` | `bridge off`");
	}

	public CommandResponse buildStatusResponse(boolean includePanel) {
		PlayerSnapshot snap = captureOnGameThread();
		snap.activeMotion = motion.describeState();

		Map<String, Object> embed = new LinkedHashMap<String, Object>();
		embed.put("title", "Remote Controlled Minecraft — Live Telemetry");
		embed.put("color", Integer.valueOf(snap.inWorld ? 0x57F287 : 0xFEE75C));

		List<Object> fields = new ArrayList<Object>();
		fields.add(embedField("Edition / Mode", "`" + snap.minecraftVersion + "` (" + snap.mode + ")", true));
		fields.add(embedField("World / Server", snap.serverAddress, true));
		fields.add(embedField("Player", "`" + snap.playerName + "`", true));
		if (snap.inWorld) {
			fields.add(embedField(
				"Vitals",
				String.format("HP: **%.1f/%.1f** | Food: **%d/20** | XP: **Lvl %d**",
					Float.valueOf(snap.health), Float.valueOf(snap.maxHealth),
					Integer.valueOf(snap.foodLevel), Integer.valueOf(snap.xpLevel)),
				false
			));
			fields.add(embedField(
				"Coordinates & Facing",
				String.format("`X: %.2f  Y: %.2f  Z: %.2f` | Yaw `%.1f°` Pitch `%.1f°` | `%s`",
					Double.valueOf(snap.x), Double.valueOf(snap.y), Double.valueOf(snap.z),
					Float.valueOf(snap.yaw), Float.valueOf(snap.pitch), snap.dimension),
				false
			));
			fields.add(embedField(
				"Equipped (Slot " + snap.selectedSlot + ")",
				"Main: `" + snap.mainHandItem + "` | Offhand: `" + snap.offHandItem + "`",
				false
			));
		}
		fields.add(embedField("Motion State", snap.activeMotion, true));
		fields.add(embedField(
			"Performance",
			"FPS: **" + snap.fps + "** | RAM: **" + snap.usedMemoryMb + " / " + snap.maxMemoryMb + " MB**",
			true
		));
		if (!snap.onlinePlayers.isEmpty()) {
			fields.add(embedField("Online Players (" + snap.onlinePlayers.size() + ")", String.join(", ", snap.onlinePlayers), false));
		}
		embed.put("fields", fields);

		CommandResponse r = new CommandResponse();
		r.embeds = Collections.<Object>singletonList(embed);
		if (includePanel) {
			r.components = buildControlPanelComponents();
		}
		return r;
	}

	public CommandResponse buildPanelResponse(String actionNote, boolean attachScreenshot) {
		CommandResponse r = buildStatusResponse(true);
		r.content = actionNote;
		if (attachScreenshot && platform.isClientEnvironment()) {
			try {
				byte[] png = platform.captureScreenshotPng().get(6L, TimeUnit.SECONDS);
				if (png != null && png.length > 0) {
					r.fileName = "view.png";
					r.fileBytes = png;
					@SuppressWarnings("unchecked")
					Map<String, Object> embed = (Map<String, Object>) r.embeds.get(0);
					Map<String, Object> imageObj = new LinkedHashMap<String, Object>();
					imageObj.put("url", "attachment://view.png");
					embed.put("image", imageObj);
				}
			} catch (Exception ignored) {
			}
		}
		return r;
	}

	private CommandResponse buildScreenshotResponse(String caption) throws Exception {
		byte[] png = platform.captureScreenshotPng().get(8L, TimeUnit.SECONDS);
		if (png == null || png.length == 0) {
			return CommandResponse.text("Screenshot unavailable (running on headless server without display).");
		}
		CommandResponse r = new CommandResponse();
		r.content = caption;
		r.fileName = "minecraft-screenshot.png";
		r.fileBytes = png;
		return r;
	}

	private CommandResponse buildInventoryResponse() {
		PlayerSnapshot snap = captureOnGameThread();
		if (snap.inventorySummary.isEmpty()) {
			return CommandResponse.text("Inventory is currently empty or no active player is loaded.");
		}
		return CommandResponse.text(
			"**Inventory for `" + snap.playerName + "` (Selected Hotbar Slot " + snap.selectedSlot + "):**\n```\n"
				+ DiscordRestClient.truncate(String.join("\n", snap.inventorySummary), 1850) + "\n```"
		);
	}

	private CommandResponse buildEntitiesResponse() {
		PlayerSnapshot snap = captureOnGameThread();
		StringBuilder sb = new StringBuilder();
		sb.append("**Online Players (").append(snap.onlinePlayers.size()).append("):** ")
			.append(snap.onlinePlayers.isEmpty() ? "None" : String.join(", ", snap.onlinePlayers)).append("\n\n");
		sb.append("**Nearby Entities:**\n```\n");
		sb.append(snap.nearbyEntities.isEmpty() ? "(none within range)" : String.join("\n", snap.nearbyEntities));
		sb.append("\n```");
		return CommandResponse.text(DiscordRestClient.truncate(sb.toString(), 1950));
	}

	private CommandResponse buildHelpResponse() {
		String p = config.getPrefix();
		Map<String, Object> embed = new LinkedHashMap<String, Object>();
		embed.put("title", "Remote Controlled Minecraft — Command Reference");
		embed.put("color", Integer.valueOf(0x5865F2));
		embed.put("description", "Use either `/mc <subcommand>` Slash Commands or `" + p + " <command>` messages:");
		List<Object> fields = new ArrayList<Object>();
		fields.add(embedField("Live Panel & Vision",
			"`" + p + " panel` — Open interactive live button gamepad + viewport\n"
				+ "`" + p + " screenshot` — Capture full-res PNG screenshot\n"
				+ "`" + p + " status` / `inventory` / `entities` — Full telemetry", false));
		fields.add(embedField("Movement, Camera & Navigation",
			"`" + p + " move <forward|back|left|right|jump|sneak|sprint|attack|use> [ms|toggle|on|off]`\n"
				+ "`" + p + " look <yaw> <pitch>` | `" + p + " turn <dyaw> <dpitch>`\n"
				+ "`" + p + " goto <x> <y> <z>` — Auto-walk with obstacle auto-jump\n"
				+ "`" + p + " stop` — Release all keys & cancel navigation", false));
		fields.add(embedField("Actions, Inventory, Chat & Commands",
			"`" + p + " attack [count|hold <ms>]` | `" + p + " mine <ms>` | `" + p + " use [count|hold <ms>]` | `" + p + " eat`\n"
				+ "`" + p + " slot <1-9>` | `" + p + " swap` | `" + p + " drop [all]` | `" + p + " respawn`\n"
				+ "`" + p + " connect <ip>` | `" + p + " disconnect`\n"
				+ "`" + p + " say <msg>` | `" + p + " cmd <mc_command>` | `" + p + " bridge 2way`\n"
				+ "`" + p + " macro <step1 ; wait 500 ; step2>`", false));
		fields.add(embedField("Full File & System Management",
			"`" + p + " files ls [path]` | `" + p + " files read <path> [start] [end]`\n"
				+ "`" + p + " files write <path> <text>` | `" + p + " files append <path> <text>`\n"
				+ "`" + p + " files edit <path> <old> -> <new>`\n"
				+ "`" + p + " files upload [dest]` (attach file!) | `" + p + " files download <path>`\n"
				+ "`" + p + " files mkdir|mv|cp|rm ...` | `" + p + " exec <shell_cmd>`", false));
		embed.put("fields", fields);

		CommandResponse r = new CommandResponse();
		r.embeds = Collections.<Object>singletonList(embed);
		return r;
	}

	public static List<Object> buildControlPanelComponents() {
		List<Object> rows = new ArrayList<Object>();
		rows.add(actionRow(
			button("rcm:turn_l", "Turn L 45°", 2),
			button("rcm:fwd", "Forward", 1),
			button("rcm:turn_r", "Turn R 45°", 2),
			button("rcm:jump", "Jump", 1),
			button("rcm:screen", "Refresh View", 3)
		));
		rows.add(actionRow(
			button("rcm:left", "Strafe L", 1),
			button("rcm:back", "Back", 1),
			button("rcm:right", "Strafe R", 1),
			button("rcm:attack", "Attack / Mine", 4),
			button("rcm:use", "Use / Place", 3)
		));
		rows.add(actionRow(
			button("rcm:sprint", "Sprint Toggle", 2),
			button("rcm:sneak", "Sneak Toggle", 2),
			button("rcm:inv", "Inventory", 2),
			button("rcm:status", "Telemetry", 2),
			button("rcm:stop", "STOP ALL", 4)
		));
		rows.add(actionRow(
			button("rcm:slot_prev", "Slot -", 2),
			button("rcm:slot_next", "Slot +", 2),
			button("rcm:swap", "Swap Hand", 2),
			button("rcm:drop", "Drop Item", 2),
			button("rcm:respawn", "Respawn", 3)
		));
		return rows;
	}

	private static Map<String, Object> actionRow(Object... buttons) {
		Map<String, Object> row = new LinkedHashMap<String, Object>();
		row.put("type", Integer.valueOf(1));
		row.put("components", Arrays.asList(buttons));
		return row;
	}

	private static Map<String, Object> button(String customId, String label, int style) {
		Map<String, Object> btn = new LinkedHashMap<String, Object>();
		btn.put("type", Integer.valueOf(2));
		btn.put("style", Integer.valueOf(style));
		btn.put("label", label);
		btn.put("custom_id", customId);
		return btn;
	}

	private static Map<String, Object> embedField(String name, String value, boolean inline) {
		Map<String, Object> f = new LinkedHashMap<String, Object>();
		f.put("name", name);
		f.put("value", value == null || value.isEmpty() ? "-" : DiscordRestClient.truncate(value, 1000));
		f.put("inline", Boolean.valueOf(inline));
		return f;
	}

	private PlayerSnapshot captureOnGameThread() {
		try {
			final CompletableFuture<PlayerSnapshot> fut = new CompletableFuture<PlayerSnapshot>();
			platform.executeOnGameThread(new Runnable() {
				@Override
				public void run() {
					try {
						fut.complete(platform.captureSnapshot());
					} catch (Throwable t) {
						fut.completeExceptionally(t);
					}
				}
			});
			return fut.get(4L, TimeUnit.SECONDS);
		} catch (Exception e) {
			return platform.captureSnapshot();
		}
	}

	private interface StringTask {
		String run() throws Exception;
	}

	private String runOnGameThreadSync(final StringTask task) {
		try {
			final CompletableFuture<String> fut = new CompletableFuture<String>();
			platform.executeOnGameThread(new Runnable() {
				@Override
				public void run() {
					try {
						fut.complete(task.run());
					} catch (Throwable t) {
						fut.completeExceptionally(t);
					}
				}
			});
			return fut.get(5L, TimeUnit.SECONDS);
		} catch (Exception e) {
			return "Action error: " + e.getMessage();
		}
	}

	private static String firstWord(String s) {
		if (s == null || s.trim().isEmpty()) {
			return "";
		}
		int idx = indexOfWhitespace(s.trim());
		return idx < 0 ? s.trim() : s.trim().substring(0, idx);
	}

	private static String afterFirstWord(String s) {
		if (s == null || s.trim().isEmpty()) {
			return "";
		}
		String t = s.trim();
		int idx = indexOfWhitespace(t);
		return idx < 0 ? "" : t.substring(idx + 1);
	}

	private static int indexOfWhitespace(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static long parseLongOrDefault(String s, long fallback) {
		if (s == null || s.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static final class SplitTarget {
		final String path;
		final String body;

		SplitTarget(String path, String body) {
			this.path = path;
			this.body = body;
		}
	}

	private static SplitTarget splitPathAndBody(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return new SplitTarget("", "");
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("\"")) {
			int closing = trimmed.indexOf('"', 1);
			if (closing > 1) {
				String p = trimmed.substring(1, closing);
				String b = closing + 1 < trimmed.length() ? trimmed.substring(closing + 1).trim() : "";
				return new SplitTarget(p, b);
			}
		}
		int ws = indexOfWhitespace(trimmed);
		if (ws < 0) {
			return new SplitTarget(trimmed, "");
		}
		return new SplitTarget(trimmed.substring(0, ws), trimmed.substring(ws + 1).trim());
	}
}
