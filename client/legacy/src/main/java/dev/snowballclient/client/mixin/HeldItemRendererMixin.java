package dev.snowballclient.client.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Low Fire on 1.8.9: the burning overlay is drawn the same way as on newer versions, so it is squashed the same. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	/** A column-major matrix for OpenGL, reused every frame. */
	@Unique
	private static final FloatBuffer snowballclient$squash = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

	/** Whether the flames were squashed this frame, so exactly the matrix that was pushed is popped. */
	@Unique
	private boolean snowballclient$lowered;

	@Inject(method = "renderFireOverlay", at = @At("HEAD"))
	private void snowballclient$lowerFire(float tickDelta, CallbackInfo ci) {
		float scale = ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.scale() : 1f;
		snowballclient$lowered = scale < 1f;
		if (!snowballclient$lowered) return;
		FloatBuffer m = snowballclient$squash;
		m.clear();
		m.put(new float[]{1, 0, 0, 0, 0, scale, 0, 0, 0, ModuleRegistry.LOW_FIRE.shear(), 1, 0, 0, 0, 0, 1});
		m.flip();
		GlStateManager.pushMatrix();
		GlStateManager.multiMatrix(m);
	}

	@Inject(method = "renderFireOverlay", at = @At("RETURN"))
	private void snowballclient$restoreFire(float tickDelta, CallbackInfo ci) {
		if (snowballclient$lowered) GlStateManager.popMatrix();
		snowballclient$lowered = false;
	}

	@ModifyConstant(method = "renderFireOverlay", constant = @Constant(floatValue = 0.9F))
	private float snowballclient$fireOpacity(float alpha) {
		return ModuleRegistry.LOW_FIRE != null ? ModuleRegistry.LOW_FIRE.alpha(alpha) : alpha;
	}
}
