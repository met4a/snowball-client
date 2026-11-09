package dev.snowballclient.client.module.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * End crystals in a colour of your choice, glowing however dark it is. The colour is painted into
 * the crystal's texture once (so it costs nothing per frame) and the glow draws crystals at full
 * brightness. Crystals are not outlined through walls: that would show players where crystals are
 * that they could not otherwise see.
 */
public final class CrystalGlow extends Module {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png");
	/** Full sky and block light, packed the way entity light is. */
	public static final int FULL_BRIGHT = 0xF000F0;

	public final ColorSetting color = setting(new ColorSetting("color", "Colour", "The crystals' colour", 0xFF4FD8FF));
	public final BooleanSetting glow = setting(new BooleanSetting("glow", "Glow in the dark", "Crystals stay bright however dark it is", true));

	/** The colour now painted on the crystal texture, or null while it is Minecraft's own. */
	private Integer painted;
	/** Painting failed for this colour (no texture to read); not tried again until the colour changes. */
	private boolean failed;

	public CrystalGlow() {
		super("crystal_glow", "Crystal Glow", "End crystals in your colour, glowing", ModuleCategory.VISUALS);
		alsoIn(ModuleCategory.PVP);
		color.onChange(c -> failed = false);
	}

	/** Whether crystals are drawn at full brightness. */
	public boolean glows() {
		return isEnabled() && glow.isOn();
	}

	@Override
	public void onTick() {
		// Painting waits for a tick: the game's resources are loaded by then, and this is the render thread.
		int want = color.argb() | 0xFF000000;
		if (failed || (painted != null && painted == want)) return;
		if (paint(want)) painted = want;
		else failed = true;
	}

	@Override
	protected void onDisable() {
		if (painted == null) return;
		// With ours gone, Minecraft loads its own crystal texture (or a resource pack's) the next time one is drawn.
		Minecraft.getInstance().getTextureManager().release(TEXTURE);
		painted = null;
		failed = false;
	}

	private static boolean paint(int colour) {
		Minecraft mc = Minecraft.getInstance();
		Optional<Resource> resource = mc.getResourceManager().getResource(TEXTURE);
		if (resource.isEmpty()) return false;
		try (InputStream in = resource.get().open(); NativeImage original = NativeImage.read(in)) {
			int w = original.getWidth();
			int h = original.getHeight();
			int[] pixels = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) pixels[y * w + x] = original.getPixel(x, y);
			}
			int[] tinted = CrystalTint.recolour(pixels, colour);
			NativeImage image = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) image.setPixel(x, y, tinted[y * w + x]);
			}
			// Replaces (and closes) the texture crystals are drawn with.
			mc.getTextureManager().register(TEXTURE, new DynamicTexture(TEXTURE::toString, image));
			return true;
		} catch (IOException | RuntimeException e) {
			SnowballClient.LOGGER.warn("Could not colour the end crystal", e);
			return false;
		}
	}
}
