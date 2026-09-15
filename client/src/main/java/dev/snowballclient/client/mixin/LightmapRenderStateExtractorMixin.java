package dev.snowballclient.client.mixin;

//? if <26.1 {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
*///?}
import dev.snowballclient.client.module.ModuleRegistry;
//? if >=26.1 {
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
//?} else {
/*import net.minecraft.client.renderer.LightTexture;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

//? if >=26.1 {
@Mixin(LightmapRenderStateExtractor.class)
//?} else {
/*@Mixin(LightTexture.class)
*///?}
public abstract class LightmapRenderStateExtractorMixin {
	//? if >=26.1 {
	@Inject(method = "extract", at = @At("TAIL"))
	private void snowballclient$fullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
		// Only touch the state when vanilla just recomputed it; otherwise the lightmap is reused as-is.
		if (renderState.needsUpdate && ModuleRegistry.FULLBRIGHT != null) {
			renderState.brightness = ModuleRegistry.FULLBRIGHT.apply(renderState.brightness);
		}
	}
	//?} else {
	/*// 1.21.x reads the brightness (gamma) option while rebuilding the lightmap: the second Double.floatValue call.
	@ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 1))
	private float snowballclient$fullbright(float brightness) {
		return ModuleRegistry.FULLBRIGHT != null ? ModuleRegistry.FULLBRIGHT.apply(brightness) : brightness;
	}
	*///?}
}
