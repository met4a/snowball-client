package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.ui.Canvas;
import net.minecraft.client.MinecraftClient;

/** Single HUD layer that draws every enabled HUD module, waypoint markers and notifications. */
public final class HudRenderer {
	private final HudModule[] huds;
	private final Theme theme;

	public HudRenderer(ModuleManager modules, Theme theme) {
		this.theme = theme;
		// Resolved once: the module list never changes after start-up, so no per-frame filtering.
		this.huds = modules.all().stream().filter(HudModule.class::isInstance).map(HudModule.class::cast).toArray(HudModule[]::new);
	}

	public void render(Canvas c) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden) return;
		if (ModuleRegistry.CLIENT_HUD == null || ModuleRegistry.CLIENT_HUD.isEnabled()) {
			if (ModuleRegistry.WAYPOINTS != null && ModuleRegistry.WAYPOINTS.isEnabled()) ModuleRegistry.WAYPOINTS.renderMarkers(c, theme);
			for (HudModule hud : huds) {
				if (hud.isEnabled()) hud.render(c, theme);
			}
			String zoom = ModuleRegistry.ZOOM != null ? ModuleRegistry.ZOOM.levelText() : null;
			if (zoom != null) c.drawText(zoom, (c.guiWidth() - c.textWidth(zoom)) / 2, c.guiHeight() / 2 + 14, theme.text(), true);
		}
		if (ModuleRegistry.NOTIFICATIONS != null && ModuleRegistry.NOTIFICATIONS.isEnabled()) NotificationCenter.render(c, theme);
	}
}
