package dev.snowballclient.client.platform;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.mixin.TitleScreenAccessor;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.View;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.Window;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/** Shows a {@link View} as a Minecraft 1.8.9 screen and gives it this version's {@link Host} services. */
public final class LegacyScreen extends Screen implements Host {
	private final View view;
	private final Screen parent;
	private final LegacyCanvas canvas = new LegacyCanvas();
	/** Minecraft's own title screen, kept only to draw its spinning panorama behind the main menu. */
	private TitleScreen panorama;

	public LegacyScreen(View view, Screen parent) {
		this.view = view;
		this.parent = parent;
	}

	/** Draws the game's own menu backdrop: the panorama the vanilla title screen spins. */
	public void drawMenuBackdrop(float partialTick) {
		if (panorama == null) {
			panorama = new TitleScreen();
			panorama.init(client, width, height);
		}
		((TitleScreenAccessor) panorama).snowball$renderPanorama(0, 0, partialTick);
	}

	/** Opens a view on top of whatever is on screen now; closing it returns there. */
	public static void show(View view) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.setScreen(new LegacyScreen(view, client.currentScreen));
	}

	public View view() {
		return view;
	}

	@Override
	public void init() {
		view.attach(this, width, height);
		if (panorama != null) panorama.init(client, width, height);
	}

	@Override
	public void tick() {
		view.tick();
		// Keeps the panorama turning.
		if (panorama != null) panorama.tick();
	}

	@Override
	public void render(int mouseX, int mouseY, float delta) {
		LegacyCanvas c = canvas.frame();
		view.renderBackground(c, mouseX, mouseY, delta);
		view.render(c, mouseX, mouseY, delta);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		view.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		view.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	protected void mouseDragged(int mouseX, int mouseY, int button, long timeSinceClick) {
		view.mouseDragged(mouseX, mouseY, button);
	}

	@Override
	public void handleMouse() {
		super.handleMouse();
		int wheel = Mouse.getEventDWheel();
		if (wheel == 0) return;
		// Same conversion the game uses for click positions: window pixels to GUI units.
		int mouseX = Mouse.getEventX() * width / client.width;
		int mouseY = height - Mouse.getEventY() * height / client.height - 1;
		view.mouseScrolled(mouseX, mouseY, Math.signum(wheel));
	}

	@Override
	protected void keyPressed(char chr, int keyCode) {
		int key = LegacyKeys.toGlfw(keyCode);
		if (key != -1 && view.keyPressed(key)) return;
		if (chr >= 32 && chr != 127 && view.charTyped(String.valueOf(chr))) return;
		if (keyCode == Keyboard.KEY_ESCAPE) view.onClose();
	}

	@Override
	public boolean shouldPauseGame() {
		return view.pausesGame();
	}

	@Override
	public void removed() {
		view.removed();
	}

	// ---- Host ----

	@Override
	public void open(View next) {
		client.setScreen(new LegacyScreen(next, this));
	}

	@Override
	public void back() {
		client.setScreen(parent);
	}

	@Override
	public void closeAll() {
		client.setScreen(null);
	}

	@Override
	public Textures textures() {
		return LegacyTextures.INSTANCE;
	}

	@Override
	public double guiScale() {
		return new Window(client).getScaleFactor();
	}

	@Override
	public int fps() {
		return MinecraftClient.getCurrentFps();
	}

	@Override
	public boolean inWorld() {
		return client.world != null;
	}

	@Override
	public String keyName(int key) {
		String name = Keyboard.getKeyName(LegacyKeys.toLwjgl(key));
		return name == null ? "Unknown" : name;
	}

	@Override
	public boolean isMenuKey(int key) {
		SnowballClient snowball = SnowballClient.get();
		return snowball != null && snowball.openMenuKey().getCode() == LegacyKeys.toLwjgl(key);
	}

	@Override
	public boolean isControlDown() {
		return Screen.hasControlDown();
	}

	@Override
	public String clipboard() {
		return Screen.getClipboard();
	}

	@Override
	public void openPath(Path path) {
		// Minecraft 1.8.9 has no helper for opening files, so the desktop is asked directly.
		try {
			if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(path.toFile());
			else new ProcessBuilder("cmd", "/c", "start", "", path.toString()).start();
		} catch (IOException | RuntimeException e) {
			SnowballClient.LOGGER.warn("Could not open {}", path, e);
		}
	}
}
