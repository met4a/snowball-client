package dev.snowballclient.client.gui;

import dev.snowballclient.client.module.Module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Filters modules for the menu search box. Every word typed must appear in the module's name,
 * description or category. Name matches are listed before description-only matches.
 */
public final class ModuleSearch {
	private ModuleSearch() {
	}

	public static List<Module> filter(Collection<Module> modules, String query) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return List.copyOf(new LinkedHashSet<>(modules));
		String[] words = q.split("\\s+");
		List<Module> nameHits = new ArrayList<>();
		List<Module> otherHits = new ArrayList<>();
		for (Module m : new LinkedHashSet<>(modules)) {
			String name = m.name().toLowerCase(Locale.ROOT);
			String haystack = name + " " + m.description().toLowerCase(Locale.ROOT) + " " + m.category().displayName().toLowerCase(Locale.ROOT);
			boolean all = true;
			boolean inName = true;
			for (String w : words) {
				if (!haystack.contains(w)) {
					all = false;
					break;
				}
				if (!name.contains(w)) inName = false;
			}
			if (!all) continue;
			(inName ? nameHits : otherHits).add(m);
		}
		nameHits.addAll(otherHits);
		return nameHits;
	}
}
