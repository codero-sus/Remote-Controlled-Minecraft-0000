package dev.rcm.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of current player, world, server, inventory, and JVM telemetry.
 */
public final class PlayerSnapshot {
	public String mode = "CLIENT";
	public String minecraftVersion = "unknown";
	public boolean inWorld = false;
	public String serverAddress = "Not in world";
	public String playerName = "N/A";
	public float health = 0f;
	public float maxHealth = 20f;
	public int foodLevel = 20;
	public int xpLevel = 0;
	public double x = 0.0;
	public double y = 0.0;
	public double z = 0.0;
	public float yaw = 0f;
	public float pitch = 0f;
	public String dimension = "unknown";
	public int fps = 0;
	public int selectedSlot = 1;
	public String mainHandItem = "Air";
	public String offHandItem = "Air";
	public final List<String> onlinePlayers = new ArrayList<String>();
	public final List<String> nearbyEntities = new ArrayList<String>();
	public final List<String> inventorySummary = new ArrayList<String>();
	public long usedMemoryMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
	public long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
	public String activeMotion = "Idle";
}
