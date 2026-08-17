package org.example.footballmanager.demo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DemoUI — ODGOVORNOST: SAV PRIKAZ I INTERAKCIJA (Swing).
 *
 * Sadrzi: prozor, kontrolni panel, dugmad, logove, timere, osvezavanje,
 * korisnicku interakciju (klik na igraca) i SVU renderersku logiku
 * (teren, grid, golovi, igraci, lopta, trag, proslava gola).
 *
 * UI komunicira sa simulacijom ISKLJUCIVO kroz javni API
 * {@link SimulationEngine} — ne zna kako je simulacija sklopljena i ne
 * poseduje simulacionu logiku. Vizuelni izgled je IDENTICAN kao pre refaktora.
 */
public class DemoUI {

    // --- UI / interakcija ---
    private static final int CONTROLS_WIDTH = 300;
    private static final int PLAYER_SELECT_RADIUS = 25;
    // Veličina "razmicanja" igrača koji dele isto polje — nasloženi kao karte
    private static final int FAN_STEP_X = 15;
    private static final int FAN_STEP_Y = 12;
    private static final int MAX_ACTION_LOG_LINES = 50;
    private static final int BALL_TRAIL_POINTS = 60;   // koliko tacaka traga lopte crtamo
    private static final long BALL_TRAIL_MS = 2500;    // trail se skloni posle ~2.5 sekunde
    private static final int ANIMATION_DELAY_MS = 50;  // sporija ANIMACIJA: sim-tick na svakih 50ms (20fps)
    private static final int GOAL_CELEBRATION_MS = 7000; // proslava pre reseta nakon gola

    private final DemoScenario scenario;
    private final SimulationEngine simulation;

    /** Svi igraci. Renderer samo cita njihovo stanje — ne poseduje pozicije. */
    private final List<Player> players;

    /** Lopta: renderer cita poziciju, SimulationEngine menja stanje. */
    private final Ball ball;

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
    private long celebrationEndMs; // kraj proslave gola (0 = nema proslave)
    private javax.swing.Timer animationTimer;
    private javax.swing.Timer autoRunTimer;

    /** Trag lopte: [x, y, vremeMs] u pikselima — čisto vizuelni, briše se na reset. */
    private final ArrayDeque<long[]> ballTrail = new ArrayDeque<>();

    public DemoUI(SimulationEngine simulation) {
        this.scenario = DemoScenario.standard();
        this.simulation = simulation;
        this.players = simulation.getPlayers();
        this.ball = simulation.getBall();

        buildFrame();
        startAnimationTimer();
    }

    private void buildFrame() {
        JFrame frame = new JFrame("Tactical Grid Demo - 9x8 grid (pitch 7x6)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        drawingPanel = new DrawingPanel();
        drawingPanel.setPreferredSize(new Dimension(DemoScenario.PANEL_WIDTH, DemoScenario.PANEL_HEIGHT));
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
        controls.setPreferredSize(new Dimension(CONTROLS_WIDTH, DemoScenario.PANEL_HEIGHT));

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

        frame.setContentPane(content);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
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
        int cx = DemoScenario.cellCenterX(p.getColumn());
        int cy = DemoScenario.cellCenterY(p.getRow());
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
     * - Start: krece odmah prva akcija, a svaka sledeća kreće čim se prethodna
     *   završi — BEZ pauze između akcija. Jedina pauza je proslava gola (~5s),
     *   koju drži sam engine (zamrzavanje tokom proslave).
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
            runAllButton.setText("Stop Actions");
            logAction("Automatic execution started");
            autoRunTimer = new javax.swing.Timer(100, e -> autoRunTick());
            autoRunTimer.start();
        }
    }

    private void autoRunTick() {
        if (stopRequested) {
            stopAutoRun();
            return;
        }
        if (simulation.isActionInProgress()) {
            return; // čekamo da se trenutna akcija završi
        }
        runNextAction();
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
            if (!DemoScenario.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            int cx = DemoScenario.cellCenterX(p.getPosition().getColumn());
            int cy = DemoScenario.cellCenterY(p.getPosition().getRow());
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
                int cx = DemoScenario.cellCenterX(p.getPosition().getColumn());
                int cy = DemoScenario.cellCenterY(p.getPosition().getRow());
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

    /** Action Log — poruke iz simulacije + UI poruke, append-only. */
    private void logAction(String message) {
        actionLogMessages.addLast(message);
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

    // --- rendererska logika ---

    /**
     * Crtanje gola: dve stative, precka i mreza.
     *
     * @param mouthCenterX horizontalna sredina gola (u pikselima)
     * @param goalLineY    linija gola (gol-linija terena, u pikselima)
     * @param depth        dubina gola u pikselima
     * @param pointsUp     true ako gol "gleda" na gore (gostujuci), false ako na dole (domaci)
     */
    private static void drawGoal(Graphics2D g2d, int mouthCenterX, int goalLineY, int depth, boolean pointsUp) {
        int mouthHalf = DemoScenario.CELL_SIZE / 2;
        int leftPostX = mouthCenterX - mouthHalf;
        int rightPostX = mouthCenterX + mouthHalf;
        int farY = pointsUp ? goalLineY - depth : goalLineY + depth;

        // Mreza (iscrtava se prva, iza stativa)
        g2d.setStroke(new BasicStroke(1));
        g2d.setColor(DemoScenario.COLOR_NET);
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
        g2d.setColor(DemoScenario.COLOR_GRID_LINE);
        g2d.drawLine(leftPostX, goalLineY, leftPostX, farY);
        g2d.drawLine(rightPostX, goalLineY, rightPostX, farY);
        g2d.drawLine(leftPostX, farY, rightPostX, farY);
    }

    /**
     * Crtanje fudbalske lopte: beli krug, crni obrub i crni "pentagoni".
     */
    private static void drawBall(Graphics2D g2d, int centerX, int centerY) {
        int radius = 12;
        g2d.setColor(Color.WHITE);
        g2d.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

        int pentagonSize = 4;
        g2d.fillOval(centerX - pentagonSize, centerY - pentagonSize, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX - 8, centerY - 1, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX + 4, centerY - 1, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX - 4, centerY + 6, pentagonSize * 2, pentagonSize * 2);
    }

    /**
     * Crtanje igraca: iscrtava igraca na njegovoj trenutnoj poziciji.
     * Pozicija, boja i oznaka se cita iz {@link Player} stanja.
     * Opcioni (dx, dy) pomeraj u pikselima koristi se kod naslaganih igraca
     * (lepeza karata), inace je (0, 0).
     */
    private static void drawPlayer(Graphics2D g2d, Player player, int dx, int dy) {
        Position position = player.getPosition();
        int centerX = DemoScenario.cellCenterX(position.getColumn()) + dx;
        int centerY = DemoScenario.cellCenterY(position.getRow()) + dy;
        drawPlayerCircle(g2d, centerX, centerY, player.getColor(), player.getLabel());
    }

    /**
     * Crtanje kruga igraca: krug u datoj boji sa oznakom pozicije u centru.
     */
    private static void drawPlayerCircle(Graphics2D g2d, int centerX, int centerY, Color color, String label) {
        int radius = 18;
        g2d.setColor(color);
        g2d.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

        g2d.setFont(new Font("Arial", Font.BOLD, 11));
        int textWidth = g2d.getFontMetrics().stringWidth(label);
        g2d.drawString(label, centerX - textWidth / 2, centerY + 4);
    }

    /**
     * Narandzasti prsten oko NOSIOCA LOPTE — prati nosioca kroz turn (na
     * pocetku turna je na trenutnom nosiocu, a kad se nosilac promeni — na
     * novom). Cisto vizuelni highlight, ne menja stanje igraca.
     */
    private static void drawCarrierRing(Graphics2D g2d, Player carrier, int dx, int dy) {
        Position position = carrier.getPosition();
        int centerX = DemoScenario.cellCenterX(position.getColumn()) + dx;
        int centerY = DemoScenario.cellCenterY(position.getRow()) + dy;
        g2d.setColor(DemoScenario.COLOR_CARRIER_RING);
        g2d.setStroke(new BasicStroke(4));
        g2d.drawOval(centerX - 26, centerY - 26, 52, 52);
        g2d.setColor(new Color(255, 255, 255, 140));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - 22, centerY - 22, 44, 44);
    }

    /**
     * Mali trougao-pokazivac iznad selektovanog igraca — jasno se razlikuje
     * od prstena nosioca. UI-only prikaz (ne menja stanje igraca).
     */
    private static void drawSelectionMarker(Graphics2D g2d, Player player, int dx, int dy) {
        Position position = player.getPosition();
        int cx = DemoScenario.cellCenterX(position.getColumn()) + dx;
        int cy = DemoScenario.cellCenterY(position.getRow()) + dy;
        int tipY = cy - 24;
        int baseY = tipY - 10;
        int[] xs = {cx, cx - 7, cx + 7};
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
     * Proslava gola: pulsirajuci "GOAL!" tekst + svetlosni talasi iz centra
     * terena + broj gola. Crta se ~5s dok engine ceka reset.
     */
    private void drawGoalCelebration(Graphics2D g2d) {
        long now = System.currentTimeMillis();
        double remaining = celebrationEndMs == 0 ? 1.0
            : (double) (celebrationEndMs - now) / GOAL_CELEBRATION_MS;
        double t = Math.max(0.0, Math.min(1.0, remaining)); // 1 -> 0 tokom proslave

        int cx = DemoScenario.PANEL_WIDTH / 2;
        int cy = DemoScenario.PANEL_HEIGHT / 2;

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

    private class DrawingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Boja polja: teren svetlo zeleno, van terena belo
            for (int row = 0; row < DemoScenario.GRID_ROWS; row++) {
                for (int col = 0; col < DemoScenario.GRID_COLS; col++) {
                    int sx = DemoScenario.screenCol(col) * DemoScenario.CELL_SIZE;
                    int sy = DemoScenario.screenRow(row) * DemoScenario.CELL_SIZE;
                    g2d.setColor(DemoScenario.isPitchCell(row, col) ? DemoScenario.COLOR_PITCH : DemoScenario.COLOR_OUTSIDE);
                    g2d.fillRect(sx, sy, DemoScenario.CELL_SIZE, DemoScenario.CELL_SIZE);
                }
            }

            // Grid linije
            g2d.setColor(DemoScenario.COLOR_GRID_LINE);
            for (int col = 0; col <= DemoScenario.GRID_COLS; col++) {
                int sx = col * DemoScenario.CELL_SIZE;
                g2d.drawLine(sx, 0, sx, DemoScenario.PANEL_HEIGHT);
            }
            for (int row = 0; row <= DemoScenario.GRID_ROWS; row++) {
                int sy = row * DemoScenario.CELL_SIZE;
                g2d.drawLine(0, sy, DemoScenario.PANEL_WIDTH, sy);
            }

            // Bolduj spoljne linije terena: oiviči ceo teren (redovi 1-7, kolone 1-6)
            g2d.setColor(DemoScenario.COLOR_GRID_LINE);
            g2d.setStroke(new BasicStroke(4));
            int borderX = DemoScenario.screenCol(DemoScenario.PITCH_MIN_COL) * DemoScenario.CELL_SIZE;
            int borderY = DemoScenario.screenRow(DemoScenario.PITCH_MAX_ROW) * DemoScenario.CELL_SIZE;
            int borderWidth = (DemoScenario.PITCH_MAX_COL - DemoScenario.PITCH_MIN_COL + 1) * DemoScenario.CELL_SIZE;
            int borderHeight = (DemoScenario.PITCH_MAX_ROW - DemoScenario.PITCH_MIN_ROW + 1) * DemoScenario.CELL_SIZE;
            g2d.drawRect(borderX, borderY, borderWidth, borderHeight);

            // Linija centra terena: kroz sredinu celija c4_1-c4_6 (ista bordura)
            int centerRow = (DemoScenario.PITCH_MIN_ROW + DemoScenario.PITCH_MAX_ROW) / 2;
            int centerLineY = DemoScenario.screenRow(centerRow) * DemoScenario.CELL_SIZE + DemoScenario.CELL_SIZE / 2;
            g2d.drawLine(
                DemoScenario.screenCol(DemoScenario.PITCH_MIN_COL) * DemoScenario.CELL_SIZE,
                centerLineY,
                DemoScenario.screenCol(DemoScenario.PITCH_MAX_COL) * DemoScenario.CELL_SIZE + DemoScenario.CELL_SIZE,
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
            int goalCenterX = DemoScenario.screenCol(3) * DemoScenario.CELL_SIZE + DemoScenario.CELL_SIZE; // sredina izmedju c*_3 i c*_4
            int homeGoalLineY = DemoScenario.screenRow(1) * DemoScenario.CELL_SIZE + DemoScenario.CELL_SIZE; // donja ivica terena (dno reda 1)
            int awayGoalLineY = DemoScenario.screenRow(7) * DemoScenario.CELL_SIZE;              // gornja ivica terena (vrh reda 7)
            drawGoal(g2d, goalCenterX, homeGoalLineY, DemoScenario.GOAL_DEPTH, false);
            drawGoal(g2d, goalCenterX, awayGoalLineY, DemoScenario.GOAL_DEPTH, true);

            // Igraci: renderer samo cita poziciju iz svakog Player objekta.
            // Prsten nosioca prati TRENUTNOG nosioca lopte.
            Player carrier = simulation.getCarrier();
            for (Player player : players) {
                if (player == carrier) {
                    drawCarrierRing(g2d, player, 0, 0);
                }
                drawPlayer(g2d, player, 0, 0);
                if (player == selectedPlayer) {
                    drawSelectionMarker(g2d, player, 0, 0);
                }
            }

            // Lopta se crta POSLEDNJA — uvek ON TOP, vidljiva i kad je preko igraca.
            Position ballPos = ball.getPosition();
            drawBall(g2d, DemoScenario.cellCenterX(ballPos.getColumn()), DemoScenario.cellCenterY(ballPos.getRow()));

            if (simulation.isCelebrating()) {
                drawGoalCelebration(g2d);
            }
        }
    }
}
