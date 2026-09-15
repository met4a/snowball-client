package dev.snowballclient.client.mixin;

import dev.snowballclient.client.chat.ChatLineFinder;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.components.ChatComponent;
//?}
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
import org.spongepowered.asm.mixin.Shadow;
//?}
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
	//? if >=26.1 {
	@Shadow
	private ChatComponent.DisplayMode displayMode;
	//?}

	protected ChatScreenMixin(Component title) {
		super(title);
	}

	/** Right-click a chat line to copy its text. */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void snowballclient$copyLine(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (event.button() != 1 || ModuleRegistry.CHAT == null || !ModuleRegistry.CHAT.copyEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		ChatLineFinder finder = new ChatLineFinder(font, (int) event.x(), (int) event.y());
		//? if >=26.1 {
		mc.gui.hud.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), mc.gui.hud.getGuiTicks(), displayMode);
		//?} else {
		/*// 1.21.x has no chat display modes; the last argument says the chat screen is open.
		mc.gui.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), mc.gui.getGuiTicks(), true);
		*///?}
		String text = finder.result();
		if (text == null || text.isBlank()) return;
		mc.keyboardHandler.setClipboard(text.strip());
		NotificationCenter.post("Chat", "Message copied");
		cir.setReturnValue(true);
	}
}
