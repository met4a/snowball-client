package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.CustomSky;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=26.1 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/** Custom Sky's Tint fog: in open air under the painted sky, distant land fades into the sky's colour. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Shadow
	private FogType getFogType(Camera camera) {
		throw new AssertionError();
	}

	//? if >=26.1 {
	@Inject(method = "computeFogColor", at = @At("TAIL"))
	private void snowballclient$skyFog(Camera camera, float partialTicks, ClientLevel level, int renderDistance, float darkenWorld, Vector4f color, CallbackInfo ci) {
		tint(camera, level, color);
	}
	//?} else {
	/*@Inject(method = "computeFogColor", at = @At("RETURN"))
	private void snowballclient$skyFog(Camera camera, float partialTicks, ClientLevel level, int renderDistance, float darkenWorld, CallbackInfoReturnable<Vector4f> cir) {
		tint(camera, level, cir.getReturnValue());
	}
	*///?}

	private void tint(Camera camera, ClientLevel level, Vector4f color) {
		CustomSky sky = ModuleRegistry.CUSTOM_SKY;
		if (sky == null || !sky.isEnabled() || !sky.tintFog.isOn() || color == null || level == null) return;
		if (level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD || getFogType(camera) != FogType.NONE) return;
		var player = Minecraft.getInstance().player;
		// Blindness and Darkness keep their black fog.
		if (player != null && (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS))) return;
		int fog = sky.fog(level.getRainLevel(1f));
		color.set(((fog >> 16) & 0xFF) / 255f, ((fog >> 8) & 0xFF) / 255f, (fog & 0xFF) / 255f, color.w());
	}
}
