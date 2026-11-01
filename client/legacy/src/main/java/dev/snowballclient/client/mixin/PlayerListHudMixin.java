package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nick Hider in the player list: only your own line, found by your player's id. */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void snowball$nick(PlayerListEntry entry, CallbackInfoReturnable<String> name) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (ModuleRegistry.NICK_HIDER == null || client.player == null || entry.getProfile() == null) return;
		if (!client.player.getUuid().equals(entry.getProfile().getId())) return;
		name.setReturnValue(ModuleRegistry.NICK_HIDER.tabName(name.getReturnValue()));
	}
}
