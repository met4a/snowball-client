package dev.snowballclient.client.ui;

/**
 * A full-screen piece of Snowball interface (menu, settings, gallery) written once for every Minecraft
 * version. Each version shows it inside its own screen class, which forwards drawing and input here.
 */
public abstract class View {
	private final String title;
	protected Host host;
	protected int width;
	protected int height;

	protected View(String title) {
		this.title = title;
	}

	public final String title() {
		return title;
	}

	/** Called when the view is shown and again whenever the window size changes. */
	public final void attach(Host host, int width, int height) {
		this.host = host;
		this.width = width;
		this.height = height;
		init();
	}

	protected void init() {
	}

	public void tick() {
	}

	public void renderBackground(Canvas c, int mouseX, int mouseY, float partialTick) {
	}

	public abstract void render(Canvas c, int mouseX, int mouseY, float partialTick);

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button) {
		return false;
	}

	/** @param amount positive when scrolling up */
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	/** @param key GLFW key code, see {@link Keys}. Escape reaches {@link #onClose()} when this returns false. */
	public boolean keyPressed(int key) {
		return false;
	}

	/** Printable text the player typed, already limited to characters chat allows. */
	public boolean charTyped(String text) {
		return false;
	}

	/** Escape pressed: goes back to where the view was opened from unless overridden. */
	public void onClose() {
		host.back();
	}

	/** The view stopped being shown: it was closed, or another screen replaced it. */
	public void removed() {
	}

	public boolean pausesGame() {
		return true;
	}

	/** True for the main menu: the game draws its rotating panorama behind it. */
	public boolean showsPanorama() {
		return false;
	}
}
