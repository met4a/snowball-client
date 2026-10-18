package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Nick Hider in chat: every chat line passes through here before it is stored and wrapped. */
@Mixin(ChatHud.class)
public class ChatHudMixin {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;IIZ)V", at = @At("HEAD"), argsOnly = true)
	private Text snowball$nick(Text message) {
		return ModuleRegistry.NICK_HIDER != null ? ModuleRegistry.NICK_HIDER.apply(message) : message;
	}
}
