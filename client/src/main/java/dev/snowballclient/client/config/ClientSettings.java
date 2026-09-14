package dev.snowballclient.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Global client options stored in client.json. */
public final class ClientSettings {
	public static final int SCHEMA_VERSION = 1;

	/** Remembered radial category so the menu reopens where the player left it. */
	public String lastCategory = "client";
	/** Pause singleplayer while the menu is open. */
	public boolean pauseGameInMenu = false;
	/** Show a one-time chat hint about the menu key. */
	public boolean showMenuHint = true;

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("lastCategory", lastCategory);
		o.addProperty("pauseGameInMenu", pauseGameInMenu);
		o.addProperty("showMenuHint", showMenuHint);
		return o;
	}

	public void fromJson(JsonObject o) {
		lastCategory = string(o.get("lastCategory"), lastCategory);
		pauseGameInMenu = bool(o.get("pauseGameInMenu"), pauseGameInMenu);
		showMenuHint = bool(o.get("showMenuHint"), showMenuHint);
	}

	static boolean bool(JsonElement e, boolean fallback) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
	}

	static String string(JsonElement e, String fallback) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().length() <= 64 ? e.getAsString() : fallback;
	}
}
