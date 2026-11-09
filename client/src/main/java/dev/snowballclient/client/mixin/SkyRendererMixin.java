package dev.snowballclient.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.snowballclient.client.platform.SkyboxRenderer;
//? if <26.1 {
/*import net.minecraft.client.Minecraft;
*///?}
import net.minecraft.client.renderer.SkyRenderer;
//? if >=26.1 {
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Sky: the painted sky goes over the sky colour; the sunrise glow (and, by choice, the sun and moon) step aside. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	//? if >=26.1 {
	@Shadow
	@Final
	private RenderTarget renderTarget;
	//?}

	@Inject(method = "renderSkyDisc", at = @At("TAIL"))
	private void snowballclient$customSky(CallbackInfo ci) {
		//? if >=26.1 {
		RenderTarget target = renderTarget;
		//?} else {
		/*RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
		*///?}
		SkyboxRenderer.draw(target.getColorTextureView(), target.getDepthTextureView());
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void snowballclient$customSkySunrise(CallbackInfo ci) {
		if (SkyboxRenderer.active()) ci.cancel();
	}

	@Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
	private void snowballclient$customSkyCelestials(CallbackInfo ci) {
		if (SkyboxRenderer.hidesCelestials()) ci.cancel();
	}
}
