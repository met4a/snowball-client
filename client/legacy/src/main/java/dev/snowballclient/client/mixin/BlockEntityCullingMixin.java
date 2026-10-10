package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.LegacyCulling;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smart Culling: a chest, sign, banner or head that walls hide completely is not drawn. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {
	@Inject(method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V", at = @At("HEAD"), cancellable = true)
	private void snowball$skipHidden(BlockEntity blockEntity, float tickDelta, int breaking, CallbackInfo ci) {
		// Something being broken keeps its cracks.
		if (breaking < 0 && LegacyCulling.hideBlockEntity(blockEntity)) ci.cancel();
	}
}
