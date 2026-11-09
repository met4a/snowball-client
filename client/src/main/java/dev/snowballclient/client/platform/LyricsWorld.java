package dev.snowballclient.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.snowballclient.client.lyrics.Lyrics;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.misc.LyricsModule;
import dev.snowballclient.client.spotify.Playback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * The Lyrics module's words in the world on this version: each word of the song appears as it is
 * sung, somewhere in front of you (or high in the sky), turned to face you, pops in, drifts, and
 * fades a little after its line. When a song starts, its title and artist float in front of you.
 * Words stay where they appeared, so turning your head shows them from the side.
 */
public final class LyricsWorld {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int MAX_WORDS = 24;
	private static final long FADE_IN_MS = 280;
	private static final long FADE_OUT_MS = 650;
	private static final long LINGER_MS = 1_400;
	private static final long CARD_MS = 4_200;
	/** A word's letters are about this tall for each block away: some 3.5 degrees of the view. */
	private static final double LETTERS_PER_BLOCK = 0.06;
	private static final Style WORD = Style.EMPTY.withBold(true).withItalic(true);

	/** Something floating: a word, or one line of the title card. */
	private record Floating(FormattedCharSequence text, int width, Vec3 at, Vec3 toward, double approach, Quaternionf facing,
							float scale, float lineY, int argb, boolean card, long born, long dies, double yaw, double pitch) {
	}

	private static final List<Floating> LIVE = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private static String song = "";
	private static int lastLine = -1;
	private static int lastWord = -1;
	private static long lastPos = -1;
	private static ClientLevel level;

	private LyricsWorld() {
	}

	/** Once a frame while the world is drawn. */
	public static void render(OrderedSubmitNodeCollector queue) {
		Minecraft mc = Minecraft.getInstance();
		LyricsModule lyrics = ModuleRegistry.LYRICS;
		if (lyrics == null || !lyrics.isEnabled() || mc.level == null || mc.player == null) {
			clear();
			return;
		}
		if (mc.level != level) {
			clear();
			level = mc.level;
		}
		long now = System.currentTimeMillis();
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3fc f = camera.forwardVector();
		double viewYaw = Math.toDegrees(Math.atan2(-f.x(), f.z()));
		double viewPitch = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, f.y()))));
		spawn(mc, lyrics, now, eye, viewYaw, viewPitch);
		draw(queue, now, eye);
	}

	private static void clear() {
		LIVE.clear();
		song = "";
		lastLine = -1;
		lastWord = -1;
		lastPos = -1;
		level = null;
	}

	private static void spawn(Minecraft mc, LyricsModule lyrics, long now, Vec3 eye, double viewYaw, double viewPitch) {
		Playback p = lyrics.playback();
		String key = p.hasTrack() ? p.track() + "\n" + p.artists() : "";
		boolean sky = LyricsModule.SKY.equals(lyrics.words.get());
		if (!key.equals(song)) {
			song = key;
			LIVE.clear();
			lastLine = -1;
			lastWord = -1;
			lastPos = -1;
			if (p.playing() && lyrics.titleCard.isOn()) card(mc, lyrics, p, now, eye, viewYaw, viewPitch, sky);
		}
		if (LyricsModule.OFF.equals(lyrics.words.get()) || !p.playing() || !lyrics.timed()) return;
		Lyrics l = lyrics.lyrics();
		long pos = lyrics.position(now);
		int li = l.lineAt(pos);
		boolean jumped = lastPos >= 0 && (pos < lastPos - 400 || pos > lastPos + 3_000);
		lastPos = pos;
		if (li < 0) {
			lastLine = -1;
			lastWord = -1;
			return;
		}
		Lyrics.Line line = l.lines().get(li);
		int wi = Lyrics.wordAt(line, pos);
		// After a seek, carry on from where the song is now rather than flooding the view.
		if (jumped) {
			lastLine = li;
			lastWord = wi;
			return;
		}
		if (li == lastLine && wi <= lastWord) return;
		int from = li == lastLine ? lastWord + 1 : li == lastLine + 1 ? 0 : wi;
		for (int i = Math.max(Math.max(0, from), wi - 3); i <= wi; i++) {
			word(mc, lyrics, line, line.words().get(i), pos, now, eye, viewYaw, viewPitch, sky);
		}
		lastLine = li;
		lastWord = wi;
	}

	private static void word(Minecraft mc, LyricsModule lyrics, Lyrics.Line line, Lyrics.Word word, long pos, long now, Vec3 eye,
							 double viewYaw, double viewPitch, boolean sky) {
		// Of a few spots, the one furthest from the words already floating, so they do not pile up.
		double yaw = viewYaw;
		double pitch = viewPitch;
		double bestGap = -1;
		for (int tries = 0; tries < 8; tries++) {
			double y = viewYaw + range(-52, 52);
			double x = sky ? -range(14, 42) : Math.max(-40, Math.min(30, viewPitch + range(-13, 13)));
			double gap = Double.MAX_VALUE;
			for (Floating other : LIVE) {
				double dy = Math.abs(((y - other.yaw()) % 360 + 540) % 360 - 180);
				gap = Math.min(gap, Math.hypot(dy, x - other.pitch()));
			}
			if (gap > bestGap) {
				bestGap = gap;
				yaw = y;
				pitch = x;
			}
		}
		double distance = sky ? skyDistance(mc) * range(0.85, 1.1) : range(6.5, 11);
		Vec3 dir = direction(yaw, pitch);
		Vec3 at = eye.add(dir.scale(distance));
		float scale = (float) (distance * LETTERS_PER_BLOCK / 9.0 * lyrics.size.get() * range(0.75, 1.35));
		// Turned to face you, give or take a little, so words sit at angles like the reference.
		Quaternionf facing = new Quaternionf().rotationYXZ((float) (Math.PI - Math.toRadians(yaw + range(-20, 20))),
				(float) Math.toRadians(-pitch), (float) Math.toRadians(range(-10, 10)));
		long life = Math.max(600, Math.min(6_000, line.endMs() - pos)) + LINGER_MS;
		FormattedCharSequence text = FormattedCharSequence.forward(word.text(), WORD);
		while (LIVE.size() >= MAX_WORDS) LIVE.remove(0);
		LIVE.add(new Floating(text, mc.font.width(text), at, dir, distance * 0.06, facing, scale, 0f, lyrics.color.argb(), false, now, now + life, yaw, pitch));
	}

	/** "NOW PLAYING", the song and the artist, in front of you, coming slowly closer. */
	private static void card(Minecraft mc, LyricsModule lyrics, Playback p, long now, Vec3 eye, double viewYaw, double viewPitch, boolean sky) {
		double pitch = Math.max(-25, Math.min(15, viewPitch)) - 4;
		double distance = sky ? skyDistance(mc) * 0.6 : 10;
		Vec3 dir = direction(viewYaw, pitch);
		Vec3 at = eye.add(dir.scale(distance));
		Quaternionf facing = new Quaternionf().rotationYXZ((float) (Math.PI - Math.toRadians(viewYaw)), (float) Math.toRadians(-pitch), 0f);
		float base = (float) (distance * LETTERS_PER_BLOCK / 9.0 * lyrics.size.get());
		String artist = p.artists().isEmpty() ? p.album() : p.artists();
		cardLine(mc, "♪ NOW PLAYING", base * 0.7f, -15f, 0xD9FFFFFF, at, dir, distance, facing, now, viewYaw, pitch);
		cardLine(mc, p.track(), base * 1.9f, -2.5f, lyrics.color.argb(), at, dir, distance, facing, now, viewYaw, pitch);
		if (!artist.isEmpty()) cardLine(mc, artist, base * 1.05f, 9.5f, 0xFFFFFFFF, at, dir, distance, facing, now, viewYaw, pitch);
	}

	private static void cardLine(Minecraft mc, String line, float scale, float lineY, int argb, Vec3 at, Vec3 dir, double distance,
								 Quaternionf facing, long now, double yaw, double pitch) {
		FormattedCharSequence text = FormattedCharSequence.forward(line, Style.EMPTY.withBold(true));
		LIVE.add(new Floating(text, mc.font.width(text), at, dir, distance * 0.2, facing, scale, lineY, argb, true, now, now + CARD_MS, yaw, pitch));
	}

	private static void draw(OrderedSubmitNodeCollector queue, long now, Vec3 eye) {
		Iterator<Floating> it = LIVE.iterator();
		while (it.hasNext()) {
			Floating w = it.next();
			if (now >= w.dies()) {
				it.remove();
				continue;
			}
			float age = now - w.born();
			float life = w.dies() - w.born();
			float in = Math.min(1f, age / FADE_IN_MS);
			float out = Math.min(1f, (w.dies() - now) / (float) FADE_OUT_MS);
			float alpha = smooth(in) * smooth(out);
			int a = Math.round(((w.argb() >>> 24) & 0xFF) * alpha);
			// The font draws nearly see-through text opaque, which would flash as a word fades.
			if (a < 8) continue;
			float pop = w.card() ? 0.92f + 0.08f * smooth(in) : 0.6f + 0.4f * backOut(in);
			float grow = 1f + 0.12f * (1f - out);
			float progress = age / life;
			double come = w.approach() * smooth(progress);
			double rise = w.card() ? 0 : w.scale() * 9 * 0.8 * progress;
			PoseStack pose = new PoseStack();
			pose.translate(w.at().x - eye.x - w.toward().x * come, w.at().y - eye.y - w.toward().y * come + rise, w.at().z - eye.z - w.toward().z * come);
			pose.mulPose(w.facing());
			float s = w.scale() * pop * grow;
			pose.scale(s, -s, s);
			int argb = (a << 24) | (w.argb() & 0xFFFFFF);
			queue.submitText(pose, -w.width() / 2f, w.lineY() - 4.5f, w.text(), true, Font.DisplayMode.NORMAL, FULL_BRIGHT, argb, 0, 0);
		}
	}

	/** Far enough to read as sky, near enough to stay out of the fog at short render distances. */
	private static double skyDistance(Minecraft mc) {
		return Math.max(24, Math.min(56, mc.options.getEffectiveRenderDistance() * 16 * 0.6));
	}

	/** Minecraft's look direction for a yaw and pitch, in degrees. */
	private static Vec3 direction(double yaw, double pitch) {
		double y = Math.toRadians(yaw);
		double x = Math.toRadians(pitch);
		return new Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
	}

	private static double range(double min, double max) {
		return min + RANDOM.nextDouble() * (max - min);
	}

	private static float smooth(float t) {
		float c = Math.max(0f, Math.min(1f, t));
		return c * c * (3f - 2f * c);
	}

	/** Overshoots a little before settling, for the pop as a word appears. */
	private static float backOut(float t) {
		float c = Math.max(0f, Math.min(1f, t)) - 1f;
		return 1f + c * c * (2.7f * c + 1.7f);
	}
}
