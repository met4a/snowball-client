package dev.snowballclient.client.crash;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.HashMap;
import java.util.Map;

/** Answers {@link CrashCulprit}'s questions from Fabric Loader: which mod's files hold a class. */
public final class FabricMods implements CrashCulprit.Mods {
	private final Map<String, String> owners = new HashMap<>();

	@Override
	public String modOf(String className) {
		if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("sun.")) return "java";
		// Code mods mix into Minecraft runs under Minecraft's class names and is found by its method name instead.
		if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) return "minecraft";
		// Snowball's own classes are known without looking; in a development run they are not inside a mod's files.
		if (className.startsWith("dev.snowballclient.client.")) return "snowballclient";
		if (owners.containsKey(className)) return owners.get(className);
		String file = className.replace('.', '/') + ".class";
		String owner = null;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			try {
				if (mod.findPath(file).isPresent()) {
					owner = mod.getMetadata().getId();
					break;
				}
			} catch (RuntimeException ignored) {
				// A mod whose files cannot be read right now simply is not the answer.
			}
		}
		owners.put(className, owner);
		return owner;
	}

	@Override
	public boolean isLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public String name(String modId) {
		return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getName()).orElse(modId);
	}
}
