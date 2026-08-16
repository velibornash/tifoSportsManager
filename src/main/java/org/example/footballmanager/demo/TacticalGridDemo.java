package org.example.footballmanager.demo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TacticalGridDemo - Demonstracija osnovne mreže (grid foundation).
 *
 * Prikazuje fudbalski teren kao 9 × 8 grid (row-major, koordinata "row,column"):
 *
 *   - 9 redova:  0..8
 *   - 8 kolona:  0..7
 *   - red 7 je fizički GORNJI red terena
 *   - red 1 je fizički DONJI red terena
 *   - kolone rastu s leva na desno
 *
 * Sam teren (playing field) čini tačno 7 × 6 polja:
 *   - redovi 1..7
 *   - kolone 1..6
 *
 * Van terena (crvene belo):
 *   - red 0, red 8
 *   - kolona 0, kolona 7
 *
 * Igraci su modelirani kao {@link Player} objekti (identitet, ekipa, rola,
 * boja, position, alternativePosition), a lopta kao {@link Ball} objekat.
 * Renderer (DrawingPanel) samo cita stanje iz igraca i lopte — ne poseduje
 * pozicije. Simulaciju vodi {@link SimulationEngine} (prvi simulacioni demo):
 * SAMO HOME igraci se krecu, nosilac lopte bira PASS/CARRY/SHOT, lopta leti
 * ka primaocu/golu, po golu se sve resetuje na pocetno stanje.
 */
public class TacticalGridDemo extends JFrame {

    private static final int GRID_ROWS = 9;
    private static final int GRID_COLS = 8;
    private static final int CELL_SIZE = 100;          // ceo grid staje na ekran bez skrola (1080x1680)
    private static final int PANEL_WIDTH = GRID_COLS * CELL_SIZE;
    private static final int PANEL_HEIGHT = GRID_ROWS * CELL_SIZE;

    private static final int PITCH_MIN_ROW = 1;
    private static final int PITCH_MAX_ROW = 7;
    private static final int PITCH_MIN_COL = 1;
    private static final int PITCH_MAX_COL = 6;

    private static final Color COLOR_PITCH = new Color(174, 226, 174);
    private static final Color COLOR_OUTSIDE = Color.WHITE;
    private static final Color COLOR_GRID_LINE = new Color(60, 60, 60);
    private static final Color COLOR_NET = new Color(120, 120, 120);
    private static final int GOAL_DEPTH = (int) (CELL_SIZE * 0.55);

    private static final String TEAM_HOME = "HOME";
    private static final String TEAM_AWAY = "AWAY";
    private static final Color COLOR_HOME = Color.BLUE;
    private static final Color COLOR_AWAY = Color.RED;
    private static final Color COLOR_GOALKEEPER = Color.YELLOW;
    private static final Color COLOR_CARRIER_RING = new Color(255, 140, 0);

    // --- UI / interakcija ---
    private static final int CONTROLS_WIDTH = 300;
    private static final int PLAYER_SELECT_RADIUS = 40;
    // Veličina "razmicanja" igrača koji dele isto polje — nasloženi kao karte
    private static final int FAN_STEP_X = 15;
    private static final int FAN_STEP_Y = 12;
    private static final int AUTO_RUN_DELAY_MS = 2000;
    private static final int MAX_ACTION_LOG_LINES = 6;
    private static final int BALL_TRAIL_POINTS = 60;   // koliko tacaka traga lopte crtamo
    private static final long BALL_TRAIL_MS = 2500;    // trail se skloni posle ~2.5 sekunde
    private static final int ANIMATION_DELAY_MS = 50;  // sporija ANIMACIJA: sim-tick na svakih 50ms (20fps)
    private static final int GOAL_CELEBRATION_MS = 5000; // pauza (proslava) pre reseta nakon gola

    /** Svi igraci. Renderer samo cita njihovo stanje — ne poseduje pozicije. */
    private final List<Player> players = createPlayers();

    /** Lopta: renderer cita poziciju, SimulationEngine menja stanje. */
    private final Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));

    /** Simulacioni engin (prvi simulacioni demo: PASS/CARRY/SHOT). */
    private final SimulationEngine simulation = new SimulationEngine(players, ball, new TacticsRules());

    // --- UI komponente ---
    private DrawingPanel drawingPanel;
    private final JLabel statusLabel = new JLabel(" ");
    private JButton runAllButton;
    private JTextArea playerLogArea;
    private JTextArea actionLogArea;
    private final ArrayDeque<String> actionLogMessages = new ArrayDeque<>();

    // --- interakcija / auto-run ---
    private Player selectedPlayer;
    private boolean autoRunActive;
    private boolean stopRequested;
    private boolean prevInProgress;
    private long lastActionCompletionTime;
    private long celebrationEndMs; // kraj proslave gola (0 = nema proslave)
    private javax.swing.Timer animationTimer;
    private javax.swing.Timer autoRunTimer;

    /** Trag lopte: [x, y, vremeMs] u pikselima — čisto vizuelni, briše se na reset. */
    private final ArrayDeque<long[]> ballTrail = new ArrayDeque<>();

    public TacticalGridDemo() {
        setTitle("Tactical Grid Demo - 9x8 grid (pitch 7x6)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        drawingPanel = new DrawingPanel();
        drawingPanel.setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
        drawingPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectPlayerAt(e.getX(), e.getY());
            }
        });

        JButton nextBtn = new JButton("Run Next Action");
        nextBtn.addActionListener(e -> runNextAction());

        runAllButton = new JButton("Run All Actions");
        runAllButton.addActionListener(e -> toggleRunAll());

        JButton resetBtn = new JButton("Reset State");
        resetBtn.addActionListener(e -> resetState());

        // Desni kontrolni panel: opaka pozadina + fiksna širina, da NIKADA ne
        // prekrije teren — prostor se dodaje s desne strane (proširen prozor).
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        controls.setBackground(new Color(235, 235, 235));
        controls.setPreferredSize(new Dimension(CONTROLS_WIDTH, PANEL_HEIGHT));

        controls.add(nextBtn);
        controls.add(Box.createVerticalStrut(8));
        controls.add(runAllButton);
        controls.add(Box.createVerticalStrut(8));
        controls.add(resetBtn);
        controls.add(Box.createVerticalStrut(16));

        controls.add(sectionLabel("Player Log"));
        playerLogArea = logArea(24);
        playerLogArea.setText("Click on a player to inspect.");
        controls.add(new JScrollPane(playerLogArea));
        controls.add(Box.createVerticalStrut(14));

        controls.add(sectionLabel("Action Log"));
        actionLogArea = logArea(MAX_ACTION_LOG_LINES);
        actionLogArea.setText("Waiting...");
        controls.add(new JScrollPane(actionLogArea));
        controls.add(Box.createVerticalStrut(14));

        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setText(simulationStatus());
        controls.add(statusLabel);

        JPanel content = new JPanel(new BorderLayout());
        // Skrol samo ako prozor nije dovoljno velik da prikaze ceo grid.
        content.add(new JScrollPane(drawingPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
        content.add(controls, BorderLayout.EAST);

        setContentPane(content);
        pack();
        setLocationRelativeTo(null);

        startAnimationTimer();
    }

    /**
     * Uvek-aktivni timer za ANIMACIJU: svakih {@link #ANIMATION_DELAY_MS} ms
     * pokrece jedan sim-tick ({@link SimulationEngine#advance() advance()}),
     * odvodi poruke u Action Log i osvezava status. Sim logika se ne menja —
     * samo je prikaz usporen (50ms po tick-u umesto 16ms).
     */
    private void startAnimationTimer() {
        animationTimer = new javax.swing.Timer(ANIMATION_DELAY_MS, e -> {
            simulation.advance();
            handleCelebrationTiming();
            trackBallTrail();
            drainActionLog();
            refreshPlayerLog();
            statusLabel.setText(simulationStatus());
            drawingPanel.repaint();
        });
        animationTimer.start();
    }

    /**
     * Proslava gola: cim se gol desi (engine je zamrznut) krece 5-sekundni
     * tajmer; kad istekne — reset na pocetno stanje i nastavak.
     */
    private void handleCelebrationTiming() {
        if (!simulation.isCelebrating()) {
            celebrationEndMs = 0;
            return;
        }
        if (celebrationEndMs == 0) {
            celebrationEndMs = System.currentTimeMillis() + GOAL_CELEBRATION_MS;
            logAction("GOAL! Celebration...");
        }
        if (System.currentTimeMillis() >= celebrationEndMs) {
            simulation.reset();
            celebrationEndMs = 0;
            logAction("Reset after goal");
        }
    }

    /**
     * Beleži tačke traga lopte u pikselima sa vremenom. Tačka se dodaje samo
     * kad se lopta stvarno pomera (preskače identične pozicije), a tačke
     * starije od ~2.5s se uklanjaju — trail nestaje i kad lopta miruje.
     */
    private void trackBallTrail() {
        long now = System.currentTimeMillis();
        while (!ballTrail.isEmpty() && now - ballTrail.peekFirst()[2] > BALL_TRAIL_MS) {
            ballTrail.removeFirst();
        }
        Position p = ball.getPosition();
        int cx = cellCenterX(p.getColumn());
        int cy = cellCenterY(p.getRow());
        long[] last = ballTrail.peekLast();
        if (last != null && Math.hypot(cx - last[0], cy - last[1]) < 2.5) {
            return;
        }
        ballTrail.addLast(new long[]{cx, cy, now});
        while (ballTrail.size() > BALL_TRAIL_POINTS) {
            ballTrail.removeFirst();
        }
    }

    private String simulationStatus() {
        if (simulation.isCelebrating()) {
            long left = Math.max(0, celebrationEndMs - System.currentTimeMillis());
            return "GOAL! Reset in " + ((left + 999) / 1000) + "s";
        }
        return "round " + simulation.getRound()
            + " | goals " + simulation.getGoalCount()
            + " | shots " + simulation.getShotCount()
            + " | " + simulation.getStatus();
    }

    /**
     * "Run Next Action" — izvrsava TAČNO JEDNU akciju i onda čeka sledeći klik.
     * Ako je akcija već u toku, klik se ignorise (samo poruka "Waiting...").
     */
    private void runNextAction() {
        if (simulation.isActionInProgress()) {
            logAction("Waiting... (" + simulation.getStatus() + ")");
            return;
        }
        simulation.step();
        drainActionLog();
        statusLabel.setText(simulationStatus());
    }

    /**
     * "Run All Actions" / "Stop Actions":
     * - Start: krece odmah prva akcija, posle SVAKE završene akcije čeka 2 sekunde.
     * - Stop: trenutna akcija se završava, posle toga se vise ne pokrece nova.
     *   STOP ne resetuje ništa.
     */
    private void toggleRunAll() {
        if (autoRunActive) {
            stopRequested = true;
            logAction("Stop requested...");
        } else {
            autoRunActive = true;
            stopRequested = false;
            prevInProgress = simulation.isActionInProgress();
            lastActionCompletionTime = 0;
            runAllButton.setText("Stop Actions");
            logAction("Automatic execution started");
            autoRunTimer = new javax.swing.Timer(100, e -> autoRunTick());
            autoRunTimer.start();
        }
    }

    private void autoRunTick() {
        boolean inProgress = simulation.isActionInProgress();
        if (inProgress) {
            prevInProgress = true;
            return;
        }
        if (prevInProgress) {
            // Akcija se upravo završila — 2s pauza se broji od OVOG trenutka.
            prevInProgress = false;
            lastActionCompletionTime = System.currentTimeMillis();
        }
        if (stopRequested) {
            stopAutoRun();
            return;
        }
        if (lastActionCompletionTime == 0) {
            // Prva akcija nakon starta krece odmah.
            lastActionCompletionTime = System.currentTimeMillis();
            runNextAction();
            return;
        }
        if (System.currentTimeMillis() - lastActionCompletionTime >= AUTO_RUN_DELAY_MS) {
            lastActionCompletionTime = System.currentTimeMillis();
            runNextAction();
        }
    }

    private void stopAutoRun() {
        if (autoRunTimer != null) {
            autoRunTimer.stop();
        }
        autoRunActive = false;
        stopRequested = false;
        runAllButton.setText("Run All Actions");
        logAction("Automatic execution stopped");
    }

    /**
     * "Reset State": zaustavlja auto-run, vraca sve igrace i loptu na pocetno
     * stanje i cisti logove. Ne zatvara prozor.
     */
    private void resetState() {
        if (autoRunActive) {
            stopAutoRun();
        }
        simulation.reset();
        selectedPlayer = null;
        celebrationEndMs = 0;
        ballTrail.clear();
        refreshPlayerLog();
        statusLabel.setText(simulationStatus());
        logAction("Reset state");
        drawingPanel.repaint();
    }

    /**
     * Selektuje igraca na klik (po TRENUTNOJ render poziciji). SAMO HOME igraci
     * su selektabilni (AWAY ne ulaze u Player Log). Ako je na istom polju vise
     * igraca, svaki klik prelazi na sledeceg (ciklicno), tako da se moze
     * izabrati bilo koji od njih. Klik menja samo selekciju + log — NIKADA
     * stanje igraca.
     */
    private void selectPlayerAt(int x, int y) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : players) {
            if (!TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            int cx = cellCenterX(p.getPosition().getColumn());
            int cy = cellCenterY(p.getPosition().getRow());
            if (Math.hypot(x - cx, y - cy) <= PLAYER_SELECT_RADIUS) {
                candidates.add(p);
            }
        }

        if (candidates.isEmpty()) {
            selectedPlayer = null;
        } else if (selectedPlayer != null && candidates.contains(selectedPlayer)) {
            // Prebaci se na sledeceg koji deli isto polje (ciklicno).
            int idx = candidates.indexOf(selectedPlayer);
            selectedPlayer = candidates.get((idx + 1) % candidates.size());
        } else {
            candidates.sort(Comparator.comparingDouble(p -> {
                int cx = cellCenterX(p.getPosition().getColumn());
                int cy = cellCenterY(p.getPosition().getRow());
                double dx = x - cx;
                double dy = y - cy;
                return dx * dx + dy * dy;
            }));
            selectedPlayer = candidates.get(0);
        }
        refreshPlayerLog();
        drawingPanel.repaint();
    }

    /** Player Log — cita stanje direktno iz Player objekta i Ball stanja. */
    private void refreshPlayerLog() {
        if (playerLogArea == null) {
            return;
        }
        if (selectedPlayer == null) {
            playerLogArea.setText("Click on a player to inspect.");
            return;
        }
        Player p = selectedPlayer;
        Position initial = simulation.getRoundStartPosition(p);
        Position tacticalDesired = simulation.getTacticalDesiredPosition(p);
        Position moveTarget = simulation.getDesiredPosition(p);
        boolean roundInProgress = !simulation.isRoundComplete();
        Position end = roundInProgress ? p.getPosition() : simulation.getRoundEndPosition(p);
        double cellsMoved = simulation.getCellsMoved(p);
        Position ballStart = simulation.getRoundStartBallPosition();
        Position ballRule = simulation.getTacticalBallPosition();
        Position ballEnd = simulation.getRoundEndBallPosition();
        String text =
            "Player: " + p.getLabel() + "\n"
            + "Team: " + p.getTeam() + "\n"
            + "Role: " + p.getRole() + "\n"
            + "\n"
            + "Initial Position (turn start):\n"
            + "  row = " + fmt(initial.getRow()) + "\n"
            + "  column = " + fmt(initial.getColumn()) + "\n"
            + "\n"
            + "Tactical Desired Cell (editor rule):\n"
            + "  row = " + fmt(tacticalDesired.getRow()) + "\n"
            + "  column = " + fmt(tacticalDesired.getColumn()) + "\n"
            + "\n"
            + "Movement target (this turn, max 1 cell):\n"
            + "  row = " + fmt(moveTarget.getRow()) + "\n"
            + "  column = " + fmt(moveTarget.getColumn()) + "\n"
            + "\n"
            + "Final Position (turn " + (roundInProgress ? "in progress" : "end") + "):\n"
            + "  row = " + fmt(end.getRow()) + "\n"
            + "  column = " + fmt(end.getColumn()) + "\n"
            + "\n"
            + "Cells moved in turn: " + fmt(cellsMoved) + " (max 1)\n"
            + "\n"
            + "Ball Position (turn start):\n"
            + "  row = " + fmt(ballStart.getRow()) + "\n"
            + "  column = " + fmt(ballStart.getColumn()) + "\n"
            + "\n"
            + "Ball Position (used for tactical rules):\n"
            + "  row = " + fmt(ballRule.getRow()) + "\n"
            + "  column = " + fmt(ballRule.getColumn()) + "\n"
            + "\n"
            + "Ball Position (turn end):\n"
            + "  row = " + fmt(ballEnd.getRow()) + "\n"
            + "  column = " + fmt(ballEnd.getColumn());
        // setText() kida selekciju/markaciju svaki put — zato ga pozivamo samo
        // kad se sadrzaj STVARNO promenio (kad je igrac miran, tekst je stabilan
        // pa se selekcija i kopiranje zadrzavaju).
        if (!text.equals(playerLogArea.getText())) {
            playerLogArea.setText(text);
        }
    }

    /** Action Log — poruke iz simulacije + UI poruke, ogranicen broj redova. */
    private void logAction(String message) {
        actionLogMessages.addLast(message);
        while (actionLogMessages.size() > MAX_ACTION_LOG_LINES) {
            actionLogMessages.removeFirst();
        }
        if (actionLogArea != null) {
            actionLogArea.setText(String.join("\n", actionLogMessages));
            actionLogArea.setCaretPosition(actionLogArea.getDocument().getLength());
        }
    }

    private void drainActionLog() {
        for (String msg : simulation.getAndDrainMessages()) {
            logAction(msg);
        }
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Arial", Font.BOLD, 12));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JTextArea logArea(int rows) {
        JTextArea area = new JTextArea(rows, 24);
        area.setEditable(false);
        area.setFocusable(true);
        area.setFont(new Font("Monospaced", Font.PLAIN, 11));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Kopiranje: eksplicitno vezemo Cmd+C (macOS) i Ctrl+C, plus desni-klik -> Copy.
        for (KeyStroke ks : new KeyStroke[]{
                KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK)}) {
            area.getInputMap().put(ks, "copy");
        }
        JPopupMenu copyMenu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> area.copy());
        copyMenu.add(copyItem);
        area.setComponentPopupMenu(copyMenu);
        return area;
    }

    /** Formatira double: 2 -> "2", 3.5 -> "3.5", 3.075 -> "3.075". */
    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-9) {
            return String.valueOf((long) Math.round(v));
        }
        String s = String.format("%.3f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Provera da li je polje (row,column) deo terena (redovi 1-7, kolone 1-6).
     */
    private static boolean isPitchCell(int row, int col) {
        return row >= PITCH_MIN_ROW && row <= PITCH_MAX_ROW
            && col >= PITCH_MIN_COL && col <= PITCH_MAX_COL;
    }

    /**
     * Mapiranje model reda na ekranski red. Model red 1 je dole, red 7 gore.
     * Ekran raste na dole, pa se red invertuje: screenRow = (GRID_ROWS - 1) - row.
     */
    private static int screenRow(int modelRow) {
        return (GRID_ROWS - 1) - modelRow;
    }

    /**
     * Mapiranje model kolone na ekransku kolonu. Kolone rastu s leva na desno.
     */
    private static int screenCol(int modelCol) {
        return modelCol;
    }

    /** Centar celije (row, col) u pikselima po X osi. */
    private static int cellCenterX(double col) {
        return (int) Math.round((col + 0.5) * CELL_SIZE);
    }

    /** Centar celije (row, col) u pikselima po Y osi. */
    private static int cellCenterY(double row) {
        return (int) Math.round(((GRID_ROWS - 1 - row) + 0.5) * CELL_SIZE);
    }

    /**
     * Crtanje gola: dve stative, precka i mreza.
     *
     * @param mouthCenterX horizontalna sredina gola (u pikselima)
     * @param goalLineY    linija gola (gol-linija terena, u pikselima)
     * @param depth        dubina gola u pikselima
     * @param pointsUp     true ako gol "gleda" na gore (gostujuci), false ako na dole (domaci)
     */
    private static void drawGoal(Graphics2D g2d, int mouthCenterX, int goalLineY, int depth, boolean pointsUp) {
        int mouthHalf = CELL_SIZE / 2;
        int leftPostX = mouthCenterX - mouthHalf;
        int rightPostX = mouthCenterX + mouthHalf;
        int farY = pointsUp ? goalLineY - depth : goalLineY + depth;

        // Mreza (iscrtava se prva, iza stativa)
        g2d.setStroke(new BasicStroke(1));
        g2d.setColor(COLOR_NET);
        int netSegments = 6;
        for (int i = 0; i <= netSegments; i++) {
            int x = leftPostX + (rightPostX - leftPostX) * i / netSegments;
            g2d.drawLine(x, goalLineY, x, farY);
        }
        for (int j = 1; j <= 2; j++) {
            int y = goalLineY + (farY - goalLineY) * j / 3;
            g2d.drawLine(leftPostX, y, rightPostX, y);
        }

        // Stative i precka
        g2d.setStroke(new BasicStroke(4));
        g2d.setColor(COLOR_GRID_LINE);
        g2d.drawLine(leftPostX, goalLineY, leftPostX, farY);
        g2d.drawLine(rightPostX, goalLineY, rightPostX, farY);
        g2d.drawLine(leftPostX, farY, rightPostX, farY);
    }

    /**
     * Crtanje fudbalske lopte: beli krug, crni obrub i crni "pentagoni".
     */
    private static void drawBall(Graphics2D g2d, int centerX, int centerY) {
        int radius = 24; // malko manja od igraca (igrac r=30) — ali uvek on top
        g2d.setColor(Color.WHITE);
        g2d.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

        int pentagonSize = 8;
        g2d.fillOval(centerX - pentagonSize, centerY - pentagonSize, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX - 16, centerY - 3, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX + 8, centerY - 3, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX - 8, centerY + 11, pentagonSize * 2, pentagonSize * 2);
    }

    /**
     * Crtanje igraca: iscrtava igraca na njegovoj trenutnoj poziciji.
     * Pozicija, boja i oznaka se cita iz {@link Player} stanja.
     * Opcioni (dx, dy) pomeraj u pikselima koristi se kod naslaganih igraca
     * (lepeza karata), inace je (0, 0).
     */
    private static void drawPlayer(Graphics2D g2d, Player player, int dx, int dy) {
        Position position = player.getPosition();
        int centerX = cellCenterX(position.getColumn()) + dx;
        int centerY = cellCenterY(position.getRow()) + dy;
        drawPlayerCircle(g2d, centerX, centerY, player.getColor(), player.getLabel());
    }

    /**
     * Crtanje kruga igraca: krug u datoj boji sa oznakom pozicije u centru.
     */
    private static void drawPlayerCircle(Graphics2D g2d, int centerX, int centerY, Color color, String label) {
        int radius = 30;
        g2d.setColor(color);
        g2d.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

        g2d.setFont(new Font("Arial", Font.BOLD, 17));
        int textWidth = g2d.getFontMetrics().stringWidth(label);
        g2d.drawString(label, centerX - textWidth / 2, centerY + 6);
    }

    /**
     * Narandzasti prsten oko NOSIOCA LOPTE — prati nosioca kroz turn (na
     * pocetku turna je na trenutnom nosiocu, a kad se nosilac promeni — na
     * novom). Cisto vizuelni highlight, ne menja stanje igraca.
     */
    private static void drawCarrierRing(Graphics2D g2d, Player carrier, int dx, int dy) {
        Position position = carrier.getPosition();
        int centerX = cellCenterX(position.getColumn()) + dx;
        int centerY = cellCenterY(position.getRow()) + dy;
        g2d.setColor(COLOR_CARRIER_RING);
        g2d.setStroke(new BasicStroke(5));
        g2d.drawOval(centerX - 42, centerY - 42, 84, 84);
        g2d.setColor(new Color(255, 255, 255, 140));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - 34, centerY - 34, 68, 68);
    }

    /**
     * Mali trougao-pokazivac iznad selektovanog igraca — jasno se razlikuje
     * od prstena nosioca. UI-only prikaz (ne menja stanje igraca).
     */
    private static void drawSelectionMarker(Graphics2D g2d, Player player, int dx, int dy) {
        Position position = player.getPosition();
        int cx = cellCenterX(position.getColumn()) + dx;
        int cy = cellCenterY(position.getRow()) + dy;
        int tipY = cy - 38;
        int baseY = tipY - 14;
        int[] xs = {cx, cx - 9, cx + 9};
        int[] ys = {tipY, baseY, baseY};
        g2d.setColor(Color.WHITE);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawPolygon(xs, ys, 3);
        g2d.setColor(Color.BLACK);
        g2d.fillPolygon(xs, ys, 3);
    }

    /**
     * Crtanje grupe igraca koji dele isto polje. Ako je na polju samo jedan
     * igrac — crta se normalno. Ako ih je vise — crtaju se kao naslozene
     * karte: svaka sledeca karta je pomerena za {@link #FAN_STEP_X}/
     * {@link #FAN_STEP_Y} piksela u donji-desni smer, pa se svaka vidi ivicom.
     * Selektovani igrac (pa onda nosilac lopte) ide na VECNA (poslednja)
     * karta — on je najvidljiviji.
     */
    private static void drawStack(Graphics2D g2d, List<Player> stack, Player carrier, Player selected) {
        int n = stack.size();
        if (n == 1) {
            Player p = stack.get(0);
            if (p == carrier) {
                drawCarrierRing(g2d, p, 0, 0);
            }
            drawPlayer(g2d, p, 0, 0);
            if (p == selected) {
                drawSelectionMarker(g2d, p, 0, 0);
            }
            return;
        }

        List<Player> ordered = new ArrayList<>(stack);
        ordered.sort(Comparator.comparingInt(p -> stackPriority(p, selected, carrier)));
        for (int i = 0; i < n; i++) {
            Player p = ordered.get(i);
            int dx = (i - (n - 1)) * FAN_STEP_X;
            int dy = (i - (n - 1)) * FAN_STEP_Y;
            if (p == carrier) {
                drawCarrierRing(g2d, p, dx, dy);
            }
            drawPlayer(g2d, p, dx, dy);
            if (p == selected) {
                drawSelectionMarker(g2d, p, dx, dy);
            }
        }
    }

    /**
     * Prioritet igraca unutar naslagane grupe: selektovani igrac je na
     * "vecem" (vidljivijem) mestu, zatim nosilac lopte, pa ostali.
     */
    private static int stackPriority(Player player, Player selected, Player carrier) {
        if (player == selected) {
            return 2;
        }
        if (player == carrier) {
            return 1;
        }
        return 0;
    }

    /** Kljuc polja (red, kolona) po kome grupisemo igrace za naslagano crtanje. */
    private record CellKey(int row, int col) {}

    /**
     * Validacija koordinatnog sistema. Vraća true ako su sve provere prošle.
     */
    public static boolean validateGrid() {
        boolean ok = true;
        StringBuilder report = new StringBuilder();
        report.append("=== TacticalGridDemo validation ===\n");

        int totalCells = GRID_ROWS * GRID_COLS;
        int pitchCells = 0;
        int outsideCells = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                if (isPitchCell(row, col)) {
                    pitchCells++;
                } else {
                    outsideCells++;
                }
            }
        }

        report.append("grid rows   = ").append(GRID_ROWS).append(" (expect 9)\n");
        report.append("grid cols   = ").append(GRID_COLS).append(" (expect 8)\n");
        report.append("total cells = ").append(totalCells).append(" (expect 72)\n");
        report.append("pitch cells = ").append(pitchCells).append(" (expect 42 = 7x6)\n");
        report.append("outside     = ").append(outsideCells).append(" (expect 30)\n");

        ok &= check(report, GRID_ROWS == 9, "exactly 9 rows");
        ok &= check(report, GRID_COLS == 8, "exactly 8 columns");
        ok &= check(report, pitchCells == 42, "exactly 42 pitch cells");
        ok &= check(report, outsideCells == 30, "exactly 30 outside cells");

        int[][] corners = {{1, 1}, {1, 6}, {7, 1}, {7, 6}};
        for (int[] c : corners) {
            ok &= check(report, isPitchCell(c[0], c[1]),
                "corner " + c[0] + "," + c[1] + " is pitch");
        }

        boolean borderOutside = true;
        for (int col = 0; col < GRID_COLS; col++) {
            borderOutside &= !isPitchCell(0, col);
            borderOutside &= !isPitchCell(8, col);
        }
        for (int row = 0; row < GRID_ROWS; row++) {
            borderOutside &= !isPitchCell(row, 0);
            borderOutside &= !isPitchCell(row, 7);
        }
        ok &= check(report, borderOutside, "row 0, row 8, col 0, col 7 are all outside");

        ok &= check(report, screenRow(1) == 7, "row 1 maps to bottom screen row (7)");
        ok &= check(report, screenRow(7) == 1, "row 7 maps to top screen row (1)");
        ok &= check(report, screenCol(1) == 1 && screenCol(6) == 6,
            "columns increase left to right");

        report.append(ok ? "\nVALIDATION: PASS" : "\nVALIDATION: FAIL");
        System.out.println(report);
        return ok;
    }

    private static boolean check(StringBuilder report, boolean condition, String label) {
        report.append("  [").append(condition ? "OK" : "FAIL").append("] ").append(label).append("\n");
        return condition;
    }

    /**
     * Kreira svih 22 igraca sa njihovim tacnim pozicijama (iste kao ranije
     * hardkodovane u rendereru). Redosled kreiranja = redosled crtanja.
     *
     * alternativePosition se za sada inicijalizuje na istu poziciju kao position
     * (samo podatkovno polje, bez logike).
     */
    public static List<Player> createPlayers() {
        List<Player> players = new ArrayList<>();

        // Golmani (zuti krug): HGK na spoju c1_3/c1_4, AGK na spoju c7_3/c7_4
        players.add(player("HGK", TEAM_HOME, "GK", COLOR_GOALKEEPER, new Position(1, 3.5)));
        players.add(player("AGK", TEAM_AWAY, "GK", COLOR_GOALKEEPER, new Position(7, 3.5)));

        // Odbrana domace ekipe (plavi) u redu 2
        players.add(player("HDL", TEAM_HOME, "DL", COLOR_HOME, new Position(2, 2)));
        players.add(player("HDCL", TEAM_HOME, "DCL", COLOR_HOME, new Position(2, 3)));
        players.add(player("HDCR", TEAM_HOME, "DCR", COLOR_HOME, new Position(2, 4)));
        players.add(player("HDR", TEAM_HOME, "DR", COLOR_HOME, new Position(2, 5)));

        // Odbrana gostujuce ekipe (crveni) u redu 6
        players.add(player("ADL", TEAM_AWAY, "DL", COLOR_AWAY, new Position(6, 2)));
        players.add(player("ADCL", TEAM_AWAY, "DCL", COLOR_AWAY, new Position(6, 3)));
        players.add(player("ADCR", TEAM_AWAY, "DCR", COLOR_AWAY, new Position(6, 4)));
        players.add(player("ADR", TEAM_AWAY, "DR", COLOR_AWAY, new Position(6, 5)));

        // Veznjaci domace ekipe (plavi) u redu 3
        players.add(player("HML", TEAM_HOME, "ML", COLOR_HOME, new Position(3, 1)));
        players.add(player("HCML", TEAM_HOME, "CML", COLOR_HOME, new Position(3, 3)));
        players.add(player("HCMR", TEAM_HOME, "CMR", COLOR_HOME, new Position(3, 4)));
        players.add(player("HMR", TEAM_HOME, "MR", COLOR_HOME, new Position(3, 6)));

        // Veznjaci gostujuce ekipe (crveni) u redu 5
        players.add(player("AML", TEAM_AWAY, "ML", COLOR_AWAY, new Position(5, 1)));
        players.add(player("ACML", TEAM_AWAY, "CML", COLOR_AWAY, new Position(5, 3)));
        players.add(player("ACMR", TEAM_AWAY, "CMR", COLOR_AWAY, new Position(5, 4)));
        players.add(player("AMR", TEAM_AWAY, "MR", COLOR_AWAY, new Position(5, 6)));

        // Napadaci gostujuce ekipe (crveni) na liniji izmedju redova 4 i 5
        players.add(player("ASTL", TEAM_AWAY, "STL", COLOR_AWAY, new Position(4.5, 2)));
        players.add(player("ASTR", TEAM_AWAY, "STR", COLOR_AWAY, new Position(4.5, 5)));

        // Napadaci domace ekipe (plavi): HSTL na centru pored lopte, HSTR na srednjoj liniji u c4_4
        players.add(player("HSTL", TEAM_HOME, "STL", COLOR_HOME, new Position(4, 3.075)));
        players.add(player("HSTR", TEAM_HOME, "STR", COLOR_HOME, new Position(4, 4)));

        return players;
    }

    /**
     * Pomocni faktor za igraca: id = label, alternativePosition = position.
     */
    private static Player player(String label, String team, String role, Color color, Position position) {
        return new Player(label, label, team, role, color, position, position);
    }

    /**
     * Validacija igraca. Vraća true ako su sve provere prošle.
     */
    public static boolean validatePlayers(List<Player> players) {
        boolean ok = true;
        StringBuilder report = new StringBuilder();
        report.append("=== TacticalGridDemo player validation ===\n");

        ok &= check(report, players.size() == 22, "exactly 22 players");

        Map<String, Position> expected = new LinkedHashMap<>();
        expected.put("HGK", new Position(1, 3.5));
        expected.put("AGK", new Position(7, 3.5));
        expected.put("HDL", new Position(2, 2));
        expected.put("HDCL", new Position(2, 3));
        expected.put("HDCR", new Position(2, 4));
        expected.put("HDR", new Position(2, 5));
        expected.put("ADL", new Position(6, 2));
        expected.put("ADCL", new Position(6, 3));
        expected.put("ADCR", new Position(6, 4));
        expected.put("ADR", new Position(6, 5));
        expected.put("HML", new Position(3, 1));
        expected.put("HCML", new Position(3, 3));
        expected.put("HCMR", new Position(3, 4));
        expected.put("HMR", new Position(3, 6));
        expected.put("AML", new Position(5, 1));
        expected.put("ACML", new Position(5, 3));
        expected.put("ACMR", new Position(5, 4));
        expected.put("AMR", new Position(5, 6));
        expected.put("ASTL", new Position(4.5, 2));
        expected.put("ASTR", new Position(4.5, 5));
        expected.put("HSTL", new Position(4, 3.075));
        expected.put("HSTR", new Position(4, 4));

        Set<String> seenLabels = new HashSet<>();
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            seenLabels.add(player.getLabel());

            ok &= check(report, player.getId() != null && !player.getId().isBlank(),
                player.getLabel() + " has id");
            ok &= check(report, player.getLabel() != null && !player.getLabel().isBlank(),
                player.getLabel() + " has label");
            ok &= check(report, player.getTeam() != null, player.getLabel() + " has team");
            ok &= check(report, player.getRole() != null, player.getLabel() + " has role");
            ok &= check(report, player.getColor() != null, player.getLabel() + " has color");
            ok &= check(report, player.getPosition() != null, player.getLabel() + " has position");
            ok &= check(report, player.getAlternativePosition() != null,
                player.getLabel() + " has alternativePosition");

            Position exp = expected.get(player.getLabel());
            ok &= check(report, exp != null, player.getLabel() + " is a known player");
            if (exp != null) {
                ok &= check(report, positionsEqual(exp, player.getPosition()),
                    player.getLabel() + " position matches");
            }

            String expectedTeam = player.getLabel().startsWith("H") ? TEAM_HOME : TEAM_AWAY;
            ok &= check(report, expectedTeam.equals(player.getTeam()),
                player.getLabel() + " team = " + expectedTeam);

            String expectedRole = player.getLabel().substring(1);
            ok &= check(report, expectedRole.equals(player.getRole()),
                player.getLabel() + " role = " + expectedRole);

            Color expectedColor = "GK".equals(expectedRole)
                ? COLOR_GOALKEEPER
                : ("H".equals(player.getLabel().substring(0, 1)) ? COLOR_HOME : COLOR_AWAY);
            ok &= check(report, expectedColor.equals(player.getColor()),
                player.getLabel() + " color = " + expectedColor);
        }

        for (String label : expected.keySet()) {
            ok &= check(report, seenLabels.contains(label), label + " exists in players");
        }

        report.append(ok ? "\nVALIDATION: PASS" : "\nVALIDATION: FAIL");
        System.out.println(report);
        return ok;
    }

    private static boolean positionsEqual(Position a, Position b) {
        return Math.abs(a.getRow() - b.getRow()) < 1e-6
            && Math.abs(a.getColumn() - b.getColumn()) < 1e-6;
    }

    private class DrawingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Boja polja: teren svetlo zeleno, van terena belo
            for (int row = 0; row < GRID_ROWS; row++) {
                for (int col = 0; col < GRID_COLS; col++) {
                    int sx = screenCol(col) * CELL_SIZE;
                    int sy = screenRow(row) * CELL_SIZE;
                    g2d.setColor(isPitchCell(row, col) ? COLOR_PITCH : COLOR_OUTSIDE);
                    g2d.fillRect(sx, sy, CELL_SIZE, CELL_SIZE);
                }
            }

            // Grid linije
            g2d.setColor(COLOR_GRID_LINE);
            for (int col = 0; col <= GRID_COLS; col++) {
                int sx = col * CELL_SIZE;
                g2d.drawLine(sx, 0, sx, PANEL_HEIGHT);
            }
            for (int row = 0; row <= GRID_ROWS; row++) {
                int sy = row * CELL_SIZE;
                g2d.drawLine(0, sy, PANEL_WIDTH, sy);
            }

            // Bolduj spoljne linije terena: oiviči ceo teren (redovi 1-7, kolone 1-6)
            g2d.setColor(COLOR_GRID_LINE);
            g2d.setStroke(new BasicStroke(4));
            int borderX = screenCol(PITCH_MIN_COL) * CELL_SIZE;
            int borderY = screenRow(PITCH_MAX_ROW) * CELL_SIZE;
            int borderWidth = (PITCH_MAX_COL - PITCH_MIN_COL + 1) * CELL_SIZE;
            int borderHeight = (PITCH_MAX_ROW - PITCH_MIN_ROW + 1) * CELL_SIZE;
            g2d.drawRect(borderX, borderY, borderWidth, borderHeight);

            // Linija centra terena: kroz sredinu celija c4_1-c4_6 (ista bordura)
            int centerRow = (PITCH_MIN_ROW + PITCH_MAX_ROW) / 2;
            int centerLineY = screenRow(centerRow) * CELL_SIZE + CELL_SIZE / 2;
            g2d.drawLine(
                screenCol(PITCH_MIN_COL) * CELL_SIZE,
                centerLineY,
                screenCol(PITCH_MAX_COL) * CELL_SIZE + CELL_SIZE,
                centerLineY
            );
            g2d.setStroke(new BasicStroke(1));

            // Trag lopte (starije tacke = manje i providnije; stare >2.5s se ne crtaju)
            long now = System.currentTimeMillis();
            int trailCount = 0;
            for (long[] pt : ballTrail) {
                if (now - pt[2] <= BALL_TRAIL_MS) {
                    trailCount++;
                }
            }
            int trailIndex = 0;
            for (long[] pt : ballTrail) {
                if (now - pt[2] > BALL_TRAIL_MS) {
                    continue;
                }
                trailIndex++;
                double t = (double) trailIndex / trailCount;
                int r = 4 + (int) (10 * t);
                int alpha = 40 + (int) (150 * t);
                g2d.setColor(new Color(255, 255, 255, alpha));
                g2d.fillOval((int) pt[0] - r, (int) pt[1] - r, r * 2, r * 2);
                g2d.setColor(new Color(0, 0, 0, alpha));
                g2d.drawOval((int) pt[0] - r, (int) pt[1] - r, r * 2, r * 2);
            }

            // Golovi: domaci u redu 0 (dole), gostujuci u redu 8 (gore).
            // Gol od polovine celije c*_3 do polovine celije c*_4.
            int goalCenterX = screenCol(3) * CELL_SIZE + CELL_SIZE; // sredina izmedju c*_3 i c*_4
            int homeGoalLineY = screenRow(1) * CELL_SIZE + CELL_SIZE; // donja ivica terena (dno reda 1)
            int awayGoalLineY = screenRow(7) * CELL_SIZE;              // gornja ivica terena (vrh reda 7)
            drawGoal(g2d, goalCenterX, homeGoalLineY, GOAL_DEPTH, false);
            drawGoal(g2d, goalCenterX, awayGoalLineY, GOAL_DEPTH, true);

            // Igraci: renderer samo cita poziciju iz svakog Player objekta.
            // Prsten nosioca prati TRENUTNOG nosioca lopte (na pocetku turna i
            // kad se nosilac promeni — na novom). Selekcija je samo mali trougao.
            // Igraci koji su na ISTOM polju crtaju se kao naslozene karte (lepeza),
            // da se vidi da ih je vise, a ne da se poklapaju jedno preko drugog.
            Player carrier = simulation.getCarrier();
            Map<CellKey, List<Player>> stacks = new LinkedHashMap<>();
            for (Player player : players) {
                Position pos = player.getPosition();
                CellKey key = new CellKey((int) Math.round(pos.getRow()), (int) Math.round(pos.getColumn()));
                stacks.computeIfAbsent(key, k -> new ArrayList<>()).add(player);
            }
            for (List<Player> stack : stacks.values()) {
                drawStack(g2d, stack, carrier, selectedPlayer);
            }

            // Lopta se crta POSLEDNJA — uvek ON TOP, vidljiva i kad je preko igraca.
            Position ballPos = ball.getPosition();
            drawBall(g2d, cellCenterX(ballPos.getColumn()), cellCenterY(ballPos.getRow()));

            if (simulation.isCelebrating()) {
                drawGoalCelebration(g2d);
            }
        }
    }

    /**
     * Proslava gola: pulsirajuci "GOAL!" tekst + svetlosni talasi iz centra
     * terena + broj gola. Crta se ~5s dok engine ceka reset.
     */
    private void drawGoalCelebration(Graphics2D g2d) {
        long now = System.currentTimeMillis();
        double remaining = celebrationEndMs == 0 ? 1.0
            : (double) (celebrationEndMs - now) / GOAL_CELEBRATION_MS;
        double t = Math.max(0.0, Math.min(1.0, remaining)); // 1 -> 0 tokom proslave

        int cx = PANEL_WIDTH / 2;
        int cy = PANEL_HEIGHT / 2;

        // Svetlosni talasi: ekspandirajuci prstenovi koji izbljede.
        for (int i = 0; i < 3; i++) {
            double phase = i / 3.0 + (1.0 - t);
            phase -= Math.floor(phase);
            int r = 40 + (int) (phase * 340);
            int alpha = (int) (220 * (1.0 - phase));
            g2d.setColor(new Color(255, 200, 0, Math.max(0, alpha)));
            g2d.setStroke(new BasicStroke(8));
            g2d.drawOval(cx - r, cy - r, r * 2, r * 2);
        }

        // Pulsirajuci "GOAL!" sa bljeskanjem.
        int fontSize = 90 + (int) (12 * Math.sin(now / 120.0));
        g2d.setFont(new Font("Arial", Font.BOLD, Math.max(40, fontSize)));
        FontMetrics fm = g2d.getFontMetrics();
        String text = "GOAL!";
        int tw = fm.stringWidth(text);
        boolean blink = (now / 300) % 2 == 0;
        g2d.setColor(Color.BLACK);
        g2d.drawString(text, cx - tw / 2 + 4, cy + 4);
        g2d.setColor(blink ? new Color(255, 215, 0) : new Color(0, 200, 0));
        g2d.drawString(text, cx - tw / 2, cy);

        // Podnaslov sa brojem gola.
        g2d.setFont(new Font("Arial", Font.BOLD, 26));
        String sub = "HOME goal number " + simulation.getGoalCount();
        int tw2 = g2d.getFontMetrics().stringWidth(sub);
        g2d.setColor(Color.BLACK);
        g2d.drawString(sub, cx - tw2 / 2 + 2, cy + 52 + 2);
        g2d.setColor(Color.WHITE);
        g2d.drawString(sub, cx - tw2 / 2, cy + 52);
    }

    public static void main(String[] args) {
        boolean valid = validateGrid();
        valid &= validatePlayers(createPlayers());

        boolean validateOnly = args.length > 0 && "--validate-only".equals(args[0]);
        if (validateOnly || GraphicsEnvironment.isHeadless()) {
            System.exit(valid ? 0 : 1);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            TacticalGridDemo demo = new TacticalGridDemo();
            demo.setVisible(true);
        });
    }
}
