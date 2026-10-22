package org.tifo.footballmanager;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The squad's display order is written once.
 *
 * <p>Seven copies of {@code { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 }} lived in four files — four in
 * {@code tifo.js}, one in {@code training-view.js}, one in {@code match-view.js}, and a seventh named
 * {@code ROLE_ORDER} in {@code lineup-board.js}. They agreed on the order, which is exactly why nobody
 * noticed the part they did not agree on: <b>what an unknown position sorts as</b>. Five call sites
 * said {@code ?? 5} and two said {@code ?? 9}, so a player whose position the map did not hold landed
 * after the forwards on two screens and between the midfield and the wingers on the others.
 *
 * <p>The module is {@code ui/position-order.js}. This test holds the shape — no file may declare the
 * map again — because the defect was never in the order, it was in the copying.
 */
class PositionOrderIsDeclaredOnceTest {

    /** The map as it was written in all seven copies. Spacing is normalised before matching. */
    private static final Pattern MAP_LITERAL =
            Pattern.compile("\\{\\s*GK:\\s*0\\s*,\\s*DEF:\\s*1\\s*,\\s*MID:\\s*2\\s*,\\s*WNG:\\s*3\\s*,\\s*ATT:\\s*4\\s*}");

    private static final String MODULE = "js/ui/position-order.js";

    @Test
    @DisplayName("no frontend file declares the position map again")
    void theMapIsNotCopied() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ProductionSource.staticRoot())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".js")).toList()) {
                if (file.getFileName().toString().equals("position-order.js")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    if (MAP_LITERAL.matcher(line).find()) {
                        offenders.add(ProductionSource.staticRoot().relativize(file) + ":" + (i + 1)
                                + "  " + line);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "the position map was declared again. Import POSITION_ORDER / positionRank / "
                        + "byPosition from ui/position-order.js — a recopied map is how the ?? 5 against "
                        + "?? 9 split happened in the first place");
    }

    @Test
    @DisplayName("the module exists, and the screens that sort a squad use it")
    void theScreensUseTheModule() throws IOException {
        Path module = ProductionSource.staticFile(MODULE);
        String source = Files.readString(module);
        for (String exported : List.of("export const POSITION_ORDER", "export function positionRank",
                "export function byPosition")) {
            assertTrue(source.contains(exported),
                    MODULE + " must export " + exported + " — the module is the point of the change, "
                            + "and a module nothing can import is worse than the copies");
        }

        // The four files that held the seven copies, and the import each one needs.
        for (String file : List.of("js/tifo.js", "js/pages/views/training-view.js",
                "js/pages/views/match-view.js", "js/pages/views/lineup-board.js")) {
            String body = Files.readString(ProductionSource.staticFile(file));
            assertTrue(body.contains("position-order.js"),
                    file + " sorted a squad by its own copy of the map. It must import from "
                            + MODULE + " instead");
        }
    }
}
