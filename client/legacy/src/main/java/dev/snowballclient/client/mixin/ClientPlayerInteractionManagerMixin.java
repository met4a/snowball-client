package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the player's own hits for the Combo and Reach HUDs. Never changes the attack itself. */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
	@Inject(method = "attackEntity", at = @At("HEAD"))
	private void snowball$recordHit(PlayerEntity player, Entity target, CallbackInfo ci) {
		double eyeY = player.y + player.getEyeHeight();
		double dx = target.x - player.x;
		double dy = target.y + target.getEyeHeight() - eyeY;
		double dz = target.z - player.z;
		ModuleRegistry.COMBAT.onAttack(target.getEntityId(), Math.sqrt(dx * dx + dy * dy + dz * dz), System.currentTimeMillis());
	}
}
