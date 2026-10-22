package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.ProductionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two decimal places, once.
 *
 * <p>The formula was copied 37 times across 32 files before {@link Rounding} existed. This test does
 * two things: it pins the <b>rule</b> — {@link Math#round} is half up, at the scaled value, which is
 * not what {@code BigDecimal} does by default — and it holds the <b>shape</b>, so a new copy of the
 * formula in production source is caught rather than quietly joining the other 37.
 */
class RoundingTest {

    /**
     * The copy-paste pattern, as it was actually written: {@code Math.round(expr * 100.0) / 100.0}.
     *
     * <p>Deliberately narrow. It matches the two-decimal case only; {@code * 1000.0 / 10.0} (one
     * decimal) and {@code * 100.0 / 100} (percent) are different rules and stay where they are.
     */
    private static final Pattern TWO_DECIMAL_COPY =
            Pattern.compile("Math\\.round\\(.*\\*\\s*100\\.0\\s*\\)\\s*/\\s*100\\.0");

    @Test
    @DisplayName("rounds half up at the scaled value, and does so for money")
    void roundingRule() {
        // 2.675 is stored as 2.67499999…, so the naive `Math.round(x * 100)` on that double happens to
        // land on 267. Pinning both numbers documents that the rule is the arithmetic one, not a
        // BigDecimal-with-its-default-rounding one, which would answer 2.67.
        assertEquals(2.68, Rounding.to2(2.675),
                "two decimal places, half up: the scaled value is rounded, not the double");
        assertEquals(-2.67, Rounding.to2(-2.675),
                "a negative value goes through the same arithmetic, not an absolute-value trick");
        assertEquals(0.33, Rounding.to2(1.0 / 3.0));
        assertEquals(1.0, Rounding.to2(1.0),
                "a value already on two decimals must come back unchanged");
        assertEquals(0.0, Rounding.to2(Double.NaN),
                "NaN maps to 0 through Math.round; it must not be a surprise at a money call site");
    }

    @Test
    @DisplayName("no production source copy-pastes the two-decimal formula")
    void theFormulaIsNotCopied() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ProductionSource.root())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("Rounding.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    if (TWO_DECIMAL_COPY.matcher(line).find()) {
                        offenders.add(file + ":" + (i + 1) + "  " + line);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "the two-decimal formula was copied again. Call Rounding.to2(x) instead of writing "
                        + "Math.round(x * 100.0) / 100.0 — the rule is one place, and it is tested once");
    }
}
