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

	/** Whether this view leans in 3D while 3D Menus is on. Views that need exact positions say no. */
	protected boolean allowsDepth() {
		return true;
	}

	/** Whether the game should blur what is behind this view (where the version can). */
	public boolean blursBackground() {
		return !showsPanorama() && backgroundBlur.getAsBoolean();
	}

	private static java.util.function.BooleanSupplier backgroundBlur = () -> false;

	/** A view's dimming over the world: lighter when the world behind is blurred, which already sets the menu apart. */
	protected final float backdropDim(float alpha) {
		return blursBackground() && host != null && host.inWorld() ? alpha * 0.6f : alpha;
	}

	/** Set once by the client: whether Snowball's screens blur what is behind them. */
	public static void setBackgroundBlur(java.util.function.BooleanSupplier blur) {
		backgroundBlur = blur == null ? () -> false : blur;
	}

	// ---- What the platform screens call: drawing and the mouse go through 3D Menus first ----

	/** Draws one frame, leaning in 3D when that is on; the mouse is mapped onto the leaning page. */
	public final void renderFrame(Canvas c, int mouseX, int mouseY, float partialTick) {
		if (!Depth.begin(this, c, width, height, mouseX, mouseY, allowsDepth(), showsPanorama())) {
			render(c, mouseX, mouseY, partialTick);
			return;
		}
		try {
			double[] p = Depth.toPage(this, mouseX, mouseY);
			render(c, (int) Math.floor(p[0]), (int) Math.floor(p[1]), partialTick);
		} finally {
			Depth.end(c);
		}
	}

	public final boolean clickFrame(double mouseX, double mouseY, int button) {
		double[] p = Depth.toPage(this, mouseX, mouseY);
		return mouseClicked(p[0], p[1], button);
	}

	public final boolean releaseFrame(double mouseX, double mouseY, int button) {
		double[] p = Depth.toPage(this, mouseX, mouseY);
		return mouseReleased(p[0], p[1], button);
	}

	public final boolean dragFrame(double mouseX, double mouseY, int button) {
		double[] p = Depth.toPage(this, mouseX, mouseY);
		return mouseDragged(p[0], p[1], button);
	}

	public final boolean scrollFrame(double mouseX, double mouseY, double amount) {
		double[] p = Depth.toPage(this, mouseX, mouseY);
		return mouseScrolled(p[0], p[1], amount);
	}

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
