package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
	@ModifyReturnValue(method = "getTotalTicks", at = @At("RETURN"))
	private long snowballclient$timeChanger(long ticks) {
		return ModuleRegistry.TIME_CHANGER != null ? ModuleRegistry.TIME_CHANGER.apply(ticks) : ticks;
	}
}
