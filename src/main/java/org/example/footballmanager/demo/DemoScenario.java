package org.example.footballmanager.demo;

import java.awt.Color;
import java.util.List;

/**
 * DemoScenario — ODGOVARA NA PITANJE: "Kakav demo scenario izvodimo?".
 *
 * Sadrzi KOMPLETNU konfiguraciju pocetnog stanja demoa (ranije ugnjezdenu u
 * {@link TacticalGridDemo}):
 *
 *  - geometriju grid-a (9x8, velicina celije, granice terena 7x6)
 *  - boje terena, mreze, ekipa i prstena nosioca
 *  - definicije svih 22 igraca (label, ekipa, rola, boja, pozicija)
 *  - pocetnu poziciju lopte
 *
 * Scenario je PURE DATA — ne pokrece simulaciju i ne sadrzi UI. Definicije
 * igraca pretvara u {@link Player} objekte {@link DemoPlayerFactory}, a
 * simulaciju sklapa {@link DemoSimulationFactory}. Geometrijski pomocnici
 * (mapiranje model -> piksel koordinate) su staticki jer proistice iz same
 * grid konfiguracije i deljeni su izmedju renderera i validacije.
 */
public final class DemoScenario {

    // --- grid / layout ---
    public static final int GRID_ROWS = 9;
    public static final int GRID_COLS = 8;
    public static final int CELL_SIZE = 100;          // ceo grid staje na ekran bez skrola (1080x1680)
    public static final int PANEL_WIDTH = GRID_COLS * CELL_SIZE;
    public static final int PANEL_HEIGHT = GRID_ROWS * CELL_SIZE;

    public static final int PITCH_MIN_ROW = 1;
    public static final int PITCH_MAX_ROW = 7;
    public static final int PITCH_MIN_COL = 1;
    public static final int PITCH_MAX_COL = 6;

    // --- boje / mreza / ekipe ---
    public static final Color COLOR_PITCH = new Color(174, 226, 174);
    public static final Color COLOR_OUTSIDE = Color.WHITE;
    public static final Color COLOR_GRID_LINE = new Color(60, 60, 60);
    public static final Color COLOR_NET = new Color(120, 120, 120);
    public static final int GOAL_DEPTH = (int) (CELL_SIZE * 0.55);

    public static final String TEAM_HOME = "HOME";
    public static final String TEAM_AWAY = "AWAY";
    public static final Color COLOR_HOME = Color.BLUE;
    public static final Color COLOR_AWAY = Color.RED;
    public static final Color COLOR_GOALKEEPER = Color.YELLOW;
    public static final Color COLOR_CARRIER_RING = new Color(255, 140, 0);

    /** Definicija igraca iz scenarija (label, ekipa, rola, boja, pozicija). */
    public record PlayerDef(String label, String team, String role, Color color, Position position) {}

    private final List<PlayerDef> players;
    private final Position ballStartPosition;

    private DemoScenario() {
        this.players = List.copyOf(buildPlayers());
        this.ballStartPosition = new Position(4, 3.5);
    }

    /** Standardni demo scenario (isti sadrzaj kao ranije u TacticalGridDemo). */
    public static DemoScenario standard() {
        return new DemoScenario();
    }

    /** Definicije svih 22 igraca, u redosledu kreiranja (redosled crtanja). */
    public List<PlayerDef> getPlayers() {
        return players;
    }

    /** Pocetna pozicija lopte (position = initialPosition). */
    public Position getBallStartPosition() {
        return ballStartPosition;
    }

    private static List<PlayerDef> buildPlayers() {
        List<PlayerDef> defs = new java.util.ArrayList<>();

        // Golmani (zuti krug): HGK na spoju c1_3/c1_4, AGK na spoju c7_3/c7_4
        defs.add(new PlayerDef("HGK", TEAM_HOME, "GK", COLOR_GOALKEEPER, new Position(1, 3.5)));
        defs.add(new PlayerDef("AGK", TEAM_AWAY, "GK", COLOR_GOALKEEPER, new Position(7, 3.5)));

        // Odbrana domace ekipe (plavi) u redu 2
        defs.add(new PlayerDef("HDL", TEAM_HOME, "DL", COLOR_HOME, new Position(2, 2)));
        defs.add(new PlayerDef("HDCL", TEAM_HOME, "DCL", COLOR_HOME, new Position(2, 3)));
        defs.add(new PlayerDef("HDCR", TEAM_HOME, "DCR", COLOR_HOME, new Position(2, 4)));
        defs.add(new PlayerDef("HDR", TEAM_HOME, "DR", COLOR_HOME, new Position(2, 5)));

        // Odbrana gostujuce ekipe (crveni) u redu 6
        defs.add(new PlayerDef("ADL", TEAM_AWAY, "DL", COLOR_AWAY, new Position(6, 2)));
        defs.add(new PlayerDef("ADCL", TEAM_AWAY, "DCL", COLOR_AWAY, new Position(6, 3)));
        defs.add(new PlayerDef("ADCR", TEAM_AWAY, "DCR", COLOR_AWAY, new Position(6, 4)));
        defs.add(new PlayerDef("ADR", TEAM_AWAY, "DR", COLOR_AWAY, new Position(6, 5)));

        // Veznjaci domace ekipe (plavi) u redu 3
        defs.add(new PlayerDef("HML", TEAM_HOME, "ML", COLOR_HOME, new Position(3, 1)));
        defs.add(new PlayerDef("HCML", TEAM_HOME, "CML", COLOR_HOME, new Position(3, 3)));
        defs.add(new PlayerDef("HCMR", TEAM_HOME, "CMR", COLOR_HOME, new Position(3, 4)));
        defs.add(new PlayerDef("HMR", TEAM_HOME, "MR", COLOR_HOME, new Position(3, 6)));

        // Veznjaci gostujuce ekipe (crveni) u redu 5
        defs.add(new PlayerDef("AML", TEAM_AWAY, "ML", COLOR_AWAY, new Position(5, 1)));
        defs.add(new PlayerDef("ACML", TEAM_AWAY, "CML", COLOR_AWAY, new Position(5, 3)));
        defs.add(new PlayerDef("ACMR", TEAM_AWAY, "CMR", COLOR_AWAY, new Position(5, 4)));
        defs.add(new PlayerDef("AMR", TEAM_AWAY, "MR", COLOR_AWAY, new Position(5, 6)));

        // Napadaci gostujuce ekipe (crveni) na liniji izmedju redova 4 i 5
        defs.add(new PlayerDef("ASTL", TEAM_AWAY, "STL", COLOR_AWAY, new Position(4.5, 2)));
        defs.add(new PlayerDef("ASTR", TEAM_AWAY, "STR", COLOR_AWAY, new Position(4.5, 5)));

        // Napadaci domace ekipe (plavi): HSTL na centru pored lopte, HSTR na srednjoj liniji u c4_4
        defs.add(new PlayerDef("HSTL", TEAM_HOME, "STL", COLOR_HOME, new Position(4, 3.075)));
        defs.add(new PlayerDef("HSTR", TEAM_HOME, "STR", COLOR_HOME, new Position(4, 4)));

        return defs;
    }

    // --- geometrijski pomocnici (proistice iz grid konfiguracije) ---

    /** Da li je polje (row,column) deo terena (redovi 1-7, kolone 1-6). */
    public static boolean isPitchCell(int row, int col) {
        return row >= PITCH_MIN_ROW && row <= PITCH_MAX_ROW
            && col >= PITCH_MIN_COL && col <= PITCH_MAX_COL;
    }

    /** Mapiranje model reda na ekranski red (model red 1 je dole, red 7 gore). */
    public static int screenRow(int modelRow) {
        return (GRID_ROWS - 1) - modelRow;
    }

    /** Mapiranje model kolone na ekransku kolonu (kolone rastu levo na desno). */
    public static int screenCol(int modelCol) {
        return modelCol;
    }

    /** Centar celije po X osi u pikselima. */
    public static int cellCenterX(double col) {
        return (int) Math.round((col + 0.5) * CELL_SIZE);
    }

    /** Centar celije po Y osi u pikselima. */
    public static int cellCenterY(double row) {
        return (int) Math.round(((GRID_ROWS - 1 - row) + 0.5) * CELL_SIZE);
    }
}
