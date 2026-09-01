package com.chrystalis.mockserver.match;

import java.util.List;

/**
 * Ergebnis eines Payload-Vergleichs.
 */
public record MatchResult(List<Mismatch> mismatches) {

    private static final MatchResult MATCH = new MatchResult(List.of());

    public static MatchResult match() {
        return MATCH;
    }

    public static MatchResult of(List<Mismatch> mismatches) {
        return mismatches.isEmpty() ? MATCH : new MatchResult(List.copyOf(mismatches));
    }

    public boolean matches() {
        return mismatches.isEmpty();
    }
}
