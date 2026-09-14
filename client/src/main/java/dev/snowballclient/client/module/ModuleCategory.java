package dev.snowballclient.client.module;

/** Radial menu categories, in clockwise order starting at the top-left segment. */
public enum ModuleCategory {
	CLIENT("client", "CLIENT"),
	FPS_BOOST("fps_boost", "FPS BOOST"),
	RENDER("render", "RENDER"),
	MISC("misc", "MISC"),
	QOL("qol", "QOL"),
	STORAGE("storage", "STORAGE");

	private final String id;
	private final String displayName;

	ModuleCategory(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public static ModuleCategory byId(String id) {
		for (ModuleCategory c : values()) if (c.id.equals(id)) return c;
		return null;
	}
}
