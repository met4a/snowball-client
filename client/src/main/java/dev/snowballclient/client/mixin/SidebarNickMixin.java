package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Nick Hider on the scoreboard sidebar. Each line is swapped where it is measured and where it is
 * drawn, so the sidebar is sized for the name it shows. Lines are built from the score holder's name
 * and the team's prefix and suffix, which is where servers often write a player's name, so the whole
 * line is checked rather than only the holder.
 */
//? if >=26.1 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)
*///?}
public class SidebarNickMixin {
	@ModifyArg(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
	private FormattedText snowball$measureNick(FormattedText text) {
		return ModuleRegistry.NICK_HIDER != null && text instanceof Component line ? ModuleRegistry.NICK_HIDER.sidebar(line) : text;
	}

	//? if >=26.1 {
	@ModifyArg(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"), index = 1)
	//?} else {
	/*@ModifyArg(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"), index = 1)
	*///?}
	private Component snowball$drawNick(Component line) {
		return ModuleRegistry.NICK_HIDER != null ? ModuleRegistry.NICK_HIDER.sidebar(line) : line;
	}
}
