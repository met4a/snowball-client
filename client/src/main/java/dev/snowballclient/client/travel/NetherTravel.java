package dev.snowballclient.client.travel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Nether travel: one block in the Nether is eight in the Overworld, across X and Z. Height is not
 * scaled, so Y carries over unchanged. Portals link to the block the scaled position falls in.
 */
public final class NetherTravel {
	/** An optional sign, digits and one decimal point; a comma is taken as the decimal point too. */
	private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)");
	/** Minecraft's world border: nothing further out can be reached. */
	private static final double LIMIT = 30_000_000;
	/** The Nether's bedrock roof; a portal above it leads onto the roof. */
	public static final int NETHER_ROOF = 127;

	public enum Direction {
		TO_NETHER("Overworld", "Nether", 1.0 / 8),
		TO_OVERWORLD("Nether", "Overworld", 8);

		public final String from;
		public final String to;
		private final double scale;

		Direction(String from, String to, double scale) {
			this.from = from;
			this.to = to;
			this.scale = scale;
		}

		/** X or Z on the other side. */
		public double apply(double coordinate) {
			return coordinate * scale;
		}

		public Direction reversed() {
			return this == TO_NETHER ? TO_OVERWORLD : TO_NETHER;
		}
	}

	/** Each coordinate converted, or null where the input was blank or not a number. */
	public record Result(Double x, Double y, Double z) {
		/** Whether X and Z, the ones that matter for a portal, were both given. */
		public boolean complete() {
			return x != null && z != null;
		}
	}

	private NetherTravel() {
	}

	/** The number typed, or null when the text is blank or is not a number within the world. */
	public static Double parse(String text) {
		if (text == null) return null;
		String t = text.trim();
		if (!NUMBER.matcher(t).matches()) return null;
		double v = Double.parseDouble(t.replace(',', '.'));
		return Math.abs(v) <= LIMIT ? v : null;
	}

	public static Result convert(String x, String y, String z, Direction direction) {
		Double px = parse(x);
		Double pz = parse(z);
		return new Result(px == null ? null : direction.apply(px), parse(y), pz == null ? null : direction.apply(pz));
	}

	/** A coordinate as it is shown: whole numbers plainly, others to at most three decimals. */
	public static String format(double v) {
		BigDecimal d = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
		if (d.signum() == 0) return "0";
		return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
	}

	/** The block a coordinate falls in, which is where a portal links to. */
	public static long block(double v) {
		return (long) Math.floor(v);
	}

	/** The converted position as text to paste, block coordinates separated by spaces ("-13 64 300"). */
	public static String copyText(Result r) {
		if (!r.complete()) return "";
		// Without a height, "~" keeps the current one, so the text still works in /tp.
		return block(r.x()) + " " + (r.y() == null ? "~" : String.valueOf(block(r.y()))) + " " + block(r.z());
	}
}
