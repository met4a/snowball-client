package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void snowballclient$fullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
		// Only touch the state when vanilla just recomputed it; otherwise the lightmap is reused as-is.
		if (renderState.needsUpdate && ModuleRegistry.FULLBRIGHT != null) {
			renderState.brightness = ModuleRegistry.FULLBRIGHT.apply(renderState.brightness);
		}
	}
}
