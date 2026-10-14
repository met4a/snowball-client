package dev.snowballclient.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.client.Menu3D;
import dev.snowballclient.client.spatial.SpatialWindow;
import dev.snowballclient.client.spatial.SpatialWindows;
import dev.snowballclient.client.spatial.Vec;
import dev.snowballclient.client.ui.Host;
import dev.snowballclient.client.ui.UiTexture;
import dev.snowballclient.client.ui.View;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Snowball's menus as windows in the world on this version: opens them in front of the camera,
 * feeds the windows the camera and the mouse, and draws their pages as quads while the world is
 * drawn. The windows themselves are {@link SpatialWindows}.
 */
public final class SpatialGame implements SpatialWindows.Platform {
	private static final SpatialGame INSTANCE = new SpatialGame();
	/** Draw orders for the windows' layers: after anything the game puts in order 0 and 1. */
	private static final int FIRST_ORDER = 1000;
	/** Full light, so windows look the same in a cave as in the sun. */
	private static final int FULL_BRIGHT = 0xF000F0;
	/** Never nearer than this, in blocks, when a block is in the way. */
	private static final double NEAREST = 0.8;
	private static final int GLFW_PRESS = 1;
	private static final int GLFW_RELEASE = 0;
	/**
	 * Minecraft's text render type: textured, see-through where the texture is, lit full bright. A
	 * method reference, as the build turns calls written ".text(" into ".drawString(" for 1.21.
	 */
	private static final Function<Identifier, RenderType> TEXT = RenderTypes::text;

	private final SpatialWindows windows = new SpatialWindows(this);
	private final Sink sink = new Sink();
	private ClientLevel level;
	private long lastFrame;

	private SpatialGame() {
	}

	public static SpatialWindows windows() {
		return INSTANCE.windows;
	}

	/** Whether {@code view} should open as a window in the world rather than on the screen. */
	public static boolean takes(View view) {
		Minecraft mc = Minecraft.getInstance();
		Menu3D menus = ModuleRegistry.MENU_3D;
		return menus != null && menus.windows() && view.floats() && mc.level != null && mc.player != null && mc.gui.screen() == null;
	}

	/** Opens {@code view} as a window in front of the camera. */
	public static void open(View view) {
		Minecraft mc = Minecraft.getInstance();
		if (INSTANCE.level != mc.level) {
			INSTANCE.windows.clear();
			INSTANCE.level = mc.level;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		Vec eye = eye(camera);
		Vec look = look(camera);
		INSTANCE.windows.open(view, eye, look, placement(mc, eye));
	}

	/** Whether the crosshair is on a window, so the block behind it is not outlined. */
	public static boolean pointing() {
		return !INSTANCE.windows.isEmpty() && INSTANCE.windows.pointing();
	}

	/** The menu key with a window under the crosshair closes that window; true when it did. */
	public static boolean closePointedAt() {
		SpatialWindows windows = INSTANCE.windows;
		if (windows.isEmpty() || windows.hover().part() == SpatialWindow.Part.NONE) return false;
		SpatialWindow target = windows.pointedAt();
		if (target == null) return false;
		windows.close(target);
		return true;
	}

	/** A game tick: views in windows tick, and windows left in another world are dropped. */
	public static void tick(Minecraft mc) {
		if (INSTANCE.windows.isEmpty()) return;
		if (mc.level == null || mc.player == null || mc.level != INSTANCE.level || ModuleRegistry.MENU_3D == null || !ModuleRegistry.MENU_3D.windows()) {
			INSTANCE.windows.clear();
			return;
		}
		INSTANCE.windows.tick();
	}

	/** A mouse button; true when a window took it and the game must not see it. */
	public static boolean button(int button, int action) {
		Minecraft mc = Minecraft.getInstance();
		if (INSTANCE.windows.isEmpty() || mc.gui.screen() != null || mc.player == null) return false;
		if (action == GLFW_PRESS) return INSTANCE.windows.press(button);
		if (action == GLFW_RELEASE) return INSTANCE.windows.release(button);
		return false;
	}

	/** The mouse wheel; true when a window took it (the hotbar stays where it is). */
	public static boolean scroll(double amount) {
		Minecraft mc = Minecraft.getInstance();
		if (INSTANCE.windows.isEmpty() || mc.gui.screen() != null || mc.player == null) return false;
		return INSTANCE.windows.scroll(amount);
	}

	/** Once a frame while the world is drawn: windows move, find the crosshair, and are drawn into {@code queue}. */
	public static void render(OrderedSubmitNodeCollector queue) {
		INSTANCE.frame(queue);
	}

	private void frame(OrderedSubmitNodeCollector queue) {
		if (windows.isEmpty()) {
			lastFrame = 0;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.level != level) return;
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec eye = eye(camera);
		// With a screen up (typing, the pause menu) the crosshair points at nothing.
		windows.update(eye, look(camera), dt, mc.gui.screen() == null);
		windows.draw(0f);
		sink.start(queue);
		windows.emit(eye, sink);
	}

	private static Vec eye(Camera camera) {
		Vec3 p = camera.position();
		return new Vec(p.x, p.y, p.z);
	}

	private static Vec look(Camera camera) {
		Vector3fc f = camera.forwardVector();
		return new Vec(f.x(), f.y(), f.z());
	}

	/**
	 * Windows show the menu at the screen's GUI size, scaled so a new page fills the chosen share of
	 * the view at the chosen distance; nearer when a block is in the way.
	 */
	private static SpatialWindows.Placement placement(Minecraft mc, Vec eye) {
		Menu3D menus = ModuleRegistry.MENU_3D;
		int width = mc.getWindow().getGuiScaledWidth();
		int height = mc.getWindow().getGuiScaledHeight();
		double distance = menus.distance.get();
		double fov = Math.toRadians(mc.options.fov().get());
		double blocksPerUnit = 2 * distance * Math.tan(fov / 2) * (menus.size.get() / 100.0) / height;
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
			Vec3 at = hit.getLocation();
			double blocked = Math.sqrt((at.x - eye.x()) * (at.x - eye.x()) + (at.y - eye.y()) * (at.y - eye.y()) + (at.z - eye.z()) * (at.z - eye.z()));
			if (blocked - 0.3 < distance) {
				// Keep the window's size in the view: nearer means smaller in the world.
				double nearer = Math.max(NEAREST, blocked - 0.3);
				blocksPerUnit *= nearer / distance;
				distance = nearer;
			}
		}
		double density = Math.max(2, mc.getWindow().getGuiScale());
		return new SpatialWindows.Placement(width, height, blocksPerUnit, distance, density);
	}

	// ---- SpatialWindows.Platform ----

	@Override
	public Host game() {
		return GameHost.INSTANCE;
	}

	@Override
	public void showOnScreen(View view) {
		ViewScreen.showOnScreen(view);
	}

	@Override
	public void startTyping(View view) {
		Minecraft.getInstance().gui.setScreen(new SpatialTypingScreen(view));
	}

	@Override
	public int textWidth(String text) {
		return Minecraft.getInstance().font.width(text);
	}

	/**
	 * Hands each run of quads to the game as custom geometry in its own draw order, so runs are
	 * drawn one over another in the order the page drew them, whatever their textures.
	 */
	private static final class Sink implements SpatialWindows.QuadSink {
		/** Per quad: x, y, z of four corners, then u0, v0, u1, v1, then the colour. */
		private static final int STRIDE = 17;
		private OrderedSubmitNodeCollector queue;
		private int order;
		private UiTexture texture;
		private float[] data = new float[STRIDE * 64];
		private int quads;

		void start(OrderedSubmitNodeCollector queue) {
			this.queue = queue;
			order = FIRST_ORDER;
		}

		/** The next draw order: drawn over everything handed before it. */
		private OrderedSubmitNodeCollector next() {
			return queue instanceof SubmitNodeCollector ordered ? ordered.order(order++) : queue;
		}

		@Override
		public void line(String text, float[] matrix, int argb, boolean shadow) {
			// The font draws all but fully see-through text opaque, which would flash as a window fades.
			if ((argb >>> 24) < 8) return;
			PoseStack pose = new PoseStack();
			pose.mulPose(new Matrix4f().set(matrix));
			// Read as the game reads a string: formatting codes change the colour rather than print.
			FormattedCharSequence line = sink -> StringDecomposer.iterateFormatted(text, Style.EMPTY, sink);
			next().submitText(pose, 0f, 0f, line, shadow, Font.DisplayMode.NORMAL, FULL_BRIGHT, argb, 0, 0);
		}

		@Override
		public void beginRun(UiTexture texture) {
			this.texture = texture;
			quads = 0;
		}

		@Override
		public void quad(float[] corners, float u0, float v0, float u1, float v1, int argb) {
			if ((quads + 1) * STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
			int o = quads * STRIDE;
			System.arraycopy(corners, 0, data, o, 12);
			data[o + 12] = u0;
			data[o + 13] = v0;
			data[o + 14] = u1;
			data[o + 15] = v1;
			data[o + 16] = Float.intBitsToFloat(argb);
			quads++;
		}

		@Override
		public void endRun() {
			if (quads == 0 || texture == null) return;
			// The game draws what it is given later in the frame: the run keeps its own copy.
			float[] run = Arrays.copyOf(data, quads * STRIDE);
			int count = quads;
			RenderType type = TEXT.apply(MinecraftTextures.id(texture));
			next().submitCustomGeometry(new PoseStack(), type, (pose, consumer) -> {
				for (int q = 0; q < count; q++) {
					int o = q * STRIDE;
					float u0 = run[o + 12];
					float v0 = run[o + 13];
					float u1 = run[o + 14];
					float v1 = run[o + 15];
					int argb = Float.floatToRawIntBits(run[o + 16]);
					// Top left, bottom left, bottom right, top right: counter-clockwise seen from in front.
					consumer.addVertex(run[o], run[o + 1], run[o + 2]).setColor(argb).setUv(u0, v0).setLight(FULL_BRIGHT);
					consumer.addVertex(run[o + 3], run[o + 4], run[o + 5]).setColor(argb).setUv(u0, v1).setLight(FULL_BRIGHT);
					consumer.addVertex(run[o + 6], run[o + 7], run[o + 8]).setColor(argb).setUv(u1, v1).setLight(FULL_BRIGHT);
					consumer.addVertex(run[o + 9], run[o + 10], run[o + 11]).setColor(argb).setUv(u1, v0).setLight(FULL_BRIGHT);
				}
			});
		}
	}
}
