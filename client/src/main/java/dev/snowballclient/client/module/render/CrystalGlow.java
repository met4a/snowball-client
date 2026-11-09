package dev.snowballclient.client.module.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import dev.snowballclient.client.module.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * End crystals in a colour of your choice, glowing however dark it is. The colour is painted into
 * the crystal's texture (once for a solid colour; a few times a second for the moving patterns, a
 * texture of 64 by 32 pixels) and the glow draws crystals at full brightness. The halo and sparkles
 * around each crystal are drawn by CrystalEffectsWorld. Nothing is shown through walls: that would
 * show players where crystals are that they could not otherwise see.
 */
public final class CrystalGlow extends Module {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png");
	/** Full sky and block light, packed the way entity light is. */
	public static final int FULL_BRIGHT = 0xF000F0;
	public static final String SOLID = "solid";
	public static final String SHIMMER = "shimmer";
	public static final String PULSE = "pulse";
	public static final String RAINBOW = "rainbow";

	public final ColorSetting color = setting(new ColorSetting("color", "Colour", "The crystals' colour", 0xFF4FD8FF));
	public final BooleanSetting glow = setting(new BooleanSetting("glow", "Glow in the dark", "Crystals stay bright however dark it is", true));
	public final ChoiceSetting pattern = setting(new ChoiceSetting("pattern", "Pattern", "How the colour moves over the crystal", SHIMMER, List.of(SOLID, SHIMMER, PULSE, RAINBOW)));
	public final BooleanSetting halo = setting(new BooleanSetting("halo", "Halo", "A soft glow and turning rings of light around each crystal", true));
	public final BooleanSetting sparkles = setting(new BooleanSetting("sparkles", "Sparkles", "Little stars drift up around each crystal", true));

	/** The crystal texture as Minecraft (or a resource pack) has it, and its size. */
	private int[] original;
	private int width;
	private int height;
	/** Ours, registered in place of the crystal texture while the module is on. */
	private DynamicTexture texture;
	/** The solid colour now painted, or null while a pattern is moving. */
	private Integer painted;
	/** Reading the texture failed; not tried again until the colour changes. */
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

	/** The crystals' colour at {@code millis}: the chosen one, or the rainbow's. */
	public int colourAt(long millis) {
		if (RAINBOW.equals(pattern.get())) return 0xFF000000 | java.awt.Color.HSBtoRGB((millis % 6_000L) / 6_000f, 0.7f, 1f);
		return color.argb() | 0xFF000000;
	}

	@Override
	public void onTick() {
		// Painting waits for a tick: the game's resources are loaded by then, and this is the render thread.
		if (failed) return;
		if (texture == null && !load()) {
			failed = true;
			return;
		}
		long now = System.currentTimeMillis();
		String p = pattern.get();
		int base = colourAt(now);
		if (SOLID.equals(p)) {
			if (painted == null || painted != base) {
				show(CrystalTint.recolour(original, base));
				painted = base;
			}
			return;
		}
		painted = null;
		int[] pixels = CrystalTint.recolour(original, base);
		if (SHIMMER.equals(p)) CrystalTint.shimmer(pixels, width, height, (now % 2_200L) / 2_200f);
		else if (PULSE.equals(p)) CrystalTint.brighten(pixels, 0.7f + 0.3f * (float) Math.sin(now / 1_000.0 * Math.PI));
		show(pixels);
	}

	@Override
	protected void onDisable() {
		if (texture == null) return;
		// With ours gone, Minecraft loads its own crystal texture (or a resource pack's) the next time one is drawn.
		Minecraft.getInstance().getTextureManager().release(TEXTURE);
		texture = null;
		original = null;
		painted = null;
		failed = false;
	}

	/** Reads the crystal texture and puts an editable copy in its place. */
	private boolean load() {
		Minecraft mc = Minecraft.getInstance();
		Optional<Resource> resource = mc.getResourceManager().getResource(TEXTURE);
		if (resource.isEmpty()) return false;
		try (InputStream in = resource.get().open(); NativeImage source = NativeImage.read(in)) {
			width = source.getWidth();
			height = source.getHeight();
			original = new int[width * height];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) original[y * width + x] = source.getPixel(x, y);
			}
			NativeImage image = new NativeImage(width, height, false);
			texture = new DynamicTexture(TEXTURE::toString, image);
			// Replaces (and closes) the texture crystals are drawn with.
			mc.getTextureManager().register(TEXTURE, texture);
			painted = null;
			return true;
		} catch (IOException | RuntimeException e) {
			SnowballClient.LOGGER.warn("Could not colour the end crystal", e);
			return false;
		}
	}

	private void show(int[] pixels) {
		NativeImage image = texture.getPixels();
		if (image == null) return;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) image.setPixel(x, y, pixels[y * width + x]);
		}
		texture.upload();
	}
}
