package dev.snowballclient.client.platform;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.snowballclient.client.gui.snowglass.GlassPanel;
import dev.snowballclient.client.gui.snowglass.MaterialLevel;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
//? if >=26.1 {
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
//?} else {
/*import net.minecraft.client.gui.render.state.GuiElementRenderState;
*///?}
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * One Snowglass panel in the interface's render state: a single quad, a little larger than the panel
 * when it casts a shadow, whose vertices carry everything the glass shader needs about the panel.
 * Kept and refilled frame after frame by {@link SnowglassRenderer}.
 */
final class GlassElement implements GuiElementRenderState {
	/** Room around the panel for its soft shadow, in GUI units. */
	private static final float SHADOW_MARGIN = 9f;

	private final Matrix3x2f pose = new Matrix3x2f();
	private float x0;
	private float y0;
	private float x1;
	private float y1;
	private float centreX;
	private float centreY;
	private int colour;
	private int halfWidth8;
	private int halfHeight8;
	private int radius8;
	private int flags;
	private float rim;
	private float frost;
	private float saturation;
	private TextureSetup textures;
	private ScreenRectangle bounds;
	/** What {@link #bounds} was worked out from, so an unchanged panel does not make a new one each frame. */
	private final Matrix3x2f boundsPose = new Matrix3x2f();
	private float boundsX0 = Float.NaN;
	private float boundsY0;
	private float boundsX1;
	private float boundsY1;

	void set(Matrix3x2fc pose, GlassPanel panel, MaterialLevel material, int flags, TextureSetup textures) {
		this.pose.set(pose);
		float margin = (flags & SnowglassRenderer.FLAG_SHADOW) != 0 ? SHADOW_MARGIN : 1f;
		x0 = panel.x() - margin;
		y0 = panel.y() - margin;
		x1 = panel.x() + panel.width() + margin;
		y1 = panel.y() + panel.height() + margin;
		centreX = panel.x() + panel.width() / 2f;
		centreY = panel.y() + panel.height() / 2f;
		int fade = Math.round(Math.max(0f, Math.min(1f, panel.alpha())) * 255f);
		colour = (fade << 24) | (panel.tint() & 0x00FFFFFF);
		halfWidth8 = Math.min(Short.MAX_VALUE, Math.round(panel.width() * 4f));
		halfHeight8 = Math.min(Short.MAX_VALUE, Math.round(panel.height() * 4f));
		radius8 = Math.min(Short.MAX_VALUE, Math.max(0, panel.radius() * 8));
		this.flags = flags;
		rim = clamp(material.rim());
		frost = clamp(material.frost());
		saturation = clamp(material.saturation() - 1f);
		this.textures = textures;
		if (bounds == null || x0 != boundsX0 || y0 != boundsY0 || x1 != boundsX1 || y1 != boundsY1 || !this.pose.equals(boundsPose)) {
			bounds = new ScreenRectangle((int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1 - x0), (int) Math.ceil(y1 - y0))
					.transformMaxBounds(this.pose);
			boundsPose.set(this.pose);
			boundsX0 = x0;
			boundsY0 = y0;
			boundsX1 = x1;
			boundsY1 = y1;
		}
	}

	@Override
	public void buildVertices(VertexConsumer consumer) {
		// The same corner order as vanilla's own blits.
		vertex(consumer, x0, y0);
		vertex(consumer, x0, y1);
		vertex(consumer, x1, y1);
		vertex(consumer, x1, y0);
	}

	private void vertex(VertexConsumer consumer, float x, float y) {
		consumer.addVertexWith2DPose(pose, x, y)
				.setColor(colour)
				.setUv(x - centreX, y - centreY)
				.setUv1(halfWidth8, halfHeight8)
				.setUv2(radius8, flags)
				.setNormal(rim, frost, saturation);
	}

	@Override
	public RenderPipeline pipeline() {
		return SnowglassRenderer.glassPipeline();
	}

	@Override
	public TextureSetup textureSetup() {
		return textures;
	}

	@Override
	public ScreenRectangle scissorArea() {
		// Snowball's canvas does not clip yet; when it does, the clip goes here.
		return null;
	}

	@Override
	public ScreenRectangle bounds() {
		return bounds;
	}

	private static float clamp(float v) {
		return Math.max(-1f, Math.min(1f, v));
	}
}
