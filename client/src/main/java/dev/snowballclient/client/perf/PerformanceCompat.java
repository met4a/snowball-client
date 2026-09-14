package dev.snowballclient.client.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks the loaded optimisation mods for known conflicts and gaps. Mirrors the launcher's
 * performance-profile rules so the in-game advice matches what the launcher installs.
 */
public final class PerformanceCompat {
	private PerformanceCompat() {
	}

	public enum Severity { ERROR, WARNING, TIP }

	public record Finding(Severity severity, String message) {
	}

	/** Known optimisation mods and what they improve, in display order. */
	public static final List<String[]> KNOWN = List.of(
			new String[]{"sodium", "Sodium", "rendering"},
			new String[]{"lithium", "Lithium", "game logic"},
			new String[]{"ferritecore", "FerriteCore", "memory"},
			new String[]{"immediatelyfast", "ImmediatelyFast", "immediate-mode rendering"},
			new String[]{"entityculling", "EntityCulling", "entity culling"},
			new String[]{"modernfix", "ModernFix", "load time"},
			new String[]{"iris", "Iris", "shaders"}
	);

	public static List<Finding> analyze(Set<String> loadedModIds) {
		List<Finding> out = new ArrayList<>();
		boolean sodium = loadedModIds.contains("sodium");
		boolean optifine = loadedModIds.contains("optifabric") || loadedModIds.contains("optifine");

		if (sodium && optifine) {
			out.add(new Finding(Severity.ERROR, "OptiFine (OptiFabric) and Sodium replace the same renderer and cannot run together. Remove one."));
		}
		if (loadedModIds.contains("iris") && !sodium) {
			out.add(new Finding(Severity.ERROR, "Iris requires Sodium."));
		}
		if (sodium && loadedModIds.contains("embeddium")) {
			out.add(new Finding(Severity.ERROR, "Embeddium is a Sodium fork; install only one of them."));
		}
		if (!sodium && !optifine) {
			out.add(new Finding(Severity.TIP, "Install Sodium for the largest rendering FPS gain (use the launcher's FPS Boost profile)."));
		}
		if (!loadedModIds.contains("lithium")) out.add(new Finding(Severity.TIP, "Lithium improves tick performance in singleplayer."));
		if (!loadedModIds.contains("ferritecore")) out.add(new Finding(Severity.TIP, "FerriteCore reduces memory usage."));
		return out;
	}
}
