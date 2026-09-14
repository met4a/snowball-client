package dev.snowballclient.client.module;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.snowballclient.client.keybind.KeybindTracker;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModuleManagerTest {
	static final class TestModule extends Module {
		int enables;
		int disables;
		int ticks;
		final NumberSetting scale = setting(new NumberSetting("scale", "Scale", "", 1.0, 0.5, 2.0, 0.25));
		final BooleanSetting shadow = setting(new BooleanSetting("shadow", "Shadow", "", true));
		final ChoiceSetting mode = setting(new ChoiceSetting("mode", "Mode", "", "a", List.of("a", "b")));

		TestModule(String id, ModuleCategory category) {
			super(id, "Test " + id, "", category);
		}

		@Override
		protected void onEnable() {
			enables++;
		}

		@Override
		protected void onDisable() {
			disables++;
		}

		@Override
		public void onTick() {
			ticks++;
		}
	}

	static final class HoldModule extends Module implements HoldableModule {
		boolean held;

		HoldModule() {
			super("hold", "Hold", "", ModuleCategory.QOL, true);
		}

		@Override
		public void setHeld(boolean held) {
			this.held = held;
		}

		@Override
		public boolean isHeld() {
			return held;
		}
	}

	@Test
	void enableDisableToggleRunLifecycleOnce() {
		ModuleManager manager = new ModuleManager();
		TestModule m = manager.register(new TestModule("keystrokes", ModuleCategory.QOL));
		assertFalse(m.isEnabled());
		m.enable();
		m.enable();
		assertTrue(m.isEnabled());
		assertEquals(1, m.enables);
		m.toggle();
		assertFalse(m.isEnabled());
		assertEquals(1, m.disables);
		assertTrue(manager.isDirty());
	}

	@Test
	void tickOnlyReachesEnabledModules() {
		ModuleManager manager = new ModuleManager();
		TestModule on = manager.register(new TestModule("on", ModuleCategory.RENDER));
		TestModule off = manager.register(new TestModule("off", ModuleCategory.RENDER));
		on.enable();
		manager.tick();
		assertEquals(1, on.ticks);
		assertEquals(0, off.ticks);
	}

	@Test
	void categoriesPreserveRegistrationOrder() {
		ModuleManager manager = new ModuleManager();
		manager.register(new TestModule("b", ModuleCategory.QOL));
		manager.register(new TestModule("a", ModuleCategory.QOL));
		manager.register(new TestModule("c", ModuleCategory.MISC));
		assertEquals(List.of("b", "a"), manager.inCategory(ModuleCategory.QOL).stream().map(Module::id).toList());
		assertEquals(1, manager.inCategory(ModuleCategory.MISC).size());
		assertTrue(manager.inCategory(ModuleCategory.STORAGE).isEmpty());
	}

	static final class SharedModule extends Module {
		SharedModule(String id) {
			super(id, id, "", ModuleCategory.QOL);
			alsoIn(ModuleCategory.RENDER);
		}
	}

	@Test
	void modulesCanAppearInSeveralCategoriesAndBeOrdered() {
		ModuleManager manager = new ModuleManager();
		manager.register(new TestModule("fullbright", ModuleCategory.RENDER));
		manager.register(new SharedModule("zoom"));
		manager.register(new TestModule("weather", ModuleCategory.RENDER));
		assertEquals(List.of("fullbright", "zoom", "weather"), manager.inCategory(ModuleCategory.RENDER).stream().map(Module::id).toList());
		assertEquals(List.of("zoom"), manager.inCategory(ModuleCategory.QOL).stream().map(Module::id).toList());
		manager.order(ModuleCategory.RENDER, "weather", "zoom");
		assertEquals(List.of("weather", "zoom", "fullbright"), manager.inCategory(ModuleCategory.RENDER).stream().map(Module::id).toList());
		assertEquals(3, manager.all().size(), "shared modules are registered once");
	}

	@Test
	void duplicateIdsAreRejected() {
		ModuleManager manager = new ModuleManager();
		manager.register(new TestModule("zoom", ModuleCategory.QOL));
		assertThrows(IllegalStateException.class, () -> manager.register(new TestModule("zoom", ModuleCategory.QOL)));
	}

	@Test
	void stateRoundTripsThroughJson() {
		ModuleManager a = new ModuleManager();
		TestModule m = a.register(new TestModule("hud", ModuleCategory.CLIENT));
		m.enable();
		m.scale.set(1.5);
		m.shadow.set(false);
		m.mode.set("b");
		JsonObject saved = a.saveState();

		ModuleManager b = new ModuleManager();
		TestModule restored = b.register(new TestModule("hud", ModuleCategory.CLIENT));
		b.loadState(JsonParser.parseString(saved.toString()).getAsJsonObject());
		assertTrue(restored.isEnabled());
		assertEquals(1.5, restored.scale.get());
		assertFalse(restored.shadow.get());
		assertEquals("b", restored.mode.get());
		assertFalse(b.isDirty());
	}

	@Test
	void malformedStateValuesAreIgnoredOrClamped() {
		ModuleManager manager = new ModuleManager();
		TestModule m = manager.register(new TestModule("hud", ModuleCategory.CLIENT));
		JsonObject bad = JsonParser.parseString("""
				{"hud": {"enabled": "yes", "settings": {"scale": 99, "shadow": 3, "mode": "zzz"}},
				 "unknown": {"enabled": true}}
				""").getAsJsonObject();
		manager.loadState(bad);
		assertFalse(m.isEnabled());
		assertEquals(2.0, m.scale.get(), "out of range numbers are clamped");
		assertTrue(m.shadow.get(), "wrong type keeps the default");
		assertEquals("a", m.mode.get(), "unknown choice falls back to default");
	}

	@Test
	void numberSettingSnapsToStep() {
		NumberSetting s = new NumberSetting("x", "X", "", 1.0, 0.0, 1.0, 0.1);
		s.set(0.33);
		assertEquals(0.3, s.get());
		s.set(Double.NaN);
		assertEquals(1.0, s.get());
	}

	@Test
	void keybindsPersistAndInvalidCodesAreDropped() {
		ModuleManager a = new ModuleManager();
		TestModule zoom = a.register(new TestModule("zoom", ModuleCategory.QOL));
		zoom.setKeybind(67);
		JsonObject saved = a.saveKeybinds();
		assertEquals(67, saved.get("zoom").getAsInt());

		ModuleManager b = new ModuleManager();
		TestModule restored = b.register(new TestModule("zoom", ModuleCategory.QOL));
		TestModule other = b.register(new TestModule("other", ModuleCategory.QOL));
		b.loadKeybinds(JsonParser.parseString("{\"zoom\": 67, \"other\": 99999}").getAsJsonObject());
		assertEquals(67, restored.keybind());
		assertEquals(Module.UNBOUND, other.keybind());
	}

	@Test
	void keybindTrackerTogglesOnPressEdgeOnly() {
		ModuleManager manager = new ModuleManager();
		TestModule m = manager.register(new TestModule("sprint", ModuleCategory.QOL));
		m.setKeybind(70);
		KeybindTracker tracker = new KeybindTracker(manager);
		Set<Integer> down = new HashSet<>();

		down.add(70);
		tracker.tick(down::contains, true);
		assertTrue(m.isEnabled());
		tracker.tick(down::contains, true);
		assertTrue(m.isEnabled(), "holding the key must not re-toggle");
		down.clear();
		tracker.tick(down::contains, true);
		down.add(70);
		tracker.tick(down::contains, false);
		assertTrue(m.isEnabled(), "keys are ignored while a screen has focus");
	}

	@Test
	void holdableModulesFollowKeyState() {
		ModuleManager manager = new ModuleManager();
		HoldModule hold = manager.register(new HoldModule());
		hold.setKeybind(67);
		KeybindTracker tracker = new KeybindTracker(manager);
		tracker.tick(k -> k == 67, true);
		assertTrue(hold.held);
		tracker.tick(k -> false, true);
		assertFalse(hold.held);
		tracker.tick(k -> k == 67, true);
		tracker.releaseAll();
		assertFalse(hold.held);
	}

	@Test
	void faultyModuleIsDisabledInsteadOfCrashing() {
		ModuleManager manager = new ModuleManager();
		Module broken = manager.register(new Module("broken", "Broken", "", ModuleCategory.MISC) {
			@Override
			public void onTick() {
				throw new IllegalStateException("boom");
			}
		});
		broken.enable();
		assertDoesNotThrow(manager::tick);
		assertFalse(broken.isEnabled());
	}
}
