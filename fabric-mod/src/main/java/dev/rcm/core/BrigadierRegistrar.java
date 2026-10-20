package dev.rcm.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Registers the in-game `/rcm` management command directly onto Mojang's Brigadier
 * CommandDispatcher so players and server admins can configure tokens, inspect pairing
 * codes, and reload config across EVERY Minecraft release from 1.14 to 26.3+.
 */
public final class BrigadierRegistrar {
	private BrigadierRegistrar() {
	}

	@SuppressWarnings("unchecked")
	public static void registerOnServer(Object serverInstance, final Function<String, String> handler, final MinecraftPlatform platform) {
		if (serverInstance == null) {
			return;
		}
		try {
			CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) extractDispatcher(serverInstance);
			if (dispatcher == null) {
				return;
			}
			if (dispatcher.getRoot().getChild("rcm") != null) {
				return;
			}

			LiteralArgumentBuilder<Object> root = LiteralArgumentBuilder.<Object>literal("rcm")
				.executes(ctx -> {
					String reply = handler.apply("status");
					platform.displayLocalMessage(reply);
					return 1;
				});
			RequiredArgumentBuilder<Object, String> argsNode = RequiredArgumentBuilder.<Object, String>argument("args", StringArgumentType.greedyString())
				.executes(ctx -> {
					String args = StringArgumentType.getString(ctx, "args");
					String reply = handler.apply(args);
					platform.displayLocalMessage(reply);
					return 1;
				});
			root.then(argsNode);
			dispatcher.register(root);
		} catch (Throwable ignored) {
		}
	}

	public static CommandDispatcher<?> extractDispatcher(Object serverInstance) {
		try {
			Object commandManager = null;
			for (Method m : serverInstance.getClass().getMethods()) {
				if (m.getParameterCount() == 0) {
					String rt = m.getReturnType().getName();
					if (rt.endsWith("CommandManager") || rt.endsWith("Commands") || rt.endsWith("class_2170")) {
						commandManager = m.invoke(serverInstance);
						if (commandManager != null) {
							break;
						}
					}
				}
			}
			if (commandManager == null) {
				return null;
			}
			for (Method m : commandManager.getClass().getMethods()) {
				if (m.getParameterCount() == 0 && CommandDispatcher.class.isAssignableFrom(m.getReturnType())) {
					return (CommandDispatcher<?>) m.invoke(commandManager);
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}
}
