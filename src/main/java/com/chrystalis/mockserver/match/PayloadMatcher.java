package com.chrystalis.mockserver.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Vergleicht den Request-Body mit dem erwarteten Payload aus der Konfiguration.
 *
 * <p>Der Vergleich ist ein Subset-Match: alles, was im erwarteten Payload steht, muss im Request
 * vorkommen und uebereinstimmen; zusaetzliche Felder im Request sind erlaubt. Arrays muessen die
 * gleiche Laenge haben und elementweise an derselben Position passen. Zahlen werden numerisch
 * verglichen, damit {@code 1} und {@code 1.0} als gleich gelten.
 */
public final class PayloadMatcher {

    public static final String MISSING = "<fehlt>";

    private static final String ROOT = "$";

    private PayloadMatcher() {
    }

    /**
     * @param expected erwarteter Payload aus der Konfiguration
     * @param actual   tatsaechlicher Request-Body, darf {@code null} sein
     * @return alle gefundenen Abweichungen; leer, wenn der Request passt
     */
    public static MatchResult match(JsonNode expected, JsonNode actual) {
        if (expected == null || expected.isNull()) {
            return MatchResult.match();
        }
        List<Mismatch> mismatches = new ArrayList<>();
        compare(ROOT, expected, actual, mismatches);
        return MatchResult.of(mismatches);
    }

    private static void compare(String path, JsonNode expected, JsonNode actual, List<Mismatch> mismatches) {
        if (actual == null || actual.isMissingNode()) {
            mismatches.add(new Mismatch(path, describe(expected), MISSING));
            return;
        }

        if (expected.isObject()) {
            compareObject(path, expected, actual, mismatches);
            return;
        }
        if (expected.isArray()) {
            compareArray(path, expected, actual, mismatches);
            return;
        }
        compareValue(path, expected, actual, mismatches);
    }

    private static void compareObject(String path, JsonNode expected, JsonNode actual, List<Mismatch> mismatches) {
        if (!actual.isObject()) {
            mismatches.add(typeMismatch(path, expected, actual));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            compare(path + "." + field.getKey(), field.getValue(), actual.get(field.getKey()), mismatches);
        }
    }

    private static void compareArray(String path, JsonNode expected, JsonNode actual, List<Mismatch> mismatches) {
        if (!actual.isArray()) {
            mismatches.add(typeMismatch(path, expected, actual));
            return;
        }
        if (expected.size() != actual.size()) {
            mismatches.add(new Mismatch(path,
                    "Array mit " + expected.size() + " Element(en)",
                    "Array mit " + actual.size() + " Element(en)"));
            return;
        }
        for (int i = 0; i < expected.size(); i++) {
            compare(path + "[" + i + "]", expected.get(i), actual.get(i), mismatches);
        }
    }

    private static void compareValue(String path, JsonNode expected, JsonNode actual, List<Mismatch> mismatches) {
        if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                mismatches.add(new Mismatch(path, describe(expected), describe(actual)));
            }
            return;
        }
        if (actual.isObject() || actual.isArray()) {
            mismatches.add(typeMismatch(path, expected, actual));
            return;
        }
        if (!expected.equals(actual)) {
            mismatches.add(new Mismatch(path, describe(expected), describe(actual)));
        }
    }

    private static Mismatch typeMismatch(String path, JsonNode expected, JsonNode actual) {
        return new Mismatch(path,
                typeName(expected) + " " + describe(expected),
                typeName(actual) + " " + describe(actual));
    }

    private static String typeName(JsonNode node) {
        if (node.isObject()) {
            return "Objekt";
        }
        if (node.isArray()) {
            return "Array";
        }
        if (node.isTextual()) {
            return "Text";
        }
        if (node.isNumber()) {
            return "Zahl";
        }
        if (node.isBoolean()) {
            return "Boolean";
        }
        return "Wert";
    }

    private static String describe(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return MISSING;
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }
}
