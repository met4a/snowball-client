package dev.snowballclient.client.crash;

/**
 * What the crash screen tells the player, worked out by each version from the crash it caught.
 *
 * @param error   the error in one line
 * @param suspect the mod it most likely came from, or null when that could not be told
 * @param report  where the crash report was written, relative to the game folder; null if it could not be
 * @param place   where the player was, which decides what the screen says about their world
 */
public record CrashDetails(String error, String suspect, String report, Place place) {
	public enum Place { MENUS, SINGLEPLAYER, MULTIPLAYER }

	private static final int MAX_MESSAGE = 300;

	/** The error's type and message, as the first line of a crash report has them. */
	public static String describe(Throwable error) {
		if (error == null) return "Unknown error";
		String message = error.getMessage();
		if ((message == null || message.isBlank()) && error.getCause() != null && error.getCause() != error) {
			return error.getClass().getSimpleName() + ", caused by " + describe(error.getCause());
		}
		String type = error.getClass().getSimpleName().isEmpty() ? error.getClass().getName() : error.getClass().getSimpleName();
		if (message == null || message.isBlank()) return type;
		String line = message.strip().lines().findFirst().orElse("").strip();
		if (line.length() > MAX_MESSAGE) line = line.substring(0, MAX_MESSAGE - 3) + "...";
		return type + ": " + line;
	}
}
