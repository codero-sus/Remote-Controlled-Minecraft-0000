package dev.rcm.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tick-driven controller managing timed movement holds, persistent toggles,
 * obstacle-aware coordinate auto-navigation (goto), and stop-all emergency release.
 */
public final class MotionController {
	public static final Set<String> VALID_KEYS = Collections.unmodifiableSet(new HashSet<String>(
		Arrays.asList("forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use")
	));

	private final Map<String, Long> timedHoldsUntilMs = new HashMap<String, Long>();
	private final Set<String> persistentToggles = new HashSet<String>();
	private final Set<String> currentlyPressed = new HashSet<String>();

	private boolean gotoActive = false;
	private double targetX;
	private double targetY;
	private double targetZ;
	private long gotoDeadlineMs;
	private double lastX;
	private double lastZ;
	private int stuckTicks;
	private Consumer<String> completionCallback;

	public synchronized String holdKey(String key, long durationMs) {
		String normalized = normalizeKey(key);
		if (!VALID_KEYS.contains(normalized)) {
			return "Unknown movement/action key `" + key + "`. Valid: " + VALID_KEYS;
		}
		long clamped = Math.max(50L, Math.min(durationMs, 120_000L));
		timedHoldsUntilMs.put(normalized, Long.valueOf(System.currentTimeMillis() + clamped));
		return "Holding `" + normalized + "` for " + clamped + " ms.";
	}

	public synchronized String setToggle(String key, Boolean explicitState) {
		String normalized = normalizeKey(key);
		if (!VALID_KEYS.contains(normalized)) {
			return "Unknown key `" + key + "`. Valid: " + VALID_KEYS;
		}
		boolean next = explicitState != null
			? explicitState.booleanValue()
			: !persistentToggles.contains(normalized);
		if (next) {
			persistentToggles.add(normalized);
		} else {
			persistentToggles.remove(normalized);
		}
		return "Toggled `" + normalized + "` -> **" + (next ? "ON" : "OFF") + "**";
	}

	public synchronized String startGoto(double x, double y, double z, long timeoutMs, Consumer<String> onDone) {
		this.gotoActive = true;
		this.targetX = x;
		this.targetY = y;
		this.targetZ = z;
		this.gotoDeadlineMs = System.currentTimeMillis() + Math.max(5_000L, timeoutMs);
		this.stuckTicks = 0;
		this.completionCallback = onDone;
		return String.format("Auto-navigating to (%.1f, %.1f, %.1f)...",
			Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
	}

	public synchronized String stopAll(MinecraftPlatform platform) {
		timedHoldsUntilMs.clear();
		persistentToggles.clear();
		gotoActive = false;
		completionCallback = null;
		for (String k : VALID_KEYS) {
			platform.setKeyState(k, false);
		}
		currentlyPressed.clear();
		return "Stopped all movement, held actions, and auto-navigation.";
	}

	public synchronized String describeState() {
		StringBuilder sb = new StringBuilder();
		if (gotoActive) {
			sb.append(String.format("Goto (%.1f, %.1f, %.1f)", Double.valueOf(targetX), Double.valueOf(targetY), Double.valueOf(targetZ)));
		}
		if (!persistentToggles.isEmpty()) {
			if (sb.length() > 0) {
				sb.append(" | ");
			}
			sb.append("Toggled: ").append(persistentToggles);
		}
		if (!timedHoldsUntilMs.isEmpty()) {
			if (sb.length() > 0) {
				sb.append(" | ");
			}
			sb.append("Holding: ").append(timedHoldsUntilMs.keySet());
		}
		return sb.length() == 0 ? "Idle" : sb.toString();
	}

	public void tick(MinecraftPlatform platform) {
		Consumer<String> notifyDone = null;
		String notifyMessage = null;

		synchronized (this) {
			long now = System.currentTimeMillis();
			Set<String> desired = new HashSet<String>(persistentToggles);

			// Clean expired holds
			Set<String> expired = new HashSet<String>();
			for (Map.Entry<String, Long> entry : timedHoldsUntilMs.entrySet()) {
				if (entry.getValue().longValue() > now) {
					desired.add(entry.getKey());
				} else {
					expired.add(entry.getKey());
				}
			}
			for (String k : expired) {
				timedHoldsUntilMs.remove(k);
			}

			if (gotoActive) {
				PlayerSnapshot snap = platform.captureSnapshot();
				if (!snap.inWorld) {
					gotoActive = false;
					notifyDone = completionCallback;
					completionCallback = null;
					notifyMessage = "Auto-navigation aborted: player is no longer in a world.";
				} else if (now > gotoDeadlineMs) {
					gotoActive = false;
					notifyDone = completionCallback;
					completionCallback = null;
					notifyMessage = String.format("Auto-navigation timed out at (%.1f, %.1f, %.1f).",
						Double.valueOf(snap.x), Double.valueOf(snap.y), Double.valueOf(snap.z));
				} else {
					double dx = targetX - snap.x;
					double dy = targetY - snap.y;
					double dz = targetZ - snap.z;
					double horizDist = Math.hypot(dx, dz);
					if (horizDist <= 1.25 && Math.abs(dy) <= 2.5) {
						gotoActive = false;
						notifyDone = completionCallback;
						completionCallback = null;
						notifyMessage = String.format("Arrived at destination (%.1f, %.1f, %.1f)!",
							Double.valueOf(snap.x), Double.valueOf(snap.y), Double.valueOf(snap.z));
					} else {
						float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
						float pitch = (float) Math.max(-45.0, Math.min(45.0, -Math.toDegrees(Math.atan2(dy, Math.max(0.5, horizDist))) * 0.35));
						platform.setRotation(yaw, pitch, false);
						desired.add("forward");
						if (horizDist > 3.0) {
							desired.add("sprint");
						}
						double stepMoved = Math.hypot(snap.x - lastX, snap.z - lastZ);
						if (stepMoved < 0.03) {
							stuckTicks++;
							if (stuckTicks >= 4) {
								desired.add("jump");
							}
						} else {
							stuckTicks = 0;
						}
						lastX = snap.x;
						lastZ = snap.z;
					}
				}
			}

			for (String key : VALID_KEYS) {
				boolean want = desired.contains(key);
				boolean was = currentlyPressed.contains(key);
				if (want || was) {
					platform.setKeyState(key, want);
				}
				if (want) {
					currentlyPressed.add(key);
				} else {
					currentlyPressed.remove(key);
				}
			}
		}

		if (notifyDone != null && notifyMessage != null) {
			notifyDone.accept(notifyMessage);
		}
	}

	public static String normalizeKey(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim().toLowerCase();
		if ("w".equals(s) || "up".equals(s) || "walk".equals(s)) {
			return "forward";
		}
		if ("s".equals(s) || "backward".equals(s) || "down".equals(s)) {
			return "back";
		}
		if ("a".equals(s) || "strafe_left".equals(s)) {
			return "left";
		}
		if ("d".equals(s) || "strafe_right".equals(s)) {
			return "right";
		}
		if ("space".equals(s)) {
			return "jump";
		}
		if ("shift".equals(s) || "crouch".equals(s)) {
			return "sneak";
		}
		if ("ctrl".equals(s) || "run".equals(s)) {
			return "sprint";
		}
		if ("mine".equals(s) || "hit".equals(s) || "leftclick".equals(s)) {
			return "attack";
		}
		if ("interact".equals(s) || "place".equals(s) || "eat".equals(s) || "rightclick".equals(s)) {
			return "use";
		}
		return s;
	}
}
