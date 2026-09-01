package com.chrystalis.mockserver.match;

/**
 * Eine einzelne Abweichung zwischen erwartetem und tatsaechlichem Payload.
 *
 * @param path     JSON-Pfad der Abweichung, z. B. {@code $.user.role}
 * @param expected erwarteter Wert als Text
 * @param actual   tatsaechlicher Wert als Text, {@code <fehlt>} wenn das Feld nicht vorhanden ist
 */
public record Mismatch(String path, String expected, String actual) {
}
