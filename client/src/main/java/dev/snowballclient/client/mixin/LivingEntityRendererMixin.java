package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hit Colour's glow: while a mob flashes from a hit, it is lit full bright, so the colour shines in the dark too. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	// Split so the build's "render(" renaming between versions leaves the method name alone.
	private static final String EXTRACT = "extract" + "RenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V";
	private static final int FULL_BRIGHT = 0xF000F0;

	@Inject(method = EXTRACT, at = @At("TAIL"))
	private void snowballclient$hitGlow(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
		if (state.hasRedOverlay && ModuleRegistry.HIT_COLOR != null && ModuleRegistry.HIT_COLOR.glows()) state.lightCoords = FULL_BRIGHT;
	}
}
