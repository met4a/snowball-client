package dev.snowballclient.client.potion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides when a potion warning is due. It only ever fires on the tick a whole second ticks away, so
 * a warning is one sound and not twenty, and it forgets an effect as soon as it is gone.
 */
public final class PotionAlerts {
	/**
	 * @param seconds how many seconds are left
	 * @param step    0 for the first warning, 1 for the second, then one more per countdown second
	 */
	public record Alert(int seconds, int step) {
	}

	private final Map<String, Integer> lastSeconds = new HashMap<>();
	private final Set<String> warned = new HashSet<>();
	private final Set<String> seen = new HashSet<>();
	private int first;
	private int second;
	private int countdown;
	private boolean refillResets;

	public void beginTick(int firstWarning, int secondWarning, int countdownFrom, boolean refillResets) {
		this.first = firstWarning;
		this.second = secondWarning;
		this.countdown = countdownFrom;
		this.refillResets = refillResets;
		seen.clear();
	}

	/** @return the warning to play for this effect, or null when there is nothing to say this tick */
	public Alert check(String effectId, int durationTicks) {
		int seconds = (durationTicks + 19) / 20;
		seen.add(effectId);
		Integer previous = lastSeconds.put(effectId, seconds);
		if (previous == null) return null;
		if (seconds > previous) {
			// Topped up: the warnings for this effect are armed again.
			if (refillResets) forget(effectId);
			return null;
		}
		if (seconds == previous) return null;

		if (first > 0 && seconds == first) return fire(effectId, seconds, 0);
		if (second > 0 && seconds == second) return fire(effectId, seconds, 1);
		if (countdown > 0 && seconds >= 1 && seconds <= countdown) return fire(effectId, seconds, 2 + (countdown - seconds));
		return null;
	}

	/** Drops effects that ran out or were removed, so the next one starts clean. */
	public void endTick() {
		lastSeconds.keySet().removeIf(id -> !seen.contains(id));
		warned.removeIf(key -> !seen.contains(key.substring(0, key.indexOf('|'))));
	}

	public void clear() {
		lastSeconds.clear();
		warned.clear();
		seen.clear();
	}

	private Alert fire(String effectId, int seconds, int step) {
		if (!warned.add(effectId + '|' + step)) return null;
		return new Alert(seconds, step);
	}

	private void forget(String effectId) {
		warned.removeIf(key -> key.startsWith(effectId + '|'));
	}
}
