package dev.snowballclient.client.crash;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrashCulpritTest {
	/** Mods by the package their classes live in, as Fabric would answer from their files. */
	private static final CrashCulprit.Mods MODS = new CrashCulprit.Mods() {
		private final Map<String, String> packages = Map.of(
				"net.minecraft.", "minecraft",
				"me.jellysquid.sodium.", "sodium",
				"net.fabricmc.fabric.", "fabric-lifecycle-events-v1",
				"dev.snowballclient.", "snowballclient",
				"net.fabricmc.loader.", "fabricloader");
		private final Map<String, String> names = Map.of("sodium", "Sodium", "snowballclient", "Snowball Client",
				"fabric-lifecycle-events-v1", "Fabric Lifecycle Events (v1)", "iris", "Iris",
				"legacy-fabric-lifecycle-events-v1-common-versioned", "Legacy Fabric Lifecycle Events (V1)");

		@Override
		public String modOf(String className) {
			for (Map.Entry<String, String> e : packages.entrySet()) if (className.startsWith(e.getKey())) return e.getValue();
			return null;
		}

		@Override
		public boolean isLoaded(String modId) {
			return names.containsKey(modId) || modId.equals("minecraft");
		}

		@Override
		public String name(String modId) {
			return names.getOrDefault(modId, modId);
		}
	};

	private static Throwable thrownAt(StackTraceElement... frames) {
		RuntimeException e = new RuntimeException("boom");
		e.setStackTrace(frames);
		return e;
	}

	private static StackTraceElement at(String className, String method) {
		return new StackTraceElement(className, method, null, 1);
	}

	@Test
	void blamesTheFirstModBetweenTheErrorAndTheGame() {
		Throwable e = thrownAt(
				at("java.util.HashMap", "get"),
				at("me.jellysquid.sodium.Renderer", "draw"),
				at("net.minecraft.client.renderer.GameRenderer", "render"),
				at("net.minecraft.client.Minecraft", "runTick"));
		CrashCulprit.Suspect suspect = CrashCulprit.find(e, MODS);
		assertEquals("sodium", suspect.id());
		assertEquals("Sodium (sodium)", suspect.toString());
	}

	@Test
	void readsTheModFromCodeItMixedIntoMinecraft() {
		Throwable e = thrownAt(
				at("net.minecraft.client.renderer.LevelRenderer", "handler$zcm000$iris$beforeSky"),
				at("net.minecraft.client.renderer.LevelRenderer", "renderLevel"));
		assertEquals("iris", CrashCulprit.find(e, MODS).id());
	}

	@Test
	void fabricApiIsOnlyBlamedWhenNothingElseTurnsUp() {
		Throwable passedOn = thrownAt(
				at("net.fabricmc.fabric.impl.EventInvoker", "invoke"),
				at("me.jellysquid.sodium.Hooks", "onTick"),
				at("net.minecraft.client.Minecraft", "tick"));
		assertEquals("sodium", CrashCulprit.find(passedOn, MODS).id());
		Throwable alone = thrownAt(
				at("net.fabricmc.fabric.impl.EventInvoker", "invoke"),
				at("net.minecraft.client.Minecraft", "tick"));
		assertEquals("fabric-lifecycle-events-v1", CrashCulprit.find(alone, MODS).id());
	}

	@Test
	void legacyFabricApiIsTreatedLikeFabricApi() {
		Throwable e = thrownAt(
				at("some.unknown.Thing", "run"),
				at("net.minecraft.client.MinecraftClient", "handler$zdl000$legacy-fabric-lifecycle-events-v1-common-versioned$api$onEndTick"),
				at("me.jellysquid.sodium.Hooks", "onTick"));
		assertEquals("sodium", CrashCulprit.find(e, MODS).id());
		Throwable alone = thrownAt(at("net.minecraft.client.MinecraftClient", "handler$zdl000$legacy-fabric-lifecycle-events-v1-common-versioned$api$onEndTick"));
		assertEquals("legacy-fabric-lifecycle-events-v1-common-versioned", CrashCulprit.find(alone, MODS).id());
	}

	@Test
	void theCrashCatcherItselfIsNeverBlamed() {
		Throwable e = thrownAt(
				at("net.minecraft.client.Minecraft", "tick"),
				at("net.minecraft.client.Minecraft", "runTick"),
				at("net.minecraft.client.Minecraft", "wrapOperation$zza000$snowballclient$" + CrashCulprit.GUARD_METHOD),
				at("net.minecraft.client.Minecraft", "run"),
				at("net.fabricmc.loader.impl.launch.knot.Knot", "launch"));
		assertNull(CrashCulprit.find(e, MODS));
	}

	@Test
	void snowballIsBlamedWhenTheErrorIsOurs() {
		Throwable e = thrownAt(
				at("dev.snowballclient.client.gui.RadialMenuView", "render"),
				at("net.minecraft.client.gui.screens.Screen", "render"));
		assertEquals("Snowball Client (snowballclient)", CrashCulprit.find(e, MODS).toString());
	}

	@Test
	void looksAtTheCauseBeforeTheWrapperAroundIt() {
		Throwable cause = thrownAt(at("me.jellysquid.sodium.Chunks", "build"));
		RuntimeException wrapper = new RuntimeException("Ticking screen", cause);
		wrapper.setStackTrace(new StackTraceElement[]{at("dev.snowballclient.client.Something", "tick")});
		assertEquals("sodium", CrashCulprit.find(wrapper, MODS).id());
	}

	@Test
	void lambdasAndAccessorsAreNotMixinHandlers() {
		assertNull(CrashCulprit.injectedBy("lambda$render$iris$0", MODS));
		assertNull(CrashCulprit.injectedBy("render", MODS));
		assertEquals("iris", CrashCulprit.injectedBy("redirect$bbc000$iris$skip", MODS));
		assertNull(CrashCulprit.injectedBy("handler$zcm000$unknownmod$x", MODS));
	}

	@Test
	void describesTheErrorInOneLine() {
		assertEquals("IllegalStateException: Not building!", CrashDetails.describe(new IllegalStateException("Not building!\n\tmore")));
		assertEquals("NullPointerException", CrashDetails.describe(new NullPointerException()));
		assertEquals("RuntimeException, caused by ArithmeticException: / by zero",
				CrashDetails.describe(new RuntimeException(null, new ArithmeticException("/ by zero"))));
		assertTrue(CrashDetails.describe(new RuntimeException("x".repeat(1000))).length() < 320);
	}
}
