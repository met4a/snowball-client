package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.LoadingArt;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.platform.GuiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Snowball loading screen over Minecraft's own. Minecraft keeps doing its own work - the
 * reload, the timings, the fades - and this only covers the picture, with the same fade so the two
 * never show through each other.
 */
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private float currentProgress;
	@Shadow
	private long fadeOutStart;

	@Unique
	private final GuiCanvas snowball$canvas = new GuiCanvas();
	@Unique
	private long snowball$firstFrame;

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
	private void snowball$drawLoadingScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!SnowballClient.showLoadingScreen()) return;
		long now = Util.getMillis();
		if (snowball$firstFrame == 0L) snowball$firstFrame = now;

		// While the game loads, this screen owns the window: drawn solid, it hides Minecraft's own red
		// one underneath. It only fades at the end, on the curve Minecraft fades out with, so the title
		// screen appears from behind both at the same moment.
		float fadeOutAnim = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
		float alpha = fadeOutAnim >= 1.0F ? 1.0F - Mth.clamp(fadeOutAnim - 1.0F, 0.0F, 1.0F) : 1.0F;

		SnowballClient client = SnowballClient.get();
		Theme theme = client != null ? client.theme() : LoadingArt.DEFAULT_THEME;
		//? if >=26.1 {
		graphics.nextStratum();
		//?}
		LoadingArt.draw(snowball$canvas.wrap(graphics), currentProgress, alpha, theme,
				(now - snowball$firstFrame) / 1000.0F, minecraft.font != null);
	}
}
