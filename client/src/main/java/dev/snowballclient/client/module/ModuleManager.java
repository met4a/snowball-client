package dev.snowballclient.client.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.snowballclient.client.module.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Registry of modules. The radial menu only talks to this class, so new modules appear in
 * the UI simply by being registered.
 */
public final class ModuleManager {
	private final Map<String, Module> modules = new LinkedHashMap<>();
	private final Map<ModuleCategory, List<Module>> byCategory = new EnumMap<>(ModuleCategory.class);
	private final List<Consumer<Module>> stateListeners = new ArrayList<>();
	private final List<Consumer<Module>> keybindListeners = new ArrayList<>();
	private boolean dirty;

	public ModuleManager() {
		for (ModuleCategory c : ModuleCategory.values()) byCategory.put(c, new ArrayList<>());
	}

	public <M extends Module> M register(M module) {
		if (modules.containsKey(module.id())) throw new IllegalStateException("Module already registered: " + module.id());
		modules.put(module.id(), module);
		byCategory.get(module.category()).add(module);
		for (ModuleCategory extra : module.extraCategories()) byCategory.get(extra).add(module);
		module.attach(this);
		if (module.enabledByDefault()) module.enable();
		dirty = false;
		return module;
	}

	public Optional<Module> get(String id) {
		return Optional.ofNullable(modules.get(id));
	}

	@SuppressWarnings("unchecked")
	public <M extends Module> M get(Class<M> type) {
		for (Module m : modules.values()) if (type.isInstance(m)) return (M) m;
		throw new IllegalArgumentException("No module of type " + type.getSimpleName());
	}

	public List<Module> all() {
		return Collections.unmodifiableList(new ArrayList<>(modules.values()));
	}

	public List<Module> inCategory(ModuleCategory category) {
		return Collections.unmodifiableList(byCategory.get(category));
	}

	/** Puts the given module ids first in a category, in that order; others keep registration order. */
	public void order(ModuleCategory category, String... ids) {
		List<String> order = List.of(ids);
		byCategory.get(category).sort(Comparator.comparingInt(m -> {
			int i = order.indexOf(m.id());
			return i < 0 ? Integer.MAX_VALUE : i;
		}));
	}

	public void tick() {
		for (Module m : modules.values()) if (m.isEnabled()) m.safeTick();
	}

	public void onStateChanged(Consumer<Module> listener) {
		stateListeners.add(listener);
	}

	public void onKeybindChanged(Consumer<Module> listener) {
		keybindListeners.add(listener);
	}

	void fireStateChanged(Module module) {
		for (Consumer<Module> l : stateListeners) l.accept(module);
	}

	void fireKeybindChanged(Module module) {
		for (Consumer<Module> l : keybindListeners) l.accept(module);
	}

	void markDirty() {
		dirty = true;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void clearDirty() {
		dirty = false;
	}

	/** Serialises enabled state and settings: {"<id>": {"enabled": bool, "settings": {...}}}. */
	public JsonObject saveState() {
		JsonObject root = new JsonObject();
		for (Module m : modules.values()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("enabled", m.isEnabled());
			JsonObject settings = new JsonObject();
			for (Setting<?> s : m.settings()) settings.add(s.id(), s.toJson());
			entry.add("settings", settings);
			root.add(m.id(), entry);
		}
		return root;
	}

	/** Applies persisted state. Unknown modules/settings and wrongly typed values are ignored. */
	public void loadState(JsonObject root) {
		if (root == null) return;
		for (Module m : modules.values()) {
			JsonElement element = root.get(m.id());
			if (element == null || !element.isJsonObject()) continue;
			JsonObject entry = element.getAsJsonObject();
			JsonElement settings = entry.get("settings");
			if (settings != null && settings.isJsonObject()) {
				for (Setting<?> s : m.settings()) {
					JsonElement value = settings.getAsJsonObject().get(s.id());
					if (value != null) s.fromJson(value);
				}
			}
			JsonElement enabled = entry.get("enabled");
			if (enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
				m.setEnabled(enabled.getAsBoolean());
			}
		}
		dirty = false;
	}

	/** Keybinds persist separately in keybinds.json: {"<moduleId>": keyCode}. */
	public JsonObject saveKeybinds() {
		JsonObject root = new JsonObject();
		for (Module m : modules.values()) if (m.keybind() != Module.UNBOUND) root.addProperty(m.id(), m.keybind());
		return root;
	}

	public void loadKeybinds(JsonObject root) {
		if (root == null) return;
		for (Module m : modules.values()) {
			JsonElement value = root.get(m.id());
			if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
				int key = value.getAsInt();
				m.setKeybind(key >= 0 && key <= 512 ? key : Module.UNBOUND);
			}
		}
	}
}
