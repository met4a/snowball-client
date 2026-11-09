package dev.snowballclient.client.module.misc;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.HudModule;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import dev.snowballclient.client.platform.GuiCanvas;
import dev.snowballclient.client.platform.MinecraftTextures;
import dev.snowballclient.client.spotify.NowPlaying;
import dev.snowballclient.client.spotify.Playback;
import dev.snowballclient.client.spotify.SpotifyCard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shows the song the Spotify app on this computer is playing. Nothing to sign in to: the song is
 * read from Spotify itself (see {@link NowPlaying}). The Spotify keys under Controls play, pause and
 * skip.
 */
public final class SpotifyHud extends HudModule {
	public final BooleanSetting cover = setting(new BooleanSetting("cover", "Album cover", "Show the cover where the system gives one (not on Windows)", true));
	public final BooleanSetting progress = setting(new BooleanSetting("progress", "Progress bar", "How far through the song, where the system says", true));
	public final BooleanSetting announce = setting(new BooleanSetting("announce", "Announce new songs", "A notification when the song changes", false));
	public final NumberSetting maxWidth = setting(new NumberSetting("max_width", "Width", "Widest the card gets before names are shortened", 170, 100, 300, 10));

	private final GuiCanvas canvas = new GuiCanvas();
	private Playback shown = Playback.NOTHING;

	public SpotifyHud() {
		super("spotify", "Spotify", "The song Spotify is playing", ModuleCategory.MISC, 0.0, 0.62);
	}

	private SpotifyCard.Style style() {
		return new SpotifyCard.Style(cover.isOn(), progress.isOn(), (int) Math.round(maxWidth.get()));
	}

	@Override
	public void onTick() {
		NowPlaying now = NowPlaying.get();
		if (now == null) return;
		now.tick(true);
		Playback p = now.playback();
		if (announce.isOn() && p.hasTrack() && p.playing() && !p.sameSong(shown)) {
			NotificationCenter.post("Now playing", p.artists().isEmpty() ? p.track() : p.track() + " - " + p.artists());
		}
		if (p.hasTrack()) shown = p;
	}

	@Override
	protected int contentWidth(Minecraft mc) {
		NowPlaying now = NowPlaying.get();
		if (now == null) return 0;
		return SpotifyCard.width(mc.font::width, now.playback(), now.appOpen(), style());
	}

	@Override
	protected int contentHeight(Minecraft mc) {
		NowPlaying now = NowPlaying.get();
		return now == null ? 0 : SpotifyCard.height(now.playback(), style());
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, Minecraft mc, Theme theme, int width, int height) {
		NowPlaying now = NowPlaying.get();
		if (now == null) return;
		Playback p = now.playback();
		var art = cover.isOn() ? now.art().texture(MinecraftTextures.INSTANCE, p.artUrl()) : null;
		SpotifyCard.draw(canvas.wrap(g), theme, p, art, style(), width, height, System.currentTimeMillis(), now.appOpen());
	}
}
