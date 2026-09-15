package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.WeatherEffectRenderer;
//? if <26.1 {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	//? if >=26.1 {
	private void snowballclient$hideWeather(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {
	//?} else {
	/*private void snowballclient$hideWeather(MultiBufferSource buffers, Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {
	*///?}
		if (ModuleRegistry.WEATHER != null && ModuleRegistry.WEATHER.hidePrecipitation()) ci.cancel();
	}
}
