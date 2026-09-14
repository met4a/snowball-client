package dev.snowballclient.client.chat;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;

/** Finds the chat line under the mouse, using the same hit test vanilla uses for clickable chat text. */
public final class ChatLineFinder implements ActiveTextCollector {
	private final Font font;
	private final int testX;
	private final int testY;
	private Parameters parameters = new Parameters(new Matrix3x2f());
	private String result;

	public ChatLineFinder(Font font, int x, int y) {
		this.font = font;
		this.testX = x;
		this.testY = y;
	}

	@Override
	public Parameters defaultParameters() {
		return parameters;
	}

	@Override
	public void defaultParameters(Parameters parameters) {
		this.parameters = parameters;
	}

	@Override
	public void accept(TextAlignment alignment, int x, int y, Parameters params, FormattedCharSequence text) {
		int left = alignment.calculateLeft(x, font, text);
		GuiTextRenderState state = new GuiTextRenderState(font, text, params.pose(), left, y, ARGB.white(params.opacity()), 0, true, true, params.scissor());
		ActiveTextCollector.findElementUnderCursor(state, testX, testY, style -> result = plain(text));
	}

	@Override
	public void acceptScrolling(Component message, int centerX, int left, int right, int top, int bottom, Parameters params) {
	}

	/** Plain text of the line under the cursor, or null. */
	public String result() {
		return result;
	}

	private static String plain(FormattedCharSequence text) {
		StringBuilder sb = new StringBuilder();
		text.accept((index, style, codepoint) -> {
			sb.appendCodePoint(codepoint);
			return true;
		});
		return sb.toString();
	}
}
