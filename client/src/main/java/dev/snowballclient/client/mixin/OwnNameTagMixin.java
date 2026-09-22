package dev.snowballclient.client.mixin;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.social.SnowballPlayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws your own name above your own head in third person.
 *
 * Minecraft deliberately hides it - you always know who you are - so without this you would see
 * everybody else's Snowball badge and never your own, which is the view screenshots get taken in.
 *
 * <p>It has to hook {@link LivingEntityRenderer} rather than EntityRenderer: LivingEntityRenderer
 * overrides shouldShowName and never calls the version above it, so an injection on the parent
 * simply never runs for a player.
 */
@Mixin(LivingEntityRenderer.class)
public class OwnNameTagMixin {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void snowball$showMyOwnName(LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> shown) {
		if (Boolean.TRUE.equals(shown.getReturnValue())) return;
		if (!(entity instanceof Player) || !SnowballClient.nameTagBadges()) return;
		Minecraft mc = Minecraft.getInstance();
		// Your own body only, and only when the camera is far enough back to see it.
		if (entity != mc.getCameraEntity() || mc.options.getCameraType().isFirstPerson()) return;
		// Nothing is drawn for an account the backend has not placed on Snowball.
		if (SnowballPlayers.rank(entity.getUUID()) == null) return;
		shown.setReturnValue(true);
	}
}
