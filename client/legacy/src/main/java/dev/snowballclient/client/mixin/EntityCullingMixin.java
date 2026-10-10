package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.LegacyCulling;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Smart Culling: an entity walls hide completely is not drawn. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityCullingMixin {
	// The method that draws one entity at a position; it has no readable name in 1.8.9's mappings.
	@Inject(method = "method_6913(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At("HEAD"), cancellable = true)
	private void snowball$skipHidden(Entity entity, double x, double y, double z, float yaw, float tickDelta, boolean hitbox, CallbackInfoReturnable<Boolean> cir) {
		if (LegacyCulling.hideEntity(entity)) cir.setReturnValue(false);
	}
}
