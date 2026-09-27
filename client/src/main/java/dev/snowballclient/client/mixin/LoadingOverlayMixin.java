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
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
	@Shadow
	@Final
	private boolean fadeIn;
	@Shadow
	private long fadeInStart;

	@Unique
	private final GuiCanvas snowball$canvas = new GuiCanvas();
	@Unique
	private long snowball$firstFrame;

	/**
	 * Minecraft's own background, drawn in Snowball's colour. While the screen fades in over a game
	 * that is already running, Minecraft's fill and Snowball's picture are both part-transparent; in
	 * Minecraft's red the two would mix into a red tint for half a second.
	 */
	@ModifyVariable(method = "replaceAlpha(II)I", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static int snowball$backgroundColour(int color) {
		return SnowballClient.showLoadingScreen() ? LoadingArt.BACKGROUND : color;
	}

	/**
	 * Minecraft's logo stays invisible while Snowball's screen is up. It fades in along with the
	 * screen, so for half a second it would show through Snowball's picture.
	 */
	@ModifyArg(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
	private float snowball$hideMojangLogo(float alpha) {
		return SnowballClient.showLoadingScreen() ? 0.0F : alpha;
	}

	/** Minecraft's progress bar, for the same reason: Snowball draws its own. */
	//? if >=26.1 {
	@Inject(method = "extractProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V", at = @At("HEAD"), cancellable = true)
	//?} else {
	/*@Inject(method = "drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V", at = @At("HEAD"), cancellable = true)
	*///?}
	private void snowball$hideMojangProgressBar(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, float fade, CallbackInfo ci) {
		if (SnowballClient.showLoadingScreen()) ci.cancel();
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
	private void snowball$drawLoadingScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!SnowballClient.showLoadingScreen()) return;
		long now = Util.getMillis();
		if (snowball$firstFrame == 0L) snowball$firstFrame = now;

		// Follows Minecraft's own curves exactly, so the two screens are never out of step. While the
		// game starts, the screen is solid until loading ends and then fades out over the title screen.
		// A reload while playing (a resource pack, the language, mipmaps) fades in over half a second
		// instead: drawn solid from its first frame, it cut the game to black in one frame.
		float fadeOutAnim = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
		float fadeInAnim = fadeInStart > -1L ? (now - fadeInStart) / 500.0F : -1.0F;
		float alpha;
		if (fadeOutAnim >= 1.0F) alpha = 1.0F - Mth.clamp(fadeOutAnim - 1.0F, 0.0F, 1.0F);
		else if (fadeIn) alpha = Mth.clamp(fadeInAnim, 0.0F, 1.0F);
		else alpha = 1.0F;

		SnowballClient client = SnowballClient.get();
		Theme theme = client != null ? client.theme() : LoadingArt.DEFAULT_THEME;
		//? if >=26.1 {
		graphics.nextStratum();
		//?}
		// While the game first starts, Minecraft has only compiled the shaders for shapes and images;
		// text has neither its shader nor its font until that first load is done. Asking for it earlier
		// logged "Couldn't compile pipeline minecraft:pipeline/gui_text" and drew nothing, and switching
		// it on as the load finished would pop the words in a second before the screen fades. So the
		// first start shows the snowball and the bar; a reload while playing (fadeIn) shows the words too.
		boolean textReady = minecraft.font != null && fadeIn;
		LoadingArt.draw(snowball$canvas.wrap(graphics), currentProgress, alpha, theme,
				(now - snowball$firstFrame) / 1000.0F, textReady);
	}
}
