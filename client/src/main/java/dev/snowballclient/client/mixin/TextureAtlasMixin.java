package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
	@Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
	private void snowballclient$throttleAnimations(CallbackInfo ci) {
		if (ModuleRegistry.ANIMATIONS != null && ModuleRegistry.ANIMATIONS.skipThisTick()) ci.cancel();
	}
}
