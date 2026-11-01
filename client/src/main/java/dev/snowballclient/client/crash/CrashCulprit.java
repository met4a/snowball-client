package dev.snowballclient.client.crash;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Works out which mod an error most likely came from, by reading its stack trace from where it was
 * thrown outwards. It is a good guess, not proof: the first mod found may only have called into the
 * one that is really at fault, which is why the screen says "likely".
 *
 * <p>A frame belongs to a mod when the mod's files hold its class, or when it is code a mod mixed
 * into Minecraft: Fabric names those methods with the mod's id, as in
 * {@code handler$zcm000$sodium$onRender}. Minecraft, Java and the loader are never blamed, and
 * Fabric API (Legacy Fabric API on 1.8.9) only when nothing else turns up, since its events mostly
 * pass calls on to other mods.
 */
public final class CrashCulprit {
	/** The name of the method that catches crashes in the game loop. Its frame is under every crash, so it is never the culprit. */
	public static final String GUARD_METHOD = "snowball$antiCrash";

	private static final Set<String> NEVER = Set.of("minecraft", "java", "fabricloader", "mixinextras");
	private static final Set<String> NOT_INJECTORS = Set.of("lambda", "access", "static", "");

	/** What the culprit search needs to know about the mods that are loaded. */
	public interface Mods {
		/** The id of the mod whose files hold this class, or null when none does. */
		String modOf(String className);

		boolean isLoaded(String modId);

		String name(String modId);
	}

	public record Suspect(String id, String name) {
		@Override
		public String toString() {
			return name.equals(id) ? id : name + " (" + id + ")";
		}
	}

	private CrashCulprit() {
	}

	/** The mod the error most likely came from, or null when the trace only shows Minecraft and Java. */
	public static Suspect find(Throwable error, Mods mods) {
		String fallback = null;
		List<Throwable> chain = chain(error);
		// The innermost cause is where things went wrong; the wrappers around it only add a title.
		for (int c = chain.size() - 1; c >= 0; c--) {
			for (StackTraceElement frame : chain.get(c).getStackTrace()) {
				if (frame.getMethodName().endsWith(GUARD_METHOD)) continue;
				String id = injectedBy(frame.getMethodName(), mods);
				if (id == null) id = mods.modOf(frame.getClassName());
				if (id == null || NEVER.contains(id)) continue;
				if (isFabricApi(id)) {
					if (fallback == null) fallback = id;
					continue;
				}
				return new Suspect(id, mods.name(id));
			}
		}
		return fallback == null ? null : new Suspect(fallback, mods.name(fallback));
	}

	/** Fabric API and Legacy Fabric API (1.8.9), whole or one of their modules. */
	static boolean isFabricApi(String id) {
		return id.startsWith("fabric-") || id.startsWith("legacy-fabric-");
	}

	/** The mod named in a mixin method's name ({@code handler$zcm000$modid$original}), or null. */
	static String injectedBy(String method, Mods mods) {
		if (method.indexOf('$') < 0) return null;
		String[] parts = method.split("\\$");
		if (parts.length < 3 || NOT_INJECTORS.contains(parts[0])) return null;
		for (int i = 1; i < parts.length - 1; i++) {
			if (mods.isLoaded(parts[i])) return parts[i];
		}
		return null;
	}

	private static List<Throwable> chain(Throwable error) {
		List<Throwable> chain = new ArrayList<>();
		for (Throwable t = error; t != null && chain.size() < 32 && !chain.contains(t); t = t.getCause()) chain.add(t);
		return chain;
	}
}
