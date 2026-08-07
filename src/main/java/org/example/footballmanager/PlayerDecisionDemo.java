package org.example.footballmanager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.Collections;

/**
 * PlayerDecisionDemo - Football AI Decision Visualizer
 *
 * This tool visualizes how a football player decides what action to take in a given situation.
 * Think of this tool as a debugger for the AI decision system, not a football simulation.
 *
 * It demonstrates:
 * - Decision scoring for available actions
 * - Playmaker behavior and weighting
 * - Target position selection
 * - Ball action animation (pass, shot, etc.)
 * - Decision rationale inspection
 */
public class PlayerDecisionDemo extends JFrame {

    private static final int GRID_SIZE = 5;
    private static final int CELL_SIZE = 100;
    private static final int PANEL_SIZE = (GRID_SIZE + 1) * CELL_SIZE; // Extra row for goal area
    private static final int COORD_OFFSET = 1; // Coordinates are 1-based (1-5)
    private static final int BALL_RADIUS = 15;
    private static final int PLAYER_RADIUS = 20;
    private static final double ANIMATION_SPEED = 2.1; // Slowed by 30% for visibility
    private static final int GOAL_ROW = 5;
    private static final int GOAL_COL = 3;

    // Ball state - using 1-based coordinates (row, column) -> Point(column, row)
    private Point ballPos = new Point(2, 1);
    private Point ballPixelPos = new Point(0, 0);
    private String ballResult = null; // GOAL, MISS_LEFT, MISS_RIGHT, OUT

    // Players
    private List<Player> homePlayers = new ArrayList<>();
    private List<Player> awayPlayers = new ArrayList<>();

    // Selection
    private Player selectedPlayer = null;

    // Visualization toggles
    private boolean showDecisions = true;
    private boolean showTargetPosition = true;
    private boolean showMovementPaths = true;
    private boolean showActionScores = true;
    private boolean animateMovement = false;

    // Animation
    private javax.swing.Timer animationTimer;
    private boolean animating = false;
    private boolean movementTargetsSet = false;
    private boolean collisionResolutionPhase = false;
    
    // Shot animation state
    private int shotStage = 0; // 0: not shooting, 1: in-flight toward goal, 3: GK reaction/resolve, 4: post-goal animation, 5: post-save/miss
    private Point shotIntermediatePos = null;
    private int shotDelayCounter = 0;
    private static final int GK_REACTION_DELAY = 60; // ~1 second at 16ms
    private static final int GOAL_CELEBRATION_DELAY = 120; // ~2 seconds
    // Reference to shooter while shot animation is in progress
    private Player shotShooter = null;
    // Defender that blocks the shot (if any)
    private Player shotBlocker = null;
    // Single-step animate mode: when true, stop after one action completes
    private boolean singleStepMode = false;

    // UI components
    private JTextArea eventLog;
    private JLabel inspectorLabel;
    private JLabel decisionLabel;
    private JButton testButton;
    
    // Test state
    private int testAnimationCount = 0;
    private boolean runningTest = false;
    private List<TestResult> testResults = new ArrayList<>();
    
    // Test result helper class
    private static class TestResult {
        int actionNumber;
        Map<String, Point> initialPositions;
        Map<String, Point> finalPositions;
        Player initialBallOwner;
        Player finalBallOwner;
        Point finalBallPosition;
        Action action;
        Map<Action, Double> actionScores;
        double decisionScore;
        Point targetPosition;
        Map<String, Point[]> positionChanges;
        List<String[]> duels;
        List<String[]> collisions;
        boolean ballChase;
        List<String> chasingPlayers;
        List<String> specialEvents;
        
        TestResult(int num) {
            actionNumber = num;
            positionChanges = new HashMap<>();
            duels = new ArrayList<>();
            collisions = new ArrayList<>();
            chasingPlayers = new ArrayList<>();
            specialEvents = new ArrayList<>();
        }
    }

    public PlayerDecisionDemo() {
        setTitle("Football AI Decision Visualizer - Decision Debugger");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        initializeScenario();
        updateBallPixelPosition();

        JPanel mainPanel = new JPanel(new BorderLayout());
        DrawingPanel drawingPanel = new DrawingPanel();
        drawingPanel.setPreferredSize(new Dimension(PANEL_SIZE, PANEL_SIZE));
        JPanel controlPanel = createControlPanel();
 
        mainPanel.add(drawingPanel, BorderLayout.CENTER);
        mainPanel.add(controlPanel, BorderLayout.EAST);
        add(mainPanel);
        pack();
        setLocationRelativeTo(null);
        // Increase default window size slightly to accommodate larger decision panel
        setSize(new Dimension(PANEL_SIZE + 380, PANEL_SIZE + 40));

        startAnimation();
    }

    private void initializeScenario() {
        homePlayers.clear();
        awayPlayers.clear();
        Random rand = new Random();

        // Ball and playmaker always start at 1_2 (row 1, column 2)
        Point ballStart = new Point(2, 1);

        // HOME - 3 attackers total, attacking upward from bottom (row 1-2) to top (goal above row 5)
        // Playmaker with ball at 1_2
        Player playmaker = new Player("Home Playmaker", "HOME", Role.PLAYMAKER, new Point(ballStart));
        playmaker.hasBall = true;
        homePlayers.add(playmaker);

        // Second attacker in rows 3-4, ensure not same as playmaker
        Point attacker2Pos;
        do {
            attacker2Pos = new Point(1 + rand.nextInt(5), 3 + rand.nextInt(2));
        } while (attacker2Pos.equals(playmaker.currentPos));
        Player attacker2 = new Player("Home Attacker 2", "HOME", Role.ATTACKER, attacker2Pos);
        homePlayers.add(attacker2);

        // Third attacker at random position, ensure unique
        Point attacker3Pos;
        do {
            attacker3Pos = new Point(1 + rand.nextInt(5), 1 + rand.nextInt(5));
        } while (attacker3Pos.equals(playmaker.currentPos) || attacker3Pos.equals(attacker2Pos));
        Player attacker3 = new Player("Home Attacker 3", "HOME", Role.ATTACKER, attacker3Pos);
        homePlayers.add(attacker3);

        // AWAY - GK at row 5 col 3 (in front of goal) + 2 defenders
        Player gk = new Player("Away Goalkeeper", "AWAY", Role.GOALKEEPER, new Point(GOAL_COL, GOAL_ROW));
        awayPlayers.add(gk);

        // One defender in rows 4-5, ensure not same as gk or any home player
        Point defender1Pos;
        do {
            defender1Pos = new Point(1 + rand.nextInt(5), 4 + rand.nextInt(2));
        } while (defender1Pos.equals(gk.currentPos) || 
                 defender1Pos.equals(playmaker.currentPos) || 
                 defender1Pos.equals(attacker2Pos) ||
                 defender1Pos.equals(attacker3Pos));
        Player defender1 = new Player("Away Defender 1", "AWAY", Role.DEFENDER, defender1Pos);
        awayPlayers.add(defender1);

        // Second defender random (rows 1-5, columns 1-5), ensure unique position
        Point defender2Pos;
        do {
            defender2Pos = new Point(1 + rand.nextInt(5), 1 + rand.nextInt(5));
        } while (defender2Pos.equals(gk.currentPos) || 
                 defender2Pos.equals(defender1Pos) || 
                 defender2Pos.equals(playmaker.currentPos) || 
                 defender2Pos.equals(attacker2Pos) ||
                 defender2Pos.equals(attacker3Pos));
        Player defender2 = new Player("Away Defender 2", "AWAY", Role.DEFENDER, defender2Pos);
        awayPlayers.add(defender2);

        for (Player p : homePlayers) p.updatePixelPosition();
        for (Player p : awayPlayers) p.updatePixelPosition();

        evaluateAllDecisions();
    }

    private Point randomSupportPosition() {
        Random rand = new Random();
        int x = 1 + rand.nextInt(5);
        int y = 1 + rand.nextInt(5);
        return new Point(x, y);
    }

    private Point randomDefenderPosition() {
        Random rand = new Random();
        return new Point(1 + rand.nextInt(5), 1 + rand.nextInt(5));
    }

    private Point randomAttackingPosition() {
        return new Point(4, 2);
    }

    // ===================== DECISION ENGINE =====================

    private void evaluateAllDecisions() {
        // Reset shot state when new decisions are evaluated
        // Ball Carrier decides first; everyone else waits and then decides knowing the carrier's choice
        ballResult = null;
        Player carrier = findBallOwner();
        Random rand = new Random();

        if (carrier != null) {
            // 1) Ball carrier decides first
            carrier.evaluateDecisions(this);
            Action carrierAction = carrier.decision;
            Point carrierTarget = carrier.targetPosition != null ? new Point(carrier.targetPosition) : null;

            // Clear decisions for everyone else
            for (Player p : getAllPlayers()) {
                if (p != carrier) p.clearDecision();
            }

            // 2) Attacking teammates decide/move knowing the carrier action
            for (Player p : getAllPlayers()) {
                if (p == carrier) continue;
                if (p.team.equals(carrier.team) && p.role != Role.GOALKEEPER) {
                    // If pass to exact cell -> stay where you are
                    if ((carrierAction == Action.PASS_SHORT || carrierAction == Action.PASS_LONG || carrierAction == Action.THROUGH_BALL) && carrierTarget != null) {
                        if (p.currentPos.equals(carrierTarget)) {
                            p.movementTarget = new Point(p.currentPos);
                            continue;
                        }
                        // If target is neighboring cell -> move to that cell
                        if (isAdjacentTo(p.currentPos, carrierTarget)) {
                            p.movementTarget = new Point(carrierTarget);
                            continue;
                        }
                    }

                    // Otherwise move exactly one random neighbouring cell
                    p.movementTarget = getRandomAdjacentPosition(p.currentPos, rand);
                }
            }

            // 3) Defenders decide after attackers
            for (Player p : getAllPlayers()) {
                if (p.team.equals(carrier.team)) continue; // skip attackers/team-mates
                if (p.role == Role.GOALKEEPER) continue; // GK never moves here

                // If adjacent to ball carrier, move into that cell to initiate duel
                if (isAdjacentTo(p.currentPos, carrier.currentPos)) {
                    p.movementTarget = new Point(carrier.currentPos);
                } else {
                    // move exactly one random neighbouring cell
                    p.movementTarget = getRandomAdjacentPosition(p.currentPos, rand);
                }
            }

            // Note: goalkeepers do not move in this decision phase
        } else {
            // No ball carrier (loose ball) - simple fallback: all players move one random cell (except GK)
            for (Player p : getAllPlayers()) {
                p.clearDecision();
                if (p.role != Role.GOALKEEPER) p.movementTarget = getRandomAdjacentPosition(p.currentPos, rand);
            }
        }
    }

    private List<Player> getAllPlayers() {
        List<Player> all = new ArrayList<>(homePlayers.size() + awayPlayers.size());
        all.addAll(homePlayers);
        all.addAll(awayPlayers);
        return all;
    }

    private List<Player> getOpponents(Player player) {
        List<Player> opponents = new ArrayList<>();
        for (Player p : getAllPlayers()) {
            if (!p.team.equals(player.team)) opponents.add(p);
        }
        return opponents;
    }

    private List<Player> getTeammates(Player player) {
        List<Player> teammates = new ArrayList<>();
        for (Player p : getAllPlayers()) {
            if (p.team.equals(player.team) && p != player) teammates.add(p);
        }
        return teammates;
    }

    private Player findClosestDefender(Player player) {
        List<Player> opponents = getOpponents(player);
        Player closest = null;
        double best = Double.MAX_VALUE;
        for (Player opp : opponents) {
            double dist = distance(player.currentPos, opp.currentPos);
            if (dist < best) {
                best = dist;
                closest = opp;
            }
        }
        return closest;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private void applyDecision(Player player) {
        if (player.decision == null || player.targetPosition == null) return;

        if (player.role == Role.PLAYMAKER) {
            player.decisionScore += 10;
        }

        // Clamp to valid 1-based grid coordinates
        player.targetPosition.x = Math.max(1, Math.min(GRID_SIZE, player.targetPosition.x));
        player.targetPosition.y = Math.max(1, Math.min(GRID_SIZE, player.targetPosition.y));
    }

    // ===================== UI BUILDER =====================

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(380, PANEL_SIZE));

        JPanel toggles = createTogglePanel();
        JPanel buttons = createButtonPanel();
        JPanel inspector = createPlayerInspector();
        JPanel decisionInspector = createDecisionInspector();
        JPanel log = createLogPanel();

        JPanel top = new JPanel(new BorderLayout());
        top.add(toggles, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout());
        center.add(inspector, BorderLayout.NORTH);
        center.add(decisionInspector, BorderLayout.CENTER);

        panel.add(top, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        panel.add(log, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTogglePanel() {
        JPanel panel = new JPanel(new GridLayout(0, 1));
        panel.setBorder(BorderFactory.createTitledBorder("Visualization"));

        panel.add(createToggle("Show Decisions", showDecisions, this::setShowDecisions));
        panel.add(createToggle("Show Target Position", showTargetPosition, this::setShowTargetPosition));
        panel.add(createToggle("Show Movement Paths", showMovementPaths, this::setShowMovementPaths));
        panel.add(createToggle("Show Action Scores", showActionScores, this::setShowActionScores));
        // Animate Movement as a single-step button
        animateButton = new JButton("Animate Step");
        animateButton.addActionListener(e -> setAnimateMovement(true));
        panel.add(animateButton);

        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JButton resetBtn = new JButton("Reset Scenario");
        resetBtn.addActionListener(e -> resetScenario());
        resetBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(resetBtn);
        
        panel.add(Box.createVerticalStrut(5));
        
        testButton = new JButton("Run 7-Action Test");
        testButton.addActionListener(e -> runSevenActionTest());
        testButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(testButton);
        
        panel.setPreferredSize(new Dimension(340, 80));
        
        return panel;
    }

    private JButton animateButton;
    
    private void runSevenActionTest() {
        if (runningTest) return;
        
        runningTest = true;
        testAnimationCount = 0;
        testResults.clear();
        
        logEvent("=== STARTING 7-ACTION TEST ===");
        logEvent("Test will run 7 consecutive animations with analysis");
        
        resetScenario();
        
        // Start test loop
        javax.swing.Timer testTimer = new javax.swing.Timer(3500, e -> {
            if (testAnimationCount < 7) {
                testAnimationCount++;
                TestResult result = captureAndAnalyzeAction(testAnimationCount);
                testResults.add(result);
                
                if (testAnimationCount < 7) {
                    // Trigger next animation
                    setAnimateMovement(true);
                } else {
                    // Test complete, generate summary
                    generateTestSummary();
                    runningTest = false;
                    ((javax.swing.Timer)e.getSource()).stop();
                }
            }
        });
        testTimer.setRepeats(true);
        testTimer.start();
        
        // Trigger first animation
        setAnimateMovement(true);
    }
    
    private TestResult captureAndAnalyzeAction(int actionNum) {
        TestResult result = new TestResult(actionNum);
        
        // Capture initial state
        result.initialPositions = captureAllPositions();
        result.initialBallOwner = findBallOwner();
        
        // Get active player decision info
        if (result.initialBallOwner != null) {
            result.action = result.initialBallOwner.decision;
            result.actionScores = new HashMap<>(result.initialBallOwner.actionScores);
            result.decisionScore = result.initialBallOwner.decisionScore;
            result.targetPosition = new Point(result.initialBallOwner.targetPosition);
        }
        
        // Wait for animation to complete (captured in finishAction)
        return result;
    }
    
    private void generateTestSummary() {
        logEvent("=== TEST SUMMARY ===");
        logEvent("Total Actions: " + testResults.size());
        
        int totalDuels = 0;
        int totalCollisions = 0;
        int totalBallChases = 0;
        int totalShots = 0;
        int totalGoals = 0;
        int totalSaves = 0;
        int totalCorners = 0;
        int totalPasses = 0;
        
        Map<Action, Integer> actionCounts = new HashMap<>();
        
        for (TestResult result : testResults) {
            totalDuels += result.duels.size();
            totalCollisions += result.collisions.size();
            if (result.ballChase) totalBallChases++;
            
            if (result.action != null) {
                actionCounts.put(result.action, actionCounts.getOrDefault(result.action, 0) + 1);
            }
            
            for (String event : result.specialEvents) {
                switch (event) {
                    case "SHOT": totalShots++; break;
                    case "GOAL": totalGoals++; break;
                    case "SAVE": totalSaves++; break;
                    case "CORNER": totalCorners++; break;
                    case "PASS": totalPasses++; break;
                }
            }
        }
        
        logEvent("\n--- Event Counts ---");
        logEvent("Total Duels: " + totalDuels);
        logEvent("Total Collisions: " + totalCollisions);
        logEvent("Ball Chases: " + totalBallChases);
        logEvent("Shots: " + totalShots);
        logEvent("Goals: " + totalGoals);
        logEvent("Saves: " + totalSaves);
        logEvent("Corners: " + totalCorners);
        logEvent("Passes: " + totalPasses);
        
        logEvent("\n--- Action Distribution ---");
        for (Map.Entry<Action, Integer> entry : actionCounts.entrySet()) {
            logEvent(entry.getKey() + ": " + entry.getValue());
        }
        
        logEvent("\n--- Issues Detected ---");
        if (totalCollisions > 0) {
            logEvent("WARNING: " + totalCollisions + " collisions detected (should be 0)");
        }
        if (totalDuels > testResults.size() * 2) {
            logEvent("WARNING: High number of duels detected");
        }
        
        logEvent("\n--- Action Details ---");
        for (TestResult result : testResults) {
            logEvent("Action " + result.actionNumber + ":");
            logEvent("  Action: " + result.action);
            logEvent("  Position Changes: " + result.positionChanges.size());
            logEvent("  Duels: " + result.duels.size());
            logEvent("  Collisions: " + result.collisions.size());
            logEvent("  Ball Chase: " + result.ballChase);
            logEvent("  Events: " + String.join(", ", result.specialEvents));
        }
        
        logEvent("=== TEST COMPLETE ===");
    }
    
    private Map<String, Point> captureAllPositions() {
        Map<String, Point> positions = new HashMap<>();
        for (Player p : homePlayers) {
            positions.put(p.name, new Point(p.currentPos));
        }
        for (Player p : awayPlayers) {
            positions.put(p.name, new Point(p.currentPos));
        }
        return positions;
    }
    
    // Modify finishAction to capture test data
    private void finishActionWithTestCapture(Player player) {
        // Find current test result
        TestResult currentResult = null;
        if (!testResults.isEmpty() && runningTest) {
            currentResult = testResults.get(testResults.size() - 1);
        }
        
        if (currentResult != null) {
            // Capture final state
            currentResult.finalPositions = captureAllPositions();
            currentResult.finalBallOwner = findBallOwner();
            currentResult.finalBallPosition = new Point(ballPos);
            
            // Analyze changes
            analyzePositionChanges(currentResult);
            analyzeDuels(currentResult);
            analyzeCollisions(currentResult);
            analyzeBallChase(currentResult);
            analyzeSpecialEvents(currentResult);
        }
    }
    
    private void analyzePositionChanges(TestResult result) {
        for (String playerName : result.initialPositions.keySet()) {
            Point initial = result.initialPositions.get(playerName);
            Point finalPos = result.finalPositions.get(playerName);
            if (!initial.equals(finalPos)) {
                result.positionChanges.put(playerName, new Point[]{initial, finalPos});
            }
        }
    }
    
    private void analyzeDuels(TestResult result) {
        Map<String, Point> allPositions = result.finalPositions;
        List<String> playerNames = new ArrayList<>(allPositions.keySet());
        
        for (int i = 0; i < playerNames.size(); i++) {
            for (int j = i + 1; j < playerNames.size(); j++) {
                String p1 = playerNames.get(i);
                String p2 = playerNames.get(j);
                Point pos1 = allPositions.get(p1);
                Point pos2 = allPositions.get(p2);
                
                if (pos1.equals(pos2) && pos1.equals(result.finalBallPosition)) {
                    if (isOpposingTeam(p1, p2)) {
                        result.duels.add(new String[]{p1, p2, pos1.x + "_" + pos1.y});
                    }
                }
            }
        }
    }
    
    private void analyzeCollisions(TestResult result) {
        Map<String, Point> allPositions = result.finalPositions;
        List<String> playerNames = new ArrayList<>(allPositions.keySet());
        
        for (int i = 0; i < playerNames.size(); i++) {
            for (int j = i + 1; j < playerNames.size(); j++) {
                String p1 = playerNames.get(i);
                String p2 = playerNames.get(j);
                Point pos1 = allPositions.get(p1);
                Point pos2 = allPositions.get(p2);
                
                if (pos1.equals(pos2) && !pos1.equals(result.finalBallPosition)) {
                    result.collisions.add(new String[]{p1, p2, pos1.x + "_" + pos1.y});
                }
            }
        }
    }
    
    private void analyzeBallChase(TestResult result) {
        Point initialBallPos = result.initialBallOwner != null ? 
            result.initialBallOwner.currentPos : result.finalBallPosition;
        Point finalBallPos = result.finalBallPosition;
        
        if (!initialBallPos.equals(finalBallPos)) {
            result.ballChase = true;
            
            for (String playerName : result.positionChanges.keySet()) {
                Point[] change = result.positionChanges.get(playerName);
                Point from = change[0];
                Point to = change[1];
                double distBefore = distance(from, initialBallPos);
                double distAfter = distance(to, finalBallPos);
                
                if (distAfter < distBefore) {
                    result.chasingPlayers.add(playerName);
                }
            }
        }
    }
    
    private void analyzeSpecialEvents(TestResult result) {
        if (result.action == Action.SHOT) {
            result.specialEvents.add("SHOT");
            
            // Check ball position after shot (not final position which might be reset)
            Point shotEndPos = result.finalBallPosition;
            if (shotEndPos.x == 6 && shotEndPos.y == 3) {
                result.specialEvents.add("GOAL");
            } else if (shotEndPos.x == 5 && shotEndPos.y == 3) {
                result.specialEvents.add("SAVE");
            } else if (shotEndPos.x == 5 && shotEndPos.y == 1) {
                result.specialEvents.add("CORNER");
            } else if (shotEndPos.x == 4 && shotEndPos.y == 3) {
                result.specialEvents.add("SHOT_IN_PROGRESS");
            }
        }
        
        if (result.action == Action.PASS_SHORT || 
            result.action == Action.PASS_LONG ||
            result.action == Action.THROUGH_BALL) {
            result.specialEvents.add("PASS");
        }
    }
    
    private boolean isOpposingTeam(String p1, String p2) {
        boolean p1Home = p1.startsWith("Home");
        boolean p2Home = p2.startsWith("Home");
        return p1Home != p2Home;
    }

    private JCheckBox createToggle(String label, boolean selected, java.util.function.Consumer<Boolean> setter) {
        JCheckBox cb = new JCheckBox(label, selected);
        cb.addActionListener(e -> {
            setter.accept(cb.isSelected());
            repaint();
        });
        return cb;
    }

    private JPanel createPlayerInspector() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Player Inspector"));
        inspectorLabel = new JLabel("<html>Select a player to inspect</html>");
        inspectorLabel.setVerticalAlignment(SwingConstants.TOP);
        panel.add(new JScrollPane(inspectorLabel), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(300, 140));
        return panel;
    }

    private JPanel createDecisionInspector() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Decision Inspector"));
        decisionLabel = new JLabel("<html>Evaluate a player with the ball</html>");
        decisionLabel.setVerticalAlignment(SwingConstants.TOP);
        panel.add(new JScrollPane(decisionLabel), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(360, 420));
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Event Log"));
        eventLog = new JTextArea(8, 30);
        eventLog.setEditable(false); // Keep non-editable but allow selection
        eventLog.setFont(new Font("Monospaced", Font.PLAIN, 10));
        eventLog.setSelectionColor(new Color(51, 153, 255));
        JScrollPane scrollPane = new JScrollPane(eventLog);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    // ===================== ANIMATION =====================

    private void startAnimation() {
        movementTargetsSet = false;
        collisionResolutionPhase = false;
        shotStage = 0;
        shotDelayCounter = 0;
        animationTimer = new javax.swing.Timer(16, e -> {
            if (animating) {
                updateAnimation();
            }
            if (selectedPlayer != null) {
                updateInspector(selectedPlayer);
                updateDecisionInspector(selectedPlayer);
            }
            repaint();
        });
        animationTimer.start();
    }

    private void updateAnimation() {
        Player ballOwner = findBallOwner();
        
        // Handle shot animation stages
        // If a shot is in progress, drive the shot animation using the stored shooter reference.
        if (shotStage > 0 && shotShooter != null) {
            // Allow the shot animation to progress even if the shooter no longer "hasBall".
            updateShotAnimation(shotShooter);
            return;
        }
        
        // Check if GK has ball and needs to kick
        if (ballOwner != null && ballOwner.role == Role.GOALKEEPER && ballOwner.targetPosition == null) {
            gkKick();
            animating = false;
            setAnimateMovement(false);
            return;
        }
        
        // If ball is free, race to get it with animation
        if (ballOwner == null) {
            if (!movementTargetsSet) {
                moveClosestPlayerToBall();
                movementTargetsSet = true;
            }
            boolean allPlayersArrived = moveAllPlayers();
            if (allPlayersArrived) {
                animating = false;
                movementTargetsSet = false;
                setAnimateMovement(false);
                evaluateAllDecisions();
            }
            return;
        }
        
        if (ballOwner.targetPosition == null) {
            animating = false;
            setAnimateMovement(false);
            return;
        }

        // Phase 1: Normal action animation
        if (!collisionResolutionPhase) {
            // Set movement targets for idle players on first animation frame
            if (!movementTargetsSet) {
                setMovementTargetsForIdlePlayers(ballOwner);
                movementTargetsSet = true;
            }

            // Move all players toward their movement targets simultaneously
            boolean allPlayersArrived = moveAllPlayers();
            boolean ballArrived = moveBallToTarget(ballOwner);

            if (ballArrived && allPlayersArrived) {
                // Phase 1 complete, start collision resolution phase
                collisionResolutionPhase = true;
                movementTargetsSet = false; // Reset for collision phase
            }
        } else {
            // Phase 2: Collision resolution animation
            if (!movementTargetsSet) {
                resolveOpposingCollisions();
                resolveSameTeamCollisions();
                movementTargetsSet = true;
            }

            boolean allPlayersArrived = moveAllPlayers();
            
            if (allPlayersArrived) {
                finishAction(ballOwner);
            }
        }
    }
    
    private void updateShotAnimation(Player ballOwner) {
        // Log ball position every frame during shot animation
        Point gridPos = pixelToGrid(ballPixelPos);
        logEvent("Shot animation stage: " + shotStage + ", Ball pixel: " + (int)ballPixelPos.x + "_" + (int)ballPixelPos.y + ", Ball grid: " + gridPos.x + "_" + gridPos.y);
        
        boolean arrived = false;
        switch (shotStage) {
            case 1: {
                // In-flight: move ball continuously toward goal center
                Point goalGrid = new Point(GOAL_COL, GOAL_ROW + 1);
                logEvent("Stage 1: Ball in-flight toward goal from " + ballOwner.currentPos.x + "_" + ballOwner.currentPos.y + " to " + goalGrid.x + "_" + goalGrid.y);
                arrived = moveBallToPoint(goalGrid);

                // After moving this frame, check which grid cell the ball is in
                Point currentGrid = pixelToGrid(ballPixelPos);

                // Check for defender blocking along the path
                for (Player d : awayPlayers) {
                    if (d.role == Role.DEFENDER && d.currentPos.equals(currentGrid)) {
                        // Defender is on the ball path - treat as potential block
                        shotBlocker = d;
                        resolveShotOutcome(ballOwner);
                        logEvent("Shot encountered defender " + d.name + " at " + currentGrid.x + "_" + currentGrid.y + ", outcome: " + ballResult);
                        if (ballResult != null && (ballResult.startsWith("SAVE") || ballResult.startsWith("MISS") || ballResult.equals("CATCH"))) {
                            handleSaveDeflection(ballOwner);
                            shotStage = 5; // animate deflection/miss
                        } else if ("GOAL".equals(ballResult)) {
                            shotStage = 4;
                            shotDelayCounter = GOAL_CELEBRATION_DELAY;
                        } else {
                            // default to out
                            ballResult = "OUT";
                            shotStage = 5;
                        }
                        return;
                    }
                }

                // If arrived at goal area, resolve outcome
                if (arrived) {
                    if (ballResult == null) {
                        resolveShotOutcome(ballOwner);
                        logEvent("Action Resolver decided outcome at goal: " + ballResult);
                    }

                    if ("GOAL".equals(ballResult)) {
                        shotStage = 4;
                        shotDelayCounter = GOAL_CELEBRATION_DELAY;
                        logEvent("Shot resulted in GOAL - animating into net");
                    } else {
                        // Save/deflection/miss
                        handleSaveDeflection(ballOwner);
                        shotStage = 5;
                        logEvent("Shot resolved as " + ballResult + " -> animating post-outcome to " + (saveDeflectionTarget != null ? (saveDeflectionTarget.x + "_" + saveDeflectionTarget.y) : "unknown"));
                    }
                }
                break;
            }
            case 4:
                // Stage 4: Ball moves to goal (celebration)
                Point target4 = new Point(GOAL_COL, GOAL_ROW + 1);
                boolean arrived4 = moveBallToPoint(target4);
                if (arrived4) {
                    shotDelayCounter--;
                    logEvent("Stage 4: Ball at goal 6_3, celebration delay: " + shotDelayCounter + " frames");
                    if (shotDelayCounter <= 0) {
                        // Celebration complete
                        ballPos.setLocation(target4);
                        updateBallPixelPosition();
                        logEvent("GOAL! Celebration complete, resetting scenario");
                        resetScenario();
                        return;
                    }
                }
                break;
            case 5:
                // Stage 5: Save deflection animation
                Point target5 = saveDeflectionTarget;
                logEvent("Stage 5: Ball moving from 5_3 to " + target5.x + "_" + target5.y + " (save deflection)");
                arrived = moveBallToPoint(target5);
                if (arrived) {
                    // Save animation complete
                    ballPos.setLocation(target5);
                    updateBallPixelPosition();
                    logEvent("Save deflection complete at " + target5.x + "_" + target5.y);
                    finishAction(ballOwner);
                }
                break;
            default:
                logEvent("Invalid shot stage: " + shotStage + ", resetting");
                shotStage = 0;
                break;
        }
    }
    
    private boolean moveBallToPoint(Point targetGridPos) {
        double targetX = (targetGridPos.x - 1) * CELL_SIZE + CELL_SIZE / 2;
        double targetY = (GRID_SIZE - targetGridPos.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;

        double dx = targetX - ballPixelPos.x;
        double dy = targetY - ballPixelPos.y;
        double dist = Math.hypot(dx, dy);

        if (dist > ANIMATION_SPEED) {
            ballPixelPos.x += (dx / dist) * ANIMATION_SPEED;
            ballPixelPos.y += (dy / dist) * ANIMATION_SPEED;
            return false;
        } else {
            ballPixelPos.x = (int) targetX;
            ballPixelPos.y = (int) targetY;
            return true;
        }
    }
    
    private Point pixelToGrid(Point pixelPos) {
        int gridX = (int)((pixelPos.x - CELL_SIZE / 2) / CELL_SIZE) + 1;
        int gridY = (int)((pixelPos.y - CELL_SIZE - CELL_SIZE / 2) / CELL_SIZE) + 1;
        gridX = Math.max(1, Math.min(GRID_SIZE, gridX));
        gridY = Math.max(1, Math.min(GRID_SIZE, gridY));
        return new Point(gridX, gridY);
    }
    
    private void handleSaveDeflection(Player ballOwner) {
        logEvent("Handle save/deflection/miss, ballResult: " + ballResult);
        if (ballResult == null) {
            logEvent("ERROR: ballResult is null in handleSaveDeflection");
            ballOwner.targetPosition = new Point(GOAL_COL, GOAL_ROW);
            return;
        }
        switch (ballResult) {
            case "SAVE_TO_CORNER":
                ballOwner.targetPosition = saveDeflectionTarget; // e.g., 5_1
                logEvent("Ball deflects to corner " + saveDeflectionTarget.x + "_" + saveDeflectionTarget.y);
                break;
            case "SAVE_TO_FIELD":
                ballOwner.targetPosition = saveDeflectionTarget;
                logEvent("Ball deflects to field at " + saveDeflectionTarget.x + "_" + saveDeflectionTarget.y);
                break;
            case "CATCH":
                // Ball stays with GK
                ballOwner.targetPosition = new Point(GOAL_COL, GOAL_ROW);
                logEvent("GK catches the ball");
                break;
            case "MISS_LEFT":
            case "MISS_RIGHT":
                // Miss should continue just outside the goal frame
                if (saveDeflectionTarget == null) {
                    int offY = "MISS_LEFT".equals(ballResult) ? GOAL_COL - 1 : GOAL_COL + 1;
                    offY = Math.max(1, Math.min(GRID_SIZE, offY));
                    saveDeflectionTarget = new Point(offY, GOAL_ROW + 1);
                }
                ballOwner.targetPosition = saveDeflectionTarget;
                logEvent("Shot missed to " + saveDeflectionTarget.x + "_" + saveDeflectionTarget.y);
                break;
            default:
                logEvent("Unknown save type: " + ballResult + ", defaulting to catch");
                ballOwner.targetPosition = new Point(GOAL_COL, GOAL_ROW);
                break;
        }
    }
    
    private boolean moveAllPlayers() {
        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);
        
        boolean allArrived = true;
        for (Player p : allPlayers) {
            if (p.role == Role.GOALKEEPER) continue; // GK doesn't move
            
            double targetX = (p.movementTarget.x - 1) * CELL_SIZE + CELL_SIZE / 2;
            double targetY = (GRID_SIZE - p.movementTarget.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;
            
            double dx = targetX - p.pixelPos.x;
            double dy = targetY - p.pixelPos.y;
            double dist = Math.hypot(dx, dy);
            
            if (dist > ANIMATION_SPEED) {
                p.pixelPos.x += (dx / dist) * ANIMATION_SPEED;
                p.pixelPos.y += (dy / dist) * ANIMATION_SPEED;
                allArrived = false;
            } else {
                p.pixelPos.x = (int) targetX;
                p.pixelPos.y = (int) targetY;
                p.currentPos.setLocation(p.movementTarget);
            }
        }
        return allArrived;
    }

    private boolean moveBallToTarget(Player player) {
        double targetX = (player.targetPosition.x - 1) * CELL_SIZE + CELL_SIZE / 2;
        double targetY = (GRID_SIZE - player.targetPosition.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;

        double dx = targetX - ballPixelPos.x;
        double dy = targetY - ballPixelPos.y;
        double dist = Math.hypot(dx, dy);

        if (dist > ANIMATION_SPEED) {
            ballPixelPos.x += (dx / dist) * ANIMATION_SPEED;
            ballPixelPos.y += (dy / dist) * ANIMATION_SPEED;
            return false;
        } else {
            ballPixelPos.x = (int) targetX;
            ballPixelPos.y = (int) targetY;
            return true;
        }
    }

    private void finishAction(Player player) {
        // Print carrier info before turn
        Player ballOwner = findBallOwner();
        if (ballOwner != null) {
            logEvent("=== TURN START ===");
            logEvent("Carrier: " + ballOwner.name + " at " + ballOwner.currentPos.x + "_" + ballOwner.currentPos.y);
            logEvent("Decision: " + ballOwner.decision);
        }
        
        StringBuilder log = new StringBuilder();
        log.append("[").append(System.currentTimeMillis() % 10000).append("]\n");
        log.append(player.name).append(":\n");
        log.append("Action: ").append(player.decision).append("\n");

        if (player.decision == Action.SHOT) {
            // Decision only: start shot animation toward the goal center. Outcome will be resolved when
            // the ball reaches the goalkeeper/goal line by the Action Resolver.
            shotStage = 1;
            shotIntermediatePos = new Point(GOAL_COL, GOAL_ROW - 1); // approach point
            player.targetPosition = new Point(GOAL_COL, GOAL_ROW + 1); // visualize target at goal center
            player.hasBall = false; // ball leaves player
            ballResult = null; // outcome unknown until resolver runs
            shotShooter = player; // keep reference for animation/resolver
            logEvent(player.name + " decided to SHOOT toward goal (outcome pending)");
            // Initialize ball pixel position from shooter so animation starts smoothly
            updateBallPixelPosition();
            // Don't set ballPos yet - let animation handle ball movement
            // Return: let animation/action resolver handle completion
            return;
        } else if (player.decision == Action.THROUGH_BALL || player.decision == Action.PASS_SHORT || player.decision == Action.PASS_LONG) {
            ballResult = "PASS";
            // Don't set ballPos yet - let animation handle ball movement
            player.hasBall = false;
        } else if (player.decision == Action.CARRY || player.decision == Action.DRIBBLE) {
            ballResult = "CARRIED";
            // Don't teleport player - let animation handle movement
            player.movementTarget = new Point(player.targetPosition);
        } else {
            ballResult = "OUT";
        }

        log.append("Ball: ").append(ballResult).append("\n");
        appendLog(log.toString());

        if ("GOAL".equals(ballResult)) {
            // GOAL is handled by shot animation, this should not be reached
            logEvent("ERROR: GOAL reached in finishAction, should be handled by shot animation");
            return;
        }

        // Set ball position after animation completes (for non-shot actions)
        if (player.decision == Action.PASS_SHORT || player.decision == Action.PASS_LONG || 
            player.decision == Action.THROUGH_BALL) {
            ballPos.setLocation(player.targetPosition);
            updateBallPixelPosition();
        } else if (player.decision == Action.CARRY || player.decision == Action.DRIBBLE) {
            // Player should have moved to target during animation
            // Update positions to match where animation ended
            ballPos.setLocation(player.targetPosition);
            player.currentPos.setLocation(player.targetPosition);
            updateBallPixelPosition();
        }
        
        // Handle corner save - playmaker should already be at corner from animation
        if ("SAVE_TO_CORNER".equals(ballResult)) {
            for (Player p : homePlayers) {
                if (p.role == Role.PLAYMAKER) {
                    p.hasBall = true;
                    // Playmaker should have moved to corner during animation
                    logEvent("Playmaker at corner with ball");
                    break;
                }
            }
        }
        
        updateBallPixelPosition();
        player.clearDecision();
        
        // Clear shot shooter if shot just finished
        if (shotStage > 0 && shotShooter == player) {
            shotShooter = null;
            shotStage = 0;
            shotIntermediatePos = null;
        }
        
        // Check for duels after movement completes (next iteration)
        checkAndResolveDuels();
        
        // Check if any player is already at ball position
        Player atBall = findPlayerAtPosition(ballPos);
        if (atBall != null && !"GOAL".equals(ballResult) && !"OUT".equals(ballResult)) {
            atBall.hasBall = true;
            logEvent(atBall.name + " is at ball position and takes possession.");
        } else if (findBallOwner() == null && !"GOAL".equals(ballResult) && !"OUT".equals(ballResult)) {
            logEvent("Ball is free - closest players will race to get it in next turn");
        }
        
        // Do NOT auto-evaluate the next turn here. End of action — wait for next Animate Step.
        // Print positions after move
        logEvent("=== POSITIONS AFTER MOVE ===");
        for (Player p : homePlayers) {
            logEvent(p.name + ": " + p.currentPos.x + "_" + p.currentPos.y + (p.hasBall ? " (BALL)" : ""));
        }
        for (Player p : awayPlayers) {
            logEvent(p.name + ": " + p.currentPos.x + "_" + p.currentPos.y + (p.hasBall ? " (BALL)" : ""));
        }
        logEvent("Ball: " + ballPos.x + "_" + ballPos.y);
        
        // Capture test data if running test
        if (runningTest) {
            finishActionWithTestCapture(player);
        }

        // Reset movement target flag for the next action
        movementTargetsSet = false;

        // If single-step mode was active, stop animating after this action
        if (singleStepMode) {
            animating = false;
            animateMovement = false;
            singleStepMode = false;
        } else if (!animateMovement) {
            // Only stop animating if the user did not request continuous animation
            animating = false;
        }
    }

    private Player findBallOwner() {
        for (Player p : homePlayers) if (p.hasBall) return p;
        return null;
    }

    private Point saveDeflectionTarget = null;
    
    private void resolveGKSave() {
        Random rand = new Random();
        double r = rand.nextDouble();
        
        if (r < 0.33) {
            // GK saves to corner - ball will bounce from GK to 5_1 during animation
            ballResult = "SAVE_TO_CORNER";
            saveDeflectionTarget = new Point(1, 5);
            logEvent("GK save - will deflect to corner 5_1");
        } else if (r < 0.66) {
            // GK saves to field - ball will bounce to random position during animation
            ballResult = "SAVE_TO_FIELD";
            Random rand2 = new Random();
            int randX = 1 + rand2.nextInt(5);
            int randY = 1 + rand2.nextInt(5);
            saveDeflectionTarget = new Point(randX, randY);
            logEvent("GK save - will deflect to " + randX + "_" + randY);
        } else {
            // GK catches the ball
            ballResult = "CATCH";
            saveDeflectionTarget = new Point(GOAL_COL, GOAL_ROW);
            logEvent("GK catch - ball stays at 5_3");
            
            // GK has ball
            for (Player p : awayPlayers) {
                if (p.role == Role.GOALKEEPER) {
                    p.hasBall = true;
                    break;
                }
            }
        }
    }

    /**
     * Action Resolver for shots: decide GOAL / SAVE / DEFLECTION / MISS when the ball reaches the GK area.
     */
    private void resolveShotOutcome(Player shooter) {
        // Basic probabilistic resolver using distance and decision score
        double dist = distance(shooter.currentPos, new Point(GOAL_COL, GOAL_ROW + 1));
        Player closestDef = findClosestDefender(shooter);
        double pressure = closestDef != null ? Math.max(0, 1 - distance(shooter.currentPos, closestDef.currentPos) / 3.0) : 0;

        // Base probabilities by distance
        double baseGoalProb;
        if (dist <= 1.0) baseGoalProb = 0.60;
        else if (dist <= 2.0) baseGoalProb = 0.45;
        else if (dist <= 3.0) baseGoalProb = 0.30;
        else baseGoalProb = 0.08;

        // Adjust by decisionScore modestly (normalize)
        double skillAdj = Math.min(0.15, shooter.decisionScore / 300.0);
        double goalProb = baseGoalProb + skillAdj - pressure * 0.20;
        goalProb = Math.max(0.01, Math.min(0.95, goalProb));

        double r = Math.random();
        if (r < goalProb) {
            ballResult = "GOAL";
            saveDeflectionTarget = new Point(GOAL_COL, GOAL_ROW + 1);
            return;
        }

        // Not a goal -> determine save/deflection/miss
        double r2 = Math.random();
        if (r2 < 0.60) {
            // Keeper makes a save - determine save type
            resolveGKSave(); // sets ballResult to SAVE_TO_FIELD / SAVE_TO_CORNER / CATCH
        } else if (r2 < 0.85) {
            // Deflection / block by defender
            ballResult = "SAVE_TO_FIELD";
            Random rand = new Random();
            int rx = 1 + rand.nextInt(5);
            int ry = 1 + rand.nextInt(5);
            saveDeflectionTarget = new Point(rx, ry);
            logEvent("Deflected by defender to " + rx + "_" + ry);
        } else {
            // Miss - ball aimed at goal center but goes slightly off-target
            double dir = Math.random();
            if (dir < 0.5) ballResult = "MISS_LEFT"; else ballResult = "MISS_RIGHT";
            int offCol = "MISS_LEFT".equals(ballResult) ? Math.max(1, GOAL_COL - 1) : Math.min(GRID_SIZE, GOAL_COL + 1);
            saveDeflectionTarget = new Point(offCol, GOAL_ROW + 1);
            logEvent("Shot missed to " + saveDeflectionTarget.x + "_" + saveDeflectionTarget.y);
        }
    }
    
    private Player findPlayerAtPosition(Point pos) {
        for (Player p : homePlayers) {
            if (p.currentPos.equals(pos)) return p;
        }
        for (Player p : awayPlayers) {
            if (p.currentPos.equals(pos)) return p;
        }
        return null;
    }
    
    private void gkKick() {
        // GK kicks to random field position
        Random rand = new Random();
        int randX = 1 + rand.nextInt(5);
        int randY = 1 + rand.nextInt(5);
        Point kickTarget = new Point(randX, randY);
        logEvent("GK kicking to " + randX + "_" + randY);
        
        // GK loses ball
        for (Player p : awayPlayers) {
            if (p.role == Role.GOALKEEPER) {
                p.hasBall = false;
                break;
            }
        }
        
        // Animate ball to kick target
        animateBallToPosition(kickTarget, () -> {
            ballPos.setLocation(kickTarget);
            updateBallPixelPosition();
            
            // Check if player is at that position
            Player atPosition = findPlayerAtPosition(ballPos);
            if (atPosition != null) {
                atPosition.hasBall = true;
                logEvent(atPosition.name + " is at ball position and becomes carrier.");
            } else {
                logEvent("No player at ball position - race to ball.");
            }
            
            evaluateAllDecisions();
        });
    }
    
    private void animateBallToPosition(Point targetGridPos, Runnable onComplete) {
        // Simple animation - move ball over several frames
        javax.swing.Timer animTimer = new javax.swing.Timer(16, e -> {
            double targetX = (targetGridPos.x - 1) * CELL_SIZE + CELL_SIZE / 2;
            double targetY = (GRID_SIZE - targetGridPos.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;
            
            double dx = targetX - ballPixelPos.x;
            double dy = targetY - ballPixelPos.y;
            double dist = Math.hypot(dx, dy);
            
            if (dist > ANIMATION_SPEED * 2) {
                ballPixelPos.x += (dx / dist) * ANIMATION_SPEED * 2;
                ballPixelPos.y += (dy / dist) * ANIMATION_SPEED * 2;
                repaint();
            } else {
                ballPixelPos.x = (int) targetX;
                ballPixelPos.y = (int) targetY;
                repaint();
                ((javax.swing.Timer)e.getSource()).stop();
                onComplete.run();
            }
        });
        animTimer.start();
    }
    
    private void moveClosestPlayerToBall() {
        // Find closest defender and closest attacker to ball
        Player closestDefender = null;
        Player closestAttacker = null;
        double bestDefDist = Double.MAX_VALUE;
        double bestAttDist = Double.MAX_VALUE;
        
        for (Player p : awayPlayers) {
            if (!p.hasBall && p.role != Role.GOALKEEPER) {
                double dist = distance(p.currentPos, ballPos);
                if (dist < bestDefDist) {
                    bestDefDist = dist;
                    closestDefender = p;
                }
            }
        }
        
        for (Player p : homePlayers) {
            if (!p.hasBall) {
                double dist = distance(p.currentPos, ballPos);
                if (dist < bestAttDist) {
                    bestAttDist = dist;
                    closestAttacker = p;
                }
            }
        }
        
        // Both race to ball - whoever is closer wins
        if (closestDefender != null && closestAttacker != null) {
            if (bestDefDist <= bestAttDist) {
                // Only move if not already at ball position
                if (!closestDefender.currentPos.equals(ballPos)) {
                    closestDefender.movementTarget = new Point(ballPos);
                    closestDefender.hasBall = true;
                    logEvent(closestDefender.name + " racing to ball");
                } else {
                    closestDefender.hasBall = true;
                    logEvent(closestDefender.name + " already at ball position");
                }
            } else {
                if (!closestAttacker.currentPos.equals(ballPos)) {
                    closestAttacker.movementTarget = new Point(ballPos);
                    closestAttacker.hasBall = true;
                    logEvent(closestAttacker.name + " racing to ball");
                } else {
                    closestAttacker.hasBall = true;
                    logEvent(closestAttacker.name + " already at ball position");
                }
            }
        } else if (closestDefender != null) {
            if (!closestDefender.currentPos.equals(ballPos)) {
                closestDefender.movementTarget = new Point(ballPos);
                closestDefender.hasBall = true;
                logEvent(closestDefender.name + " racing to ball");
            } else {
                closestDefender.hasBall = true;
                logEvent(closestDefender.name + " already at ball position");
            }
        } else if (closestAttacker != null) {
            if (!closestAttacker.currentPos.equals(ballPos)) {
                closestAttacker.movementTarget = new Point(ballPos);
                closestAttacker.hasBall = true;
                logEvent(closestAttacker.name + " racing to ball");
            } else {
                closestAttacker.hasBall = true;
                logEvent(closestAttacker.name + " already at ball position");
            }
        }
    }

    private void setMovementTargetsForIdlePlayers(Player activePlayer) {
        Random rand = new Random();
        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);
        
        // Get GK positions to protect
        Point homeGKPos = null;
        Point awayGKPos = null;
        for (Player p : homePlayers) if (p.role == Role.GOALKEEPER) homeGKPos = p.currentPos;
        for (Player p : awayPlayers) if (p.role == Role.GOALKEEPER) awayGKPos = p.currentPos;
        
        // Check if active player is making a pass
        boolean isPass = activePlayer.decision == Action.PASS_SHORT || 
                        activePlayer.decision == Action.PASS_LONG || 
                        activePlayer.decision == Action.THROUGH_BALL;
        
        // Check if pass destination has a player (teammate)
        Player passReceiver = null;
        if (isPass) {
            for (Player p : allPlayers) {
                if (p.team.equals(activePlayer.team) && p.currentPos.equals(activePlayer.targetPosition)) {
                    passReceiver = p;
                    break;
                }
            }
        }
        
        for (Player p : allPlayers) {
            // Skip the active player, ball carrier, and GK (GK stays fixed)
            if (p == activePlayer || p.hasBall || p.role == Role.GOALKEEPER) {
                p.movementTarget = new Point(p.currentPos);
                continue;
            }
            
            // If pass to player and this is the receiver, move to pass destination
            if (passReceiver != null && p == passReceiver) {
                p.movementTarget = new Point(activePlayer.targetPosition);
                continue;
            }
            
            // Home attacker special logic after pass
            if (isPass && p.team.equals("HOME") && (p.role == Role.ATTACKER || p.role == Role.PLAYMAKER)) {
                // If pass goes to this attacker's position, stay
                if (p.currentPos.equals(activePlayer.targetPosition)) {
                    p.movementTarget = new Point(p.currentPos);
                    continue;
                }
                // If pass goes to adjacent position, target it first
                if (isAdjacentTo(p.currentPos, activePlayer.targetPosition)) {
                    p.movementTarget = new Point(activePlayer.targetPosition);
                    continue;
                }
            }
            
            // Away defender: if adjacent to HOME attacker WITH ball, move to duel
            if (p.team.equals("AWAY") && p.role == Role.DEFENDER && 
                activePlayer.team.equals("HOME") && activePlayer.hasBall &&
                isAdjacentTo(p.currentPos, activePlayer.currentPos)) {
                p.movementTarget = new Point(activePlayer.currentPos);
                continue;
            }
            
            // Away defender: move towards ball position if not adjacent
            if (p.team.equals("AWAY") && p.role == Role.DEFENDER) {
                Point ballTarget = activePlayer.hasBall ? activePlayer.targetPosition : ballPos;
                if (!p.currentPos.equals(ballTarget)) {
                    // Move one step towards ball
                    Point towardsBall = getStepTowards(p.currentPos, ballTarget);
                    if (towardsBall != null && !wouldCollideWithTeammate(p, towardsBall, allPlayers) &&
                        !wouldCollideWithOpponent(p, towardsBall, allPlayers)) {
                        p.movementTarget = towardsBall;
                        continue;
                    }
                }
            }
            
            // If pass to empty and player is adjacent to pass destination, target it
            if (isPass && passReceiver == null && isAdjacentTo(p.currentPos, activePlayer.targetPosition)) {
                p.movementTarget = new Point(activePlayer.targetPosition);
                continue;
            }
            
            // Random 8-direction movement for idle players
            Point newPos = getRandomAdjacentPosition(p.currentPos, rand);
            
            // Don't move into GK spot
            if (p.team.equals("HOME") && homeGKPos != null && newPos.equals(homeGKPos)) {
                p.movementTarget = new Point(p.currentPos);
                continue;
            }
            if (p.team.equals("AWAY") && awayGKPos != null && newPos.equals(awayGKPos)) {
                p.movementTarget = new Point(p.currentPos);
                continue;
            }
            
            // Don't move to same position as teammate
            if (wouldCollideWithTeammate(p, newPos, allPlayers)) {
                p.movementTarget = new Point(p.currentPos);
                continue;
            }
            
            // Don't move to same position as opposing player WITHOUT ball
            // (allowed if ball is there - that's a duel)
            if (wouldCollideWithOpponent(p, newPos, allPlayers)) {
                p.movementTarget = new Point(p.currentPos);
                continue;
            }
            
            p.movementTarget = newPos;
        }
    }
    
    private Point getRandomAdjacentPosition(Point current, Random rand) {
        // 8 directions: N, NE, E, SE, S, SW, W, NW
        int[][] directions = {
            {0, 1}, {1, 1}, {1, 0}, {1, -1},
            {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
        };
        
        int[] dir = directions[rand.nextInt(directions.length)];
        int newX = Math.max(1, Math.min(GRID_SIZE, current.x + dir[0]));
        int newY = Math.max(1, Math.min(GRID_SIZE, current.y + dir[1]));
        
        return new Point(newX, newY);
    }
    
    private boolean isAdjacentTo(Point pos1, Point pos2) {
        int dx = Math.abs(pos1.x - pos2.x);
        int dy = Math.abs(pos1.y - pos2.y);
        return (dx <= 1 && dy <= 1) && !(dx == 0 && dy == 0);
    }
    
    private Point getStepTowards(Point from, Point to) {
        int dx = Integer.compare(to.x, from.x);
        int dy = Integer.compare(to.y, from.y);
        int newX = from.x + dx;
        int newY = from.y + dy;
        if (newX >= 1 && newX <= GRID_SIZE && newY >= 1 && newY <= GRID_SIZE) {
            return new Point(newX, newY);
        }
        return null;
    }
    
    private boolean wouldCollideWithTeammate(Player player, Point newPos, List<Player> allPlayers) {
        for (Player p : allPlayers) {
            if (p == player) continue;
            if (p.team.equals(player.team) && p.currentPos.equals(newPos)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean wouldCollideWithOpponent(Player player, Point newPos, List<Player> allPlayers) {
        for (Player p : allPlayers) {
            if (p == player) continue;
            if (!p.team.equals(player.team) && p.currentPos.equals(newPos)) {
                // Opposing player at this position - only allowed if ball is there
                if (!ballPos.equals(newPos)) {
                    return true; // Collision without ball - not allowed
                }
            }
        }
        return false;
    }
    
    private void checkAndResolveDuels() {
        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);
        
        // Check for opposing players on same field WITH ball - triggers duel
        for (Player p1 : allPlayers) {
            for (Player p2 : allPlayers) {
                if (p1 == p2) continue;
                if (!p1.team.equals(p2.team) && p1.currentPos.equals(p2.currentPos)) {
                    // Opposing players on same field - only duel if ball is also there
                    if (ballPos.equals(p1.currentPos)) {
                        resolveDuel(p1, p2);
                    }
                }
            }
        }
    }
    
    private void resolveOpposingCollisions() {
        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);
        
        for (Player p1 : allPlayers) {
            for (Player p2 : allPlayers) {
                if (p1 == p2) continue;
                if (!p1.team.equals(p2.team) && p1.currentPos.equals(p2.currentPos)) {
                    // Opposing players on same field WITHOUT ball - move one randomly
                    if (!ballPos.equals(p1.currentPos)) {
                        Random rand = new Random();
                        Player toMove = rand.nextBoolean() ? p1 : p2;
                        Point newPos = getRandomAdjacentPosition(toMove.currentPos, rand);
                        
                        // Ensure new position is valid
                        if (!wouldCollideWithTeammate(toMove, newPos, allPlayers) &&
                            !wouldCollideWithOpponent(toMove, newPos, allPlayers)) {
                            toMove.movementTarget = new Point(newPos);
                            logEvent(toMove.name + " moving to avoid collision at " + newPos.x + "_" + newPos.y);
                        }
                    }
                }
            }
        }
    }
    
    private void resolveSameTeamCollisions() {
        List<Player> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);
        
        for (Player p1 : allPlayers) {
            for (Player p2 : allPlayers) {
                if (p1 == p2) continue;
                if (p1.team.equals(p2.team) && p1.currentPos.equals(p2.currentPos)) {
                    // Same team on same field - move one randomly
                    Random rand = new Random();
                    Player toMove = rand.nextBoolean() ? p1 : p2;
                    Point newPos = getRandomAdjacentPosition(toMove.currentPos, rand);
                    
                    // Ensure new position is valid
                    if (!wouldCollideWithTeammate(toMove, newPos, allPlayers) &&
                        !wouldCollideWithOpponent(toMove, newPos, allPlayers)) {
                        toMove.movementTarget = new Point(newPos);
                        logEvent(toMove.name + " moving to avoid same-team collision at " + newPos.x + "_" + newPos.y);
                    }
                }
            }
        }
    }
    
    private void resolveDuel(Player p1, Player p2) {
        // Simple duel: random winner
        Random rand = new Random();
        Player winner = rand.nextBoolean() ? p1 : p2;
        Player loser = (winner == p1) ? p2 : p1;
        
        logEvent("DUEL: " + winner.name + " vs " + loser.name + " - " + winner.name + " wins!");
        
        // Winner stays with ball - ball is already at this position
        winner.hasBall = true;
        // Don't set ballPos - it's already at the duel position
        
        // Loser moves 1 position in random direction (smooth animation)
        Random rand2 = new Random();
        int direction = rand2.nextBoolean() ? 1 : -1;
        int newX = Math.max(1, Math.min(GRID_SIZE, loser.currentPos.x + direction));
        loser.movementTarget = new Point(newX, loser.currentPos.y);
        loser.hasBall = false;
    }

    private Player findBestReceiver(Player passer) {
        List<Player> teammates = getTeammates(passer);
        Player best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Point target = passer.targetPosition;

        for (Player tm : teammates) {
            double score = -distance(target, tm.currentPos);
            if (tm.role == Role.ATTACKER) score += 20;
            if (score > bestScore) {
                bestScore = score;
                best = tm;
            }
        }
        return best;
    }

    private void resetBall() {
        ballPos.setLocation(2, 1);
        ballResult = null;
        updateBallPixelPosition();
    }

    private void updateBallPixelPosition() {
        // Convert 1-based coordinates to pixel positions
        // Row 1 is at bottom, row 5 at top (invert y), extra row at top for goal
        ballPixelPos.x = (ballPos.x - 1) * CELL_SIZE + CELL_SIZE / 2;
        ballPixelPos.y = (GRID_SIZE - ballPos.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;
    }

    private void resetScenario() {
        animating = false;
        setAnimateMovement(false);
        resetBall();
        shotStage = 0;
        shotIntermediatePos = null;
        shotDelayCounter = 0;
        ballResult = null;
        saveDeflectionTarget = null;
        initializeScenario();
        updateBallPixelPosition();
        selectedPlayer = null;
        eventLog.setText("");
        logEvent("Scenario reset");
        repaint();
    }

    // ===================== PLAYER MODEL =====================

    public enum Role { PLAYMAKER, ATTACKER, MIDFIELDER, DEFENDER, GOALKEEPER }

    public enum Action {
        PASS_SHORT, PASS_LONG, THROUGH_BALL, CARRY, DRIBBLE, SHOT
    }

    public static class Player {
        public String name;
        public String team;
        public Role role;
        public Point currentPos;
        public boolean hasBall;
        public Action decision;
        public double decisionScore;
        public Point targetPosition;
        public Point movementTarget; // For smooth animation
        public String reason;
        public Map<Action, Double> actionScores;
        public Map<Action, Evaluation> actionEvals;
        public Point pixelPos;

        // Simple evaluation result container
        public static class Evaluation {
            public double score;
            public String reason;
            public Point target;

            Evaluation(double score, String reason, Point target) {
                this.score = score;
                this.reason = reason;
                this.target = target;
            }
        }

        Player(String name, String team, Role role, Point startPos) {
            this.name = name;
            this.team = team;
            this.role = role;
            this.currentPos = new Point(startPos);
            this.hasBall = false;
            this.decision = null;
            this.decisionScore = 0;
            this.targetPosition = new Point(startPos);
            this.movementTarget = new Point(startPos);
            this.reason = "";
            this.actionScores = new LinkedHashMap<>();
            this.actionEvals = new LinkedHashMap<>();
            this.pixelPos = new Point(0, 0);
            updatePixelPosition();
        }

        void updatePixelPosition() {
            // Convert 1-based coordinates to pixel positions
            // Row 1 is at bottom, row 5 at top (invert y), extra row at top for goal
            pixelPos.x = (currentPos.x - 1) * CELL_SIZE + CELL_SIZE / 2;
            pixelPos.y = (GRID_SIZE - currentPos.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;
        }

        void clearDecision() {
            this.decision = null;
            this.decisionScore = 0;
            this.targetPosition = new Point(currentPos);
            this.movementTarget = new Point(currentPos);
            this.reason = "";
            this.actionScores.clear();
            this.actionEvals.clear();
        }

        void evaluateDecisions(PlayerDecisionDemo demo) {
            actionScores.clear();
            actionEvals.clear();

            // Evaluate each action using dedicated evaluator methods
            actionEvals.put(Action.PASS_SHORT, evaluateShortPass(demo));
            actionEvals.put(Action.PASS_LONG, evaluateLongPass(demo));
            actionEvals.put(Action.THROUGH_BALL, evaluateThroughBall(demo));
            actionEvals.put(Action.CARRY, evaluateCarry(demo));
            actionEvals.put(Action.DRIBBLE, evaluateDribble(demo));
            actionEvals.put(Action.SHOT, evaluateShot(demo));

            for (Map.Entry<Action, Evaluation> e : actionEvals.entrySet()) {
                actionScores.put(e.getKey(), e.getValue().score);
            }

            // Choose best action by highest score
            decision = Collections.max(actionScores.entrySet(), Map.Entry.comparingByValue()).getKey();
            decisionScore = actionScores.get(decision);

            // Set target and reason from the chosen evaluation
            Evaluation chosen = actionEvals.get(decision);
            if (chosen != null) {
                this.targetPosition = new Point(chosen.target);
                this.reason = chosen.reason;
            } else {
                this.targetPosition = new Point(currentPos);
                this.reason = "No evaluation available";
            }

            demo.applyDecision(this);
        }

        // ----- Evaluators: each returns score, reason and target -----
        private Evaluation evaluateShot(PlayerDecisionDemo demo) {
            Point goalCenter = new Point(GOAL_COL, GOAL_ROW + 1);
            double dist = PlayerDecisionDemo.distance(this.currentPos, new Point(GOAL_ROW, GOAL_COL));
            Player closestDef = demo.findClosestDefender(this);
            double pressure = closestDef != null ? Math.max(0, 1 - PlayerDecisionDemo.distance(currentPos, closestDef.currentPos) / 3.0) : 0;

            double base = 0.0;
            if (dist <= 1.0) base = 55;
            else if (dist <= 2.0) base = 42;
            else if (dist <= 3.0) base = 28;
            else base = 6;

            // Role adjustments
            if (role == Role.ATTACKER) base += 8;
            if (role == Role.PLAYMAKER) base -= 10;

            double score = base - pressure * 25;
            String reason = String.format("Dist: %.1f, Pressure: %.2f. ", dist, pressure);
            reason += (role == Role.ATTACKER ? "Striker bias. " : "");
            if (score < 0) score = 0;
            return new Evaluation(score, reason + "Shooting at goal", goalCenter);
        }

        private Evaluation evaluateShortPass(PlayerDecisionDemo demo) {
            // Short pass favors safety and forward progress
            double score = 30 + forwardProgressScore(demo);
            Player closestDef = demo.findClosestDefender(this);
            double pressure = closestDef != null ? Math.max(0, 1 - PlayerDecisionDemo.distance(currentPos, closestDef.currentPos) / 3.0) : 0;
            score -= pressure * 12;
            if (role == Role.PLAYMAKER) score += 12;

            Player best = findBestPassTarget(demo);
            String reason = (best != null) ? ("To " + best.name) : "No good short pass target";
            Point target = best != null ? new Point(best.currentPos) : new Point(this.currentPos);
            return new Evaluation(score, reason, target);
        }

        private Evaluation evaluateLongPass(PlayerDecisionDemo demo) {
            double score = 18 + forwardProgressScore(demo) * 1.5;
            if (role == Role.PLAYMAKER) score += 15;
            Player best = findBestPassTarget(demo);
            String reason = (best != null) ? ("Long to " + best.name) : "No good long pass target";
            Point target = best != null ? new Point(best.currentPos) : new Point(this.currentPos);
            return new Evaluation(score, reason, target);
        }

        private Evaluation evaluateThroughBall(PlayerDecisionDemo demo) {
            double score = 10;
            boolean attackerAhead = isAttackerAheadOfDefense(demo, this);
            double extra = 0;
            String reason = "";
            Point target = new Point(this.currentPos);
            if (attackerAhead) {
                extra += 40;
                extra += spaceBehindDefense(demo);
                reason = "Attacker behind defense";
                // pick the attacker as target
                for (Player tm : demo.getTeammates(this)) {
                    if (tm.role == Role.ATTACKER && tm.currentPos.x > getMaxDefenderX(demo)) {
                        // Through ball aims slightly ahead of the attacker (one cell towards goal)
                        int tx = Math.min(GRID_SIZE, tm.currentPos.x + 1);
                        int ty = tm.currentPos.y;
                        target = new Point(tx, ty);
                        reason += "; aiming ahead of " + tm.name;
                        break;
                    }
                }
            } else {
                reason = "No attacker behind line";
            }
            if (role == Role.PLAYMAKER) extra += 20;
            score += extra;
            return new Evaluation(score, reason, target);
        }

        private int getMaxDefenderX(PlayerDecisionDemo demo) {
            int max = 0;
            for (Player p : demo.getOpponents(this)) {
                if (p.role == Role.DEFENDER || p.role == Role.GOALKEEPER) {
                    max = Math.max(max, p.currentPos.x);
                }
            }
            return max;
        }

        private Evaluation evaluateCarry(PlayerDecisionDemo demo) {
            double score = 22 + spaceAhead(demo);
            Player closestDef = demo.findClosestDefender(this);
            double pressure = closestDef != null ? Math.max(0, 1 - PlayerDecisionDemo.distance(currentPos, closestDef.currentPos) / 3.0) : 0;
            score -= pressure * 14;
            String reason = "Carrying into space";
            Point target = new Point(Math.min(GRID_SIZE, currentPos.x + 2), currentPos.y);
            if (role == Role.PLAYMAKER) score -= 4;
            if (role == Role.DEFENDER) score += 6;
            return new Evaluation(score, reason, target);
        }

        private Evaluation evaluateDribble(PlayerDecisionDemo demo) {
            double score = 16 + spaceAhead(demo) * 0.5;
            Player closestDef = demo.findClosestDefender(this);
            double pressure = closestDef != null ? Math.max(0, 1 - PlayerDecisionDemo.distance(currentPos, closestDef.currentPos) / 3.0) : 0;
            score -= pressure * 20;
            if (role == Role.ATTACKER) score += 10;
            String reason = "Dribble attempt";
            Point target = new Point(Math.min(GRID_SIZE, currentPos.x + 1), currentPos.y);
            return new Evaluation(score, reason, target);
        }

        // ---- Helper scoring utilities (kept from previous logic) ----
        private double forwardProgressScore(PlayerDecisionDemo demo) {
            double best = 0;
            for (Player tm : demo.getTeammates(this)) {
                double progress = tm.currentPos.x - currentPos.x;
                if (progress > best) best = progress;
            }
            return best * 5;
        }

        private boolean isAttackerAheadOfDefense(PlayerDecisionDemo demo, Player player) {
            List<Player> defenders = new ArrayList<>();
            for (Player p : demo.getOpponents(player)) {
                if (p.role == Role.DEFENDER || p.role == Role.GOALKEEPER) defenders.add(p);
            }
            if (defenders.isEmpty()) return false;

            int maxDefenderX = defenders.stream().mapToInt(p -> p.currentPos.x).max().orElse(0);
            for (Player tm : demo.getTeammates(player)) {
                if (tm.role == Role.ATTACKER && tm.currentPos.x > maxDefenderX) {
                    return true;
                }
            }
            return false;
        }

        private double spaceBehindDefense(PlayerDecisionDemo demo) {
            List<Player> defenders = new ArrayList<>();
            for (Player p : demo.getOpponents(this)) {
                if (p.role == Role.DEFENDER) defenders.add(p);
            }
            if (defenders.isEmpty()) return 10;
            int maxDefenderX = defenders.stream().mapToInt(p -> p.currentPos.x).max().orElse(0);
            return Math.max(0, GRID_SIZE - 1 - maxDefenderX) * 3;
        }

        private double spaceAhead(PlayerDecisionDemo demo) {
            Player closest = demo.findClosestDefender(this);
            if (closest == null) return 15;
            double forwardSpace = closest.currentPos.x - currentPos.x;
            return Math.max(0, forwardSpace) * 5;
        }

        private Player findBestPassTarget(PlayerDecisionDemo demo) {
            Player best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Player tm : demo.getTeammates(this)) {
                double score = -PlayerDecisionDemo.distance(currentPos, tm.currentPos);
                score += tm.currentPos.x * 2;
                if (tm.role == Role.ATTACKER) score += 15;
                if (score > bestScore) {
                    bestScore = score;
                    best = tm;
                }
            }
            return best;
        }

        private String pressureSummary(PlayerDecisionDemo demo) {
            Player closest = demo.findClosestDefender(this);
            if (closest == null) return "No pressure. ";
            double dist = PlayerDecisionDemo.distance(currentPos, closest.currentPos);
            if (dist < 1.5) return "Under pressure. ";
            return "";
        }
    }

    // ===================== DRAWING =====================

    private void appendLog(String message) {
        eventLog.append(message);
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    private void logEvent(String message) {
        SwingUtilities.invokeLater(() -> appendLog("[" + System.currentTimeMillis() % 10000 + "] " + message + "\n"));
    }

    private void drawPlayerMarker(Graphics2D g2d, Point pos, Color color, String label, boolean hollow) {
        int x = pos.x * CELL_SIZE + CELL_SIZE / 2;
        int y = pos.y * CELL_SIZE + CELL_SIZE / 2;
        if (hollow) {
            g2d.setColor(color);
            g2d.drawOval(x - 6, y - 6, 12, 12);
        } else {
            g2d.setColor(color);
            g2d.fillOval(x - 6, y - 6, 12, 12);
        }
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.PLAIN, 9));
        FontMetrics fm = g2d.getFontMetrics();
        int w = fm.stringWidth(label);
        g2d.drawString(label, x - w / 2, y + PLAYER_RADIUS + 12);
    }

    private void updateInspector(Player p) {
        String threatInfo = "None";
        Player closest = findClosestDefender(p);
        if (closest != null) {
            threatInfo = "(" + closest.currentPos.x + ", " + closest.currentPos.y + ")";
        }
        String html = "<html>" +
                "<b>Name:</b> " + p.name + "<br>" +
                "<b>Team:</b> " + p.team + "<br>" +
                "<b>Role:</b> " + p.role + "<br>" +
                "<b>Position:</b> (" + p.currentPos.x + ", " + p.currentPos.y + ")<br>" +
                "<b>Has Ball:</b> " + p.hasBall + "<br>" +
                "<b>Decision:</b> " + (p.decision != null ? p.decision : "None") + "<br>" +
                "<b>Score:</b> " + String.format("%.1f", p.decisionScore) + "<br>" +
                "<b>Target:</b> " + (p.targetPosition != null ? "(" + p.targetPosition.x + ", " + p.targetPosition.y + ")" : "None") + "<br>" +
                "<b>Reason:</b> " + p.reason + "<br>" +
                "<b>Closest Defender:</b> " + threatInfo + "</html>";
        inspectorLabel.setText(html);
    }

    private void updateDecisionInspector(Player p) {
        if (p == null) {
            decisionLabel.setText("<html>Select a player</html>");
            return;
        }
        // Re-evaluate decisions if empty
        if (p.actionScores.isEmpty()) {
            p.evaluateDecisions(this);
        }
        if (p.actionScores.isEmpty()) {
            decisionLabel.setText("<html>No decisions available</html>");
            return;
        }
        StringBuilder html = new StringBuilder("<html>");
        if (!p.actionEvals.isEmpty()) {
            for (Map.Entry<Action, Player.Evaluation> entry : p.actionEvals.entrySet()) {
                Player.Evaluation ev = entry.getValue();
                html.append(entry.getKey()).append(": ").append(String.format("%.1f", ev.score));
                if (ev.reason != null && !ev.reason.isEmpty()) html.append(" - ").append(ev.reason);
                if (ev.target != null) html.append(" (-> ").append(ev.target.x).append("_").append(ev.target.y).append(")");
                html.append("<br>");
            }
        } else {
            for (Map.Entry<Action, Double> entry : p.actionScores.entrySet()) {
                html.append(entry.getKey()).append(": ").append(String.format("%.1f", entry.getValue())).append("<br>");
            }
        }
        html.append("<b>FINAL:</b> ").append(p.decision).append("</html>");
        decisionLabel.setText(html.toString());
    }

    // ===================== INNER CLASSES =====================

    private class DrawingPanel extends JPanel {
        public DrawingPanel() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handlePlayerSelection(e.getPoint());
                }
            });
        }

        private void handlePlayerSelection(Point click) {
            for (Player p : homePlayers) {
                if (clickOnPlayer(click, p)) {
                    selectedPlayer = p;
                    updateInspector(p);
                    updateDecisionInspector(p);
                    logEvent("Selected: " + p.name);
                    return;
                }
            }
            for (Player p : awayPlayers) {
                if (clickOnPlayer(click, p)) {
                    selectedPlayer = p;
                    updateInspector(p);
                    updateDecisionInspector(p);
                    logEvent("Selected: " + p.name);
                    return;
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawGrid(g2d);
            drawGoalZone(g2d);

            if (showMovementPaths) drawPaths(g2d);
            if (showTargetPosition) drawTargets(g2d);
            if (showDecisions) drawDecisionLabels(g2d);
            drawPlayers(g2d);
            drawBall(g2d);

            if (ballResult != null) drawBallResult(g2d);
        }

        private void drawGrid(Graphics2D g2d) {
            g2d.setColor(Color.LIGHT_GRAY);
            // Draw grid for 5 rows + 1 extra row at top for goal
            for (int i = 0; i <= GRID_SIZE; i++) {
                g2d.drawLine(i * CELL_SIZE, CELL_SIZE, i * CELL_SIZE, PANEL_SIZE); // Start from row 1 (skip goal row)
                g2d.drawLine(0, i * CELL_SIZE + CELL_SIZE, PANEL_SIZE, i * CELL_SIZE + CELL_SIZE);
            }
            // Draw horizontal line separating goal row from grid
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawLine(0, CELL_SIZE, PANEL_SIZE, CELL_SIZE);
        }

        private Point gridToPixel(Point gridPos) {
            // Convert 1-based coordinates to pixel positions
            // Row 1 is at bottom, row 5 at top (invert y), extra row at top for goal
            return new Point((gridPos.x - 1) * CELL_SIZE + CELL_SIZE / 2, (GRID_SIZE - gridPos.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2);
        }

        private void drawGoalZone(Graphics2D g2d) {
            // Goal is in the extra row above row 5, column 3 (dead center)
            int goalX = (GOAL_COL - 1) * CELL_SIZE;
            int goalY = 0; // Top row (goal area)
            
            // Draw goal posts and net (no yellow background)
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(6));
            g2d.drawLine(goalX, goalY + 10, goalX, goalY + CELL_SIZE - 10); // Left post
            g2d.drawLine(goalX + CELL_SIZE, goalY + 10, goalX + CELL_SIZE, goalY + CELL_SIZE - 10); // Right post
            g2d.drawLine(goalX, goalY + 10, goalX + CELL_SIZE, goalY + 10); // Crossbar
            
            // Draw net pattern
            g2d.setColor(new Color(200, 200, 200, 80));
            g2d.setStroke(new BasicStroke(1));
            for (int i = 0; i <= CELL_SIZE; i += 15) {
                g2d.drawLine(goalX + i, goalY + 10, goalX + i, goalY + CELL_SIZE - 10);
                g2d.drawLine(goalX, goalY + 10 + i, goalX + CELL_SIZE, goalY + 10 + i);
            }
            
            // Draw GOAL text
            g2d.setColor(Color.RED);
            g2d.setFont(new Font("Arial", Font.BOLD, 18));
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth("GOAL");
            g2d.drawString("GOAL", goalX + (CELL_SIZE - textWidth) / 2, goalY + CELL_SIZE / 2 + 6);
        }

        private void drawTargets(Graphics2D g2d) {
            g2d.setColor(Color.GREEN);
            for (Player p : homePlayers) {
                if (p.decision != null && p.targetPosition != null) {
                    int x = (p.targetPosition.x - 1) * CELL_SIZE + CELL_SIZE / 2;
                    int y = (GRID_SIZE - p.targetPosition.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;
                    g2d.fillOval(x - 8, y - 8, 16, 16);
                    g2d.setColor(Color.BLACK);
                    g2d.setStroke(new BasicStroke(1));
                    g2d.drawOval(x - 8, y - 8, 16, 16);
                    g2d.setColor(Color.GREEN);
                }
            }
        }

        private void drawPaths(Graphics2D g2d) {
            g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{5, 3}, 0));
            for (Player p : homePlayers) {
                if (p.decision != null && p.targetPosition != null) {
                    int cx = (int) p.pixelPos.x;
                    int cy = (int) p.pixelPos.y;
                    int tx = (p.targetPosition.x - 1) * CELL_SIZE + CELL_SIZE / 2;
                    int ty = (GRID_SIZE - p.targetPosition.y) * CELL_SIZE + CELL_SIZE + CELL_SIZE / 2;
                    g2d.setColor(Color.BLUE);
                    g2d.drawLine(cx, cy, tx, ty);
                    double angle = Math.atan2(ty - cy, tx - cx);
                    g2d.drawLine(tx, ty, (int)(tx - 8 * Math.cos(angle - Math.PI / 6)), (int)(ty - 8 * Math.sin(angle - Math.PI / 6)));
                    g2d.drawLine(tx, ty, (int)(tx - 8 * Math.cos(angle + Math.PI / 6)), (int)(ty - 8 * Math.sin(angle + Math.PI / 6)));
                }
            }
        }

        private void drawDecisionLabels(Graphics2D g2d) {
            g2d.setFont(new Font("Arial", Font.BOLD, 9));
            for (Player p : homePlayers) {
                if (p.decision != null) {
                    int x = (int) p.pixelPos.x;
                    int y = (int) p.pixelPos.y;
                    g2d.setColor(Color.MAGENTA);
                    String txt = p.decision.toString();
                    FontMetrics fm = g2d.getFontMetrics();
                    int w = fm.stringWidth(txt);
                    g2d.drawString(txt, x - w / 2, y - PLAYER_RADIUS - 8);
                }
            }
        }

        private void drawPlayers(Graphics2D g2d) {
            for (Player p : homePlayers) {
                int x = (int) p.pixelPos.x;
                int y = (int) p.pixelPos.y;
                Color color = (p == selectedPlayer) ? Color.CYAN : Color.BLUE;
                if (p.hasBall) color = Color.GREEN;
                drawPlayerCircle(g2d, x, y, color, p.name, p.hasBall);
            }
            for (Player p : awayPlayers) {
                int x = (int) p.pixelPos.x;
                int y = (int) p.pixelPos.y;
                Color color = (p == selectedPlayer) ? Color.MAGENTA : Color.RED;
                drawPlayerCircle(g2d, x, y, color, p.name, false);
            }
        }

        private void drawPlayerCircle(Graphics2D g2d, int x, int y, Color color, String name, boolean hasBall) {
            g2d.setColor(color);
            g2d.fillOval(x - PLAYER_RADIUS, y - PLAYER_RADIUS, PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawOval(x - PLAYER_RADIUS, y - PLAYER_RADIUS, PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Arial", Font.BOLD, 10));
            FontMetrics fm = g2d.getFontMetrics();
            int nameWidth = fm.stringWidth(name);
            g2d.drawString(name, x - nameWidth / 2, y + PLAYER_RADIUS + 12);
            if (hasBall) {
                g2d.setColor(Color.WHITE);
                g2d.fillOval(x + PLAYER_RADIUS - 5, y - PLAYER_RADIUS + 5, 12, 12);
                g2d.setColor(Color.BLACK);
                g2d.drawOval(x + PLAYER_RADIUS - 5, y - PLAYER_RADIUS + 5, 12, 12);
            }
        }

        private void drawBall(Graphics2D g2d) {
            g2d.setColor(Color.WHITE);
            g2d.fillOval(ballPixelPos.x - BALL_RADIUS, ballPixelPos.y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawOval(ballPixelPos.x - BALL_RADIUS, ballPixelPos.y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
            g2d.fillOval(ballPixelPos.x - 4, ballPixelPos.y - 4, 8, 8);
        }

        private void drawBallResult(Graphics2D g2d) {
            g2d.setColor(new Color(0, 0, 0, 150));
            g2d.fillRect(0, PANEL_SIZE - 30, PANEL_SIZE, 30);
            g2d.setColor(Color.YELLOW);
            g2d.setFont(new Font("Arial", Font.BOLD, 16));
            g2d.drawString("Ball Result: " + ballResult, 10, PANEL_SIZE - 8);
        }

        private boolean clickOnPlayer(Point click, Player p) {
            int px = (int) p.pixelPos.x;
            int py = (int) p.pixelPos.y;
            return Math.hypot(click.x - px, click.y - py) <= PLAYER_RADIUS + 10;
        }
    }

    // ===================== SETTERS =====================

    private void setShowDecisions(boolean v) { showDecisions = v; }
    private void setShowTargetPosition(boolean v) { showTargetPosition = v; }
    private void setShowMovementPaths(boolean v) { showMovementPaths = v; }
    private void setShowActionScores(boolean v) { showActionScores = v; }

    private void setAnimateMovement(boolean v) {
        boolean wasAnimating = animateMovement;
        animateMovement = v;
        animating = v;
        // When animation starts, evaluate all decisions first
        if (v) {
            // If starting from a stopped state, run single-step (one action) unless already running
            if (!wasAnimating) singleStepMode = true;
            evaluateAllDecisions();
        }
    }

    // ===================== MAIN =====================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PlayerDecisionDemo demo = new PlayerDecisionDemo();
            demo.setVisible(true);
        });
    }
}