package dev.snowballclient.client.hud;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.module.ModuleManager;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Single HUD layer that draws every enabled HUD module, waypoint markers and notifications. */
public final class HudRenderer {
	private final HudModule[] huds;
	private final Theme theme;

	public HudRenderer(ModuleManager modules, Theme theme) {
		this.theme = theme;
		// Resolved once: the module list never changes after start-up, so no per-frame filtering.
		this.huds = modules.all().stream().filter(HudModule.class::isInstance).map(HudModule.class::cast).toArray(HudModule[]::new);
	}

	public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return; // F1 hides the whole HUD pass in vanilla, so no extra check is needed.
		if (ModuleRegistry.CLIENT_HUD == null || ModuleRegistry.CLIENT_HUD.isEnabled()) {
			if (ModuleRegistry.WAYPOINTS != null && ModuleRegistry.WAYPOINTS.isEnabled()) ModuleRegistry.WAYPOINTS.renderMarkers(graphics, mc, theme);
			for (HudModule hud : huds) {
				if (hud.isEnabled()) hud.render(graphics, mc, theme);
			}
			String zoom = ModuleRegistry.ZOOM != null ? ModuleRegistry.ZOOM.levelText() : null;
			if (zoom != null) {
				graphics.text(mc.font, zoom, (graphics.guiWidth() - mc.font.width(zoom)) / 2, graphics.guiHeight() / 2 + 14, theme.text(), true);
			}
		}
		if (ModuleRegistry.NOTIFICATIONS != null && ModuleRegistry.NOTIFICATIONS.isEnabled()) NotificationCenter.render(graphics, mc, theme);
	}
}
