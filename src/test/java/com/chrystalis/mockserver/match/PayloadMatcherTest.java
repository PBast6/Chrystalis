package com.chrystalis.mockserver.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    private static MatchResult match(String expected, String actual) {
        return PayloadMatcher.match(json(expected), actual == null ? null : json(actual));
    }

    @Test
    @DisplayName("Zusaetzliche Felder im Request sind erlaubt")
    void additionalFieldsAreAllowed() {
        MatchResult result = match("""
                {"user": {"name": "Ada"}}""", """
                {"user": {"name": "Ada", "role": "admin"}, "traceId": "abc"}""");

        assertThat(result.matches()).isTrue();
    }

    @Test
    @DisplayName("Verschachtelte Felder werden rekursiv verglichen")
    void nestedValueMismatchIsReportedWithPath() {
        MatchResult result = match("""
                {"user": {"name": "Ada", "role": "admin"}}""", """
                {"user": {"name": "Ada", "role": "guest"}}""");

        assertThat(result.matches()).isFalse();
        assertThat(result.mismatches()).containsExactly(new Mismatch("$.user.role", "admin", "guest"));
    }

    @Test
    @DisplayName("Fehlende Felder werden als <fehlt> gemeldet")
    void missingFieldIsReported() {
        MatchResult result = match("""
                {"user": {"name": "Ada", "role": "admin"}}""", """
                {"user": {"name": "Ada"}}""");

        assertThat(result.mismatches())
                .containsExactly(new Mismatch("$.user.role", "admin", PayloadMatcher.MISSING));
    }

    @Test
    @DisplayName("Mehrere Abweichungen werden gesammelt, nicht nur die erste")
    void collectsAllMismatches() {
        MatchResult result = match("""
                {"a": 1, "b": "x"}""", """
                {"a": 2, "b": "y"}""");

        assertThat(result.mismatches()).extracting(Mismatch::path).containsExactly("$.a", "$.b");
    }

    @Test
    @DisplayName("Zahlen werden numerisch verglichen: 1 und 1.0 sind gleich")
    void numbersAreComparedNumerically() {
        assertThat(match("{\"n\": 1}", "{\"n\": 1.0}").matches()).isTrue();
        assertThat(match("{\"n\": 1}", "{\"n\": 2}").matches()).isFalse();
    }

    @Test
    @DisplayName("Ein Typwechsel wird mit beiden Typnamen gemeldet")
    void typeMismatchIsReported() {
        MatchResult result = match("""
                {"user": {"name": "Ada"}}""", """
                {"user": "Ada"}""");

        assertThat(result.mismatches()).hasSize(1);
        Mismatch mismatch = result.mismatches().get(0);
        assertThat(mismatch.path()).isEqualTo("$.user");
        assertThat(mismatch.expected()).startsWith("Objekt");
        assertThat(mismatch.actual()).startsWith("Text");
    }

    @Test
    @DisplayName("Arrays muessen die gleiche Laenge haben und elementweise passen")
    void arraysAreComparedElementwise() {
        assertThat(match("""
                {"items": [{"sku": "A"}, {"sku": "B"}]}""", """
                {"items": [{"sku": "A", "qty": 2}, {"sku": "B"}]}""").matches()).isTrue();

        MatchResult wrongElement = match("""
                {"items": [{"sku": "A"}]}""", """
                {"items": [{"sku": "Z"}]}""");
        assertThat(wrongElement.mismatches())
                .containsExactly(new Mismatch("$.items[0].sku", "A", "Z"));

        MatchResult wrongSize = match("""
                {"items": [{"sku": "A"}]}""", """
                {"items": []}""");
        assertThat(wrongSize.mismatches()).extracting(Mismatch::path).containsExactly("$.items");
    }

    @Test
    @DisplayName("Ohne erwarteten Payload passt jeder Body")
    void noExpectationMatchesEverything() {
        assertThat(PayloadMatcher.match(null, json("{\"x\": 1}")).matches()).isTrue();
        assertThat(PayloadMatcher.match(MAPPER.nullNode(), null).matches()).isTrue();
    }

    @Test
    @DisplayName("Ein fehlender Body gilt als Abweichung auf der Wurzel")
    void missingBodyIsReportedAtRoot() {
        MatchResult result = PayloadMatcher.match(json("{\"a\": 1}"), null);

        assertThat(result.mismatches()).extracting(Mismatch::path).containsExactly("$");
        assertThat(result.mismatches()).extracting(Mismatch::actual).containsExactly(PayloadMatcher.MISSING);
    }

    @Test
    @DisplayName("Skalare Erwartungen auf oberster Ebene funktionieren ebenfalls")
    void scalarRootExpectation() {
        assertThat(PayloadMatcher.match(json("\"pong\""), json("\"pong\"")).matches()).isTrue();
        assertThat(PayloadMatcher.match(json("\"pong\""), json("\"ping\"")).mismatches())
                .isEqualTo(List.of(new Mismatch("$", "pong", "ping")));
    }

    @Test
    @DisplayName("Ein erwartetes null verlangt das Feld, aber mit Wert null")
    void expectedNullRequiresThePresentNullField() {
        assertThat(match("{\"a\": null}", "{\"a\": null}").matches()).isTrue();
        assertThat(match("{\"a\": null}", "{\"a\": 1}").mismatches())
                .containsExactly(new Mismatch("$.a", "null", "1"));
        assertThat(match("{\"a\": null}", "{\"b\": 1}").mismatches())
                .containsExactly(new Mismatch("$.a", "null", PayloadMatcher.MISSING));
    }

    @Test
    @DisplayName("Booleans werden auf Gleichheit geprueft")
    void booleansAreCompared() {
        assertThat(match("{\"ok\": true}", "{\"ok\": true}").matches()).isTrue();
        assertThat(match("{\"ok\": true}", "{\"ok\": false}").mismatches())
                .containsExactly(new Mismatch("$.ok", "true", "false"));
        assertThat(match("{\"ok\": true}", "{\"ok\": \"true\"}").mismatches())
                .containsExactly(new Mismatch("$.ok", "true", "true"));
    }

    @Test
    @DisplayName("Ein leeres erwartetes Objekt stellt keine Bedingung")
    void emptyExpectedObjectMatchesAnyObject() {
        assertThat(match("{}", "{\"x\": 1, \"y\": [1, 2]}").matches()).isTrue();
        assertThat(match("{\"a\": {}}", "{\"a\": {\"tief\": true}}").matches()).isTrue();
        assertThat(match("{}", "[1, 2]").mismatches()).extracting(Mismatch::path).containsExactly("$");
    }

    @Test
    @DisplayName("In einem Array aus Objekten wird die Position mitgemeldet")
    void reportsIndexInsideArrays() {
        MatchResult result = match("""
                {"items": [{"sku": "A"}, {"sku": "B"}, {"sku": "C"}]}""", """
                {"items": [{"sku": "A"}, {"sku": "X"}, {"sku": "C"}]}""");

        assertThat(result.mismatches())
                .containsExactly(new Mismatch("$.items[1].sku", "B", "X"));
    }

    @Test
    @DisplayName("Verschachtelte Arrays werden rekursiv verglichen")
    void nestedArraysAreCompared() {
        assertThat(match("{\"m\": [[1, 2], [3]]}", "{\"m\": [[1, 2], [3]]}").matches()).isTrue();
        assertThat(match("{\"m\": [[1, 2]]}", "{\"m\": [[1, 9]]}").mismatches())
                .containsExactly(new Mismatch("$.m[0][1]", "2", "9"));
    }

    @Test
    @DisplayName("Ein Array wird nicht gegen ein Objekt verglichen")
    void arrayVersusObjectIsATypeMismatch() {
        MatchResult result = match("{\"a\": [1]}", "{\"a\": {\"0\": 1}}");

        assertThat(result.mismatches()).hasSize(1);
        assertThat(result.mismatches().get(0).expected()).startsWith("Array");
        assertThat(result.mismatches().get(0).actual()).startsWith("Objekt");
    }
}
