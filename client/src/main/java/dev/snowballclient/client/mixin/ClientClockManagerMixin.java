package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.snowballclient.client.module.ModuleRegistry;
//? if >=26.1 {
import net.minecraft.client.ClientClockManager;
//?} else {
/*import net.minecraft.client.multiplayer.ClientLevel;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Time Changer: 26.x reads the time of day from the client's world clocks, 1.21.x from the client level data.
//? if >=26.1 {
@Mixin(ClientClockManager.class)
//?} else {
/*@Mixin(ClientLevel.ClientLevelData.class)
*///?}
public abstract class ClientClockManagerMixin {
	//? if >=26.1 {
	@ModifyReturnValue(method = "getTotalTicks", at = @At("RETURN"))
	//?} else {
	/*@ModifyReturnValue(method = "getDayTime", at = @At("RETURN"))
	*///?}
	private long snowballclient$timeChanger(long ticks) {
		return ModuleRegistry.TIME_CHANGER != null ? ModuleRegistry.TIME_CHANGER.apply(ticks) : ticks;
	}
}
