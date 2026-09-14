package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int snowballclient$limitWhenUnfocused(int limit) {
		return ModuleRegistry.INACTIVE_FPS != null ? ModuleRegistry.INACTIVE_FPS.apply(limit, Minecraft.getInstance().isWindowActive()) : limit;
	}
}
