package dev.snowballclient.client.platform;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.PlayerSpot;
import dev.snowballclient.client.ui.Textures;
import dev.snowballclient.client.ui.View;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Function;

/** Shows a {@link View} as a Minecraft screen and gives it this version's {@link Host} services. */
public final class ViewScreen extends Screen implements Host {
	private final View view;
	private final Screen parent;
	private final GuiCanvas canvas = new GuiCanvas();
	private KeyEvent currentKey;

	public ViewScreen(View view, Screen parent) {
		super(Component.literal(view.title()));
		this.view = view;
		this.parent = parent;
	}

	/**
	 * Opens a view: in a world with 3D Menus set to windows, as a window standing in front of the
	 * player; otherwise on top of whatever is on screen now, and closing it returns there.
	 */
	public static void show(View view) {
		if (SpatialGame.takes(view)) SpatialGame.open(view);
		else showOnScreen(view);
	}

	/** Opens a view on top of whatever is on screen now; closing it returns there. */
	public static void showOnScreen(View view) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new ViewScreen(view, mc.gui.screen()));
	}

	public View view() {
		return view;
	}

	/** Opens a Minecraft screen from a view; the factory receives the screen to return to. Null opens nothing. */
	public void openScreen(Function<Screen, Screen> factory) {
		Screen next = factory.apply(this);
		if (next != null) minecraft.gui.setScreen(next);
	}

	@Override
	protected void init() {
		view.attach(this, width, height);
		//? if >=26.1 {
		if (view.showsPanorama()) minecraft.gameRenderer.panorama().startSpin();
		//?}
	}

	//? if <26.1 {
	/*@Override
	protected boolean panoramaShouldSpin() {
		return view.showsPanorama();
	}
	*///?}

	/** Draws the game's own menu backdrop: the rotating panorama, as sharp as on the vanilla title screen. */
	public void drawMenuBackdrop(GuiGraphicsExtractor g, float partialTick) {
		extractPanorama(g, partialTick);
	}

	@Override
	public void tick() {
		view.tick();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// The game's own backdrop first: in a world that is the blur (which Glass GUI can swap for
		// clear glass), then the view's dimming on top.
		if (view.blursBackground()) super.extractBackground(g, mouseX, mouseY, partialTick);
		view.renderBackground(canvas.wrap(g), mouseX, mouseY, partialTick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		view.renderFrame(canvas.wrap(g), mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return view.clickFrame(event.x(), event.y(), event.buttonInfo().button()) || super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return view.releaseFrame(event.x(), event.y(), event.buttonInfo().button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return view.dragFrame(event.x(), event.y(), event.buttonInfo().button()) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return view.scrollFrame(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		currentKey = event;
		try {
			if (view.keyPressed(event.key())) return true;
		} finally {
			currentKey = null;
		}
		// Escape reaches onClose() here.
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return event.isAllowedChatCharacter() && view.charTyped(event.codepointAsString());
	}

	@Override
	public boolean isPauseScreen() {
		return view.pausesGame();
	}

	@Override
	public void onClose() {
		view.onClose();
	}

	@Override
	public void removed() {
		//? if >=26.1 {
		if (view.showsPanorama()) minecraft.gameRenderer.panorama().holdSpin();
		//?}
		view.removed();
	}

	// ---- Host ----

	@Override
	public void open(View next) {
		minecraft.gui.setScreen(new ViewScreen(next, this));
	}

	@Override
	public void back() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void closeAll() {
		minecraft.gui.setScreen(null);
	}

	@Override
	public Textures textures() {
		return GameHost.INSTANCE.textures();
	}

	@Override
	public double guiScale() {
		return GameHost.INSTANCE.guiScale();
	}

	@Override
	public int fps() {
		return GameHost.INSTANCE.fps();
	}

	@Override
	public boolean inWorld() {
		return GameHost.INSTANCE.inWorld();
	}

	@Override
	public String keyName(int key) {
		return GameHost.INSTANCE.keyName(key);
	}

	@Override
	public boolean isMenuKey(int key) {
		return currentKey != null && currentKey.key() == key && SnowballClient.get().openMenuKey().matches(currentKey);
	}

	@Override
	public boolean isControlDown() {
		return GameHost.INSTANCE.isControlDown();
	}

	@Override
	public boolean isShiftDown() {
		return GameHost.INSTANCE.isShiftDown();
	}

	@Override
	public String clipboard() {
		return GameHost.INSTANCE.clipboard();
	}

	@Override
	public void copyToClipboard(String text) {
		GameHost.INSTANCE.copyToClipboard(text);
	}

	@Override
	public PlayerSpot playerSpot() {
		return GameHost.INSTANCE.playerSpot();
	}

	@Override
	public void openPath(Path path) {
		GameHost.INSTANCE.openPath(path);
	}
}
