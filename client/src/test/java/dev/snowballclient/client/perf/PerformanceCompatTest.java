package dev.snowballclient.client.perf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceCompatTest {
	private static boolean hasError(List<PerformanceCompat.Finding> findings, String text) {
		return findings.stream().anyMatch(f -> f.severity() == PerformanceCompat.Severity.ERROR && f.message().contains(text));
	}

	@Test
	void sodiumAndOptifineConflict() {
		assertTrue(hasError(PerformanceCompat.analyze(Set.of("sodium", "optifabric")), "OptiFine"));
	}

	@Test
	void irisNeedsSodium() {
		assertTrue(hasError(PerformanceCompat.analyze(Set.of("iris")), "Iris requires Sodium"));
		assertFalse(hasError(PerformanceCompat.analyze(Set.of("iris", "sodium")), "Iris"));
	}

	@Test
	void fullStackHasNoErrorsOrTips() {
		List<PerformanceCompat.Finding> findings = PerformanceCompat.analyze(Set.of("sodium", "lithium", "ferritecore", "immediatelyfast"));
		assertTrue(findings.isEmpty(), findings.toString());
	}

	@Test
	void vanillaGetsTips() {
		List<PerformanceCompat.Finding> findings = PerformanceCompat.analyze(Set.of());
		assertTrue(findings.stream().allMatch(f -> f.severity() == PerformanceCompat.Severity.TIP));
		assertEquals(3, findings.size());
	}
}
