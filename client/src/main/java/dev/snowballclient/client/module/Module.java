package dev.snowballclient.client.module;

import dev.snowballclient.client.module.setting.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Base class for every client module. Subclasses declare settings in their constructor and
 * override the lifecycle hooks they need. Hooks are exception-isolated so one faulty module
 * cannot break the menu or other modules.
 */
public abstract class Module {
	private static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient/Module");
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
	/** GLFW_KEY_UNKNOWN; means "no keybind". */
	public static final int UNBOUND = -1;

	private final String id;
	private final String name;
	private final String description;
	private final ModuleCategory category;
	private final boolean enabledByDefault;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;
	private int keybind = UNBOUND;
	private ModuleManager manager;
	private final EnumSet<ModuleCategory> extraCategories = EnumSet.noneOf(ModuleCategory.class);
	private boolean advanced;

	protected Module(String id, String name, String description, ModuleCategory category) {
		this(id, name, description, category, false);
	}

	protected Module(String id, String name, String description, ModuleCategory category, boolean enabledByDefault) {
		if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid module id: " + id);
		this.id = id;
		this.name = Objects.requireNonNull(name);
		this.description = description == null ? "" : description;
		this.category = Objects.requireNonNull(category);
		this.enabledByDefault = enabledByDefault;
	}

	protected final <S extends Setting<?>> S setting(S setting) {
		for (Setting<?> existing : settings) {
			if (existing.id().equals(setting.id())) throw new IllegalArgumentException("Duplicate setting " + setting.id() + " in " + id);
		}
		settings.add(setting);
		setting.onChange(v -> markDirty());
		return setting;
	}

	public final String id() {
		return id;
	}

	public final String name() {
		return name;
	}

	public final String description() {
		return description;
	}

	public final ModuleCategory category() {
		return category;
	}

	/** Also list this module in other categories (e.g. Zoom under both QOL and RENDER). */
	protected final void alsoIn(ModuleCategory... categories) {
		for (ModuleCategory c : categories) if (c != category) extraCategories.add(c);
	}

	public final Set<ModuleCategory> extraCategories() {
		return Collections.unmodifiableSet(extraCategories);
	}

	/** Advanced modules stay hidden in the menu until the category's Advanced row is expanded. */
	protected final void markAdvanced() {
		advanced = true;
	}

	public final boolean isAdvanced() {
		return advanced;
	}

	/** Short badge shown in the menu row instead of plain on/off (e.g. "NOT INSTALLED"); null for none. */
	public String statusText() {
		return null;
	}

	/** False when the toggle is shown but cannot currently be changed. */
	public boolean canToggle() {
		return isToggleable();
	}

	public final boolean enabledByDefault() {
		return enabledByDefault;
	}

	public final List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public final Setting<?> setting(String settingId) {
		for (Setting<?> s : settings) if (s.id().equals(settingId)) return s;
		return null;
	}

	public final boolean isEnabled() {
		return enabled;
	}

	public final void enable() {
		setEnabled(true);
	}

	public final void disable() {
		setEnabled(false);
	}

	public final void toggle() {
		setEnabled(!enabled);
	}

	public final void setEnabled(boolean value) {
		if (enabled == value) return;
		enabled = value;
		try {
			if (value) onEnable();
			else onDisable();
		} catch (RuntimeException e) {
			LOGGER.error("Module {} failed during {}", id, value ? "enable" : "disable", e);
		}
		markDirty();
		if (manager != null) manager.fireStateChanged(this);
	}

	public final int keybind() {
		return keybind;
	}

	public final void setKeybind(int key) {
		int sanitized = key < 0 ? UNBOUND : key;
		if (sanitized == keybind) return;
		keybind = sanitized;
		if (manager != null) manager.fireKeybindChanged(this);
	}

	/** True when the module exposes options beyond on/off. */
	public boolean hasSettings() {
		return !settings.isEmpty();
	}

	/** Modules like Freelook are held rather than toggled; the menu shows a toggle only for "active" behaviour. */
	public boolean isToggleable() {
		return true;
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/** Called at the end of each client tick while enabled. */
	public void onTick() {
	}

	final void attach(ModuleManager manager) {
		this.manager = manager;
	}

	protected final void markDirty() {
		if (manager != null) manager.markDirty();
	}

	final void safeTick() {
		try {
			onTick();
		} catch (RuntimeException e) {
			LOGGER.error("Module {} threw during tick; disabling it", id, e);
			setEnabled(false);
		}
	}
}
