package dev.snowballclient.gametest;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.RadialMenuView;
import dev.snowballclient.client.gui.TitleMenuView;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.platform.ViewScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.List;
import java.util.function.Function;

/** The steps every client test takes: open and close the Snowball menu, click, run on the game thread. */
final class ClientSteps {
	static final int KEY_RIGHT_SHIFT = 344;
	static final int KEY_E = 69;
	static final int KEY_ESCAPE = 256;
	static final int KEY_ENTER = 257;
	static final int KEY_TAB = 258;
	static final int KEY_BACKSPACE = 259;

	private ClientSteps() {
	}

	/** Every client test has to end on Minecraft's own title screen, which Snowball normally replaces. */
	static void endOnTitle(ClientGameTestContext context) {
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.customMainMenu.set(false);
			return null;
		});
		context.setScreen(TitleScreen::new);
		context.waitFor(mc -> mc.gui.screen() instanceof TitleScreen, 200);
		onClient(context, mc -> {
			ModuleRegistry.INTERFACE.customMainMenu.set(true);
			return null;
		});
	}

	/** Whether this run leaves out the named test: -Ponly=menu,render runs just those. */
	static boolean skipped(String test) {
		String only = System.getProperty("snowball.only", "");
		return !only.isBlank() && !List.of(only.split(",")).contains(test);
	}

	static TitleMenuView title(Minecraft mc) {
		return mc.gui.screen() instanceof ViewScreen v && v.view() instanceof TitleMenuView t ? t : null;
	}

	static RadialMenuView menu(Minecraft mc) {
		return mc.gui.screen() instanceof ViewScreen v && v.view() instanceof RadialMenuView r ? r : null;
	}

	static RadialMenuView screen(Minecraft mc) {
		RadialMenuView r = menu(mc);
		if (r != null) return r;
		throw new AssertionError("Radial menu is not open (screen=" + mc.gui.screen() + ")");
	}

	static void openMenu(ClientGameTestContext context) {
		context.getInput().pressKey(KEY_RIGHT_SHIFT);
		context.waitForScreen(ViewScreen.class);
		context.waitFor(mc -> menu(mc) != null && menu(mc).isFullyOpen() && SnowballClient.get().wheelTextures().ready(), 200);
		context.waitTicks(4);
	}

	/** Presses the menu key without waiting, so the opening itself can be watched. */
	static void openMenuNoWait(ClientGameTestContext context) {
		context.getInput().pressKey(KEY_RIGHT_SHIFT);
	}

	static void closeMenu(ClientGameTestContext context) {
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitForScreen(null);
		context.waitTicks(4);
	}

	static void clickGui(ClientGameTestContext context, double[] guiPos) {
		double scale = onClient(context, mc -> (double) mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
		context.waitTicks(2);
		context.getInput().pressMouse(0);
		context.waitTicks(2);
	}

	@SuppressWarnings("unchecked")
	static <T> T onClient(ClientGameTestContext context, Function<Minecraft, T> function) {
		Object[] box = new Object[1];
		context.waitFor(mc -> {
			box[0] = function.apply(mc);
			return true;
		});
		return (T) box[0];
	}
}
