package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.snowballclient.client.platform.LegacyCrashRecovery;
import dev.snowballclient.client.platform.SnowballTitle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	/** Replaces the title screen with Snowball's main menu as it is opened. */
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen snowball$mainMenu(Screen screen) {
		return SnowballTitle.replace(screen);
	}

	/**
	 * Anti-Crash: a frame of the game loop that throws is handed to {@link LegacyCrashRecovery}
	 * first, and only crashes it turns down reach Minecraft's own handler. The method's name keeps
	 * this frame, present under every crash, from being taken for the cause.
	 */
	@WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;runGameLoop()V"))
	private void snowball$antiCrash(MinecraftClient client, Operation<Void> frame) {
		try {
			frame.call(client);
		} catch (Throwable error) {
			if (!LegacyCrashRecovery.recover(client, error)) throw error;
			return;
		}
		LegacyCrashRecovery.frameDone();
	}
}
