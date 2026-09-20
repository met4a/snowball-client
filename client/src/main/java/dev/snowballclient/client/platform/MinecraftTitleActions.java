package dev.snowballclient.client.platform;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.BanDetails;
import com.mojang.realmsclient.RealmsMainScreen;
import dev.snowballclient.client.ui.Canvas;
import dev.snowballclient.client.ui.TitleActions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** The Snowball main menu's buttons on this Minecraft version: every one opens the game's own screen. */
public final class MinecraftTitleActions implements TitleActions {
	private final GameProfile profile;
	private final CompletableFuture<Optional<PlayerSkin>> skin;

	public MinecraftTitleActions() {
		profile = Minecraft.getInstance().getGameProfile();
		skin = Minecraft.getInstance().getSkinManager().get(profile);
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Opens a Minecraft screen that returns to the Snowball menu when it is closed. */
	private static void open(Function<Screen, Screen> factory) {
		if (mc().gui.screen() instanceof ViewScreen screen) screen.openScreen(factory);
	}

	@Override
	public void drawBackdrop(Canvas c, float partialTick) {
		if (c instanceof GuiCanvas canvas && mc().gui.screen() instanceof ViewScreen screen) {
			screen.drawMenuBackdrop(canvas.graphics(), partialTick);
		}
	}

	@Override
	public String playerName() {
		return mc().getUser().getName();
	}

	@Override
	public void drawPlayerFace(Canvas c, int x, int y, int size) {
		if (!(c instanceof GuiCanvas canvas)) return;
		PlayerSkin resolved = skin.getNow(Optional.empty()).orElseGet(() -> DefaultPlayerSkin.get(profile));
		PlayerFaceExtractor.extractRenderState(canvas.graphics(), resolved, x, y, size);
	}

	@Override
	public void singleplayer() {
		open(SelectWorldScreen::new);
	}

	@Override
	public void multiplayer() {
		// Same rule as the vanilla button: the safety notice is shown until the player has accepted it.
		open(parent -> mc().options.skipMultiplayerWarning ? new JoinMultiplayerScreen(parent) : new SafetyScreen(parent));
	}

	@Override
	public String multiplayerBlockedReason() {
		Minecraft minecraft = mc();
		if (minecraft.allowsMultiplayer()) return null;
		if (!minecraft.isNameBanned()) return "Multiplayer is turned off for this account.";
		BanDetails ban = minecraft.multiplayerBan();
		return ban != null && ban.expires() != null
				? "This account is temporarily banned from multiplayer."
				: "This account is banned from multiplayer.";
	}

	@Override
	public boolean hasRealms() {
		return true;
	}

	@Override
	public void realms() {
		open(RealmsMainScreen::new);
	}

	@Override
	public void options() {
		open(parent -> new OptionsScreen(parent, mc().options, false));
	}

	@Override
	public void language() {
		open(parent -> new LanguageSelectScreen(parent, mc().options, mc().getLanguageManager()));
	}

	@Override
	public boolean hasAccessibility() {
		return true;
	}

	@Override
	public void accessibility() {
		open(parent -> new AccessibilityOptionsScreen(parent, mc().options));
	}

	@Override
	public Runnable credits() {
		return () -> open(CreditsAndAttributionScreen::new);
	}

	@Override
	public void quit() {
		mc().stop();
	}

	@Override
	public void showVanillaTitle() {
		mc().gui.setScreen(new TitleScreen());
	}

	@Override
	public String minecraftVersion() {
		return SharedConstants.getCurrentVersion().name();
	}

	@Override
	public int modCount() {
		return FabricLoader.getInstance().getAllMods().size();
	}
}
