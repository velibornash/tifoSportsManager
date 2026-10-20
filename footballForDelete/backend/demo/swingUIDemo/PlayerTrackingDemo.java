package org.example.footballmanager.demo.swingUIDemo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PlayerTrackingDemo - Football AI Visualizer
 * 
 * This tool visualizes how the Tactical Editor drives player positioning and how AI temporarily overrides it.
 * Think of this tool as a debugger for the Tactical Editor, not a football simulation.
 * 
 * It demonstrates:
 * - Tactical Editor positions per ball zone
 * - Desired Position calculation
 * - Threat detection and intent override
 * - Movement paths from Current to Desired Position
 */
public class PlayerTrackingDemo extends JFrame {
    
    private static final int GRID_SIZE = 5;
    private static final int CELL_SIZE = 100;
    private static final int PANEL_SIZE = GRID_SIZE * CELL_SIZE;
    private static final int BALL_RADIUS = 15;
    private static final int PLAYER_RADIUS = 20;
    private static final double ANIMATION_SPEED = 2.0;
    
    // Ball position (in grid coordinates 0-4) - 3_3 (center)
    private Point ballPos = new Point(2, 2);
    private Point ballPixelPos = new Point(0, 0);
    
    // Players
    private List<Player> homePlayers = new ArrayList<>();
    private List<Player> awayPlayers = new ArrayList<>();
    
    // Player selection for inspection
    private Player selectedPlayer;
    
    // Visualization toggles
    private boolean showTacticalTargets = true;
    private boolean showDesiredPosition = true;
    private boolean showCurrentPosition = true;
    private boolean showThreatRadius = false;
    private boolean showIntent = false;
    private boolean showPaths = true;
    private boolean threatOverrideEnabled = false;
    private boolean animateMovement = false;
    
    // Timer for animation
    private Timer animationTimer;
    
    // Event log
    private JTextArea eventLog;
    
    // Player inspector
    private JLabel inspectorLabel;
    
    // Current ball zone
    private int currentBallZone = -1;
    
    public PlayerTrackingDemo() {
        setTitle("Football AI Visualizer - Tactical Editor & Threat Detection");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        
        initializePlayers();
        updateBallPixelPosition();
        updateTacticalPositions();
        
        // Main layout
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        // Drawing panel
        DrawingPanel drawingPanel = new DrawingPanel();
        drawingPanel.setPreferredSize(new Dimension(PANEL_SIZE, PANEL_SIZE));
        
        // Right control panel
        JPanel controlPanel = createControlPanel();
        
        mainPanel.add(drawingPanel, BorderLayout.CENTER);
        mainPanel.add(controlPanel, BorderLayout.EAST);
        
        add(mainPanel);
        pack();
        setLocationRelativeTo(null);
        
        // Start animation timer
        startAnimation();
    }
    
    private void initializePlayers() {
        // Home players (blue team) - fixed starting positions
        homePlayers.add(new Player("Home 1", "HOME", new Point(0, 1), 80));  // 1_2
        homePlayers.add(new Player("Home 2", "HOME", new Point(0, 2), 80));  // 1_3
        homePlayers.add(new Player("Home 3", "HOME", new Point(0, 3), 80));  // 1_4
        
        // Away players (red team) - fixed starting positions
        awayPlayers.add(new Player("Away 1", "AWAY", new Point(4, 1), 80));  // 5_2
        awayPlayers.add(new Player("Away 2", "AWAY", new Point(4, 2), 80));  // 5_3
        awayPlayers.add(new Player("Away 3", "AWAY", new Point(4, 3), 80));  // 5_4
        
        // Initialize pixel positions
        for (Player p : homePlayers) {
            p.updatePixelPosition();
        }
        for (Player p : awayPlayers) {
            p.updatePixelPosition();
        }
    }
    
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(300, PANEL_SIZE));
        
        // Top section: Interaction mode
        JPanel modePanel = new JPanel(new GridLayout(0, 1));
        modePanel.setBorder(BorderFactory.createTitledBorder("Interaction Mode"));
        
        JLabel modeLabel = new JLabel("Move Ball (click or drag)");
        modeLabel.setFont(new Font("Arial", Font.BOLD, 12));
        modePanel.add(modeLabel);
        
        // Middle section: Visualization toggles
        JPanel togglePanel = new JPanel(new GridLayout(0, 1));
        togglePanel.setBorder(BorderFactory.createTitledBorder("Visualization"));
        
        JCheckBox tacticalCheck = new JCheckBox("Show Tactical Targets", showTacticalTargets);
        JCheckBox desiredCheck = new JCheckBox("Show Desired Position", showDesiredPosition);
        JCheckBox currentCheck = new JCheckBox("Show Current Position", showCurrentPosition);
        JCheckBox threatCheck = new JCheckBox("Show Threat Radius", showThreatRadius);
        JCheckBox intentCheck = new JCheckBox("Show Intent", showIntent);
        JCheckBox pathsCheck = new JCheckBox("Show Movement Paths", showPaths);
        JCheckBox threatOverrideCheck = new JCheckBox("Threat Override", threatOverrideEnabled);
        JCheckBox animateCheck = new JCheckBox("Animate Movement", animateMovement);
        
        tacticalCheck.addActionListener(e -> {
            showTacticalTargets = tacticalCheck.isSelected();
            repaint();
        });
        desiredCheck.addActionListener(e -> {
            showDesiredPosition = desiredCheck.isSelected();
            repaint();
        });
        currentCheck.addActionListener(e -> {
            showCurrentPosition = currentCheck.isSelected();
            repaint();
        });
        threatCheck.addActionListener(e -> {
            showThreatRadius = threatCheck.isSelected();
            repaint();
        });
        intentCheck.addActionListener(e -> {
            showIntent = intentCheck.isSelected();
            repaint();
        });
        pathsCheck.addActionListener(e -> {
            showPaths = pathsCheck.isSelected();
            repaint();
        });
        threatOverrideCheck.addActionListener(e -> {
            threatOverrideEnabled = threatOverrideCheck.isSelected();
            updateTacticalPositions();
            logEvent("Threat Override: " + (threatOverrideEnabled ? "ENABLED" : "DISABLED"));
            repaint();
        });
        animateCheck.addActionListener(e -> {
            animateMovement = animateCheck.isSelected();
            logEvent("Animate Movement: " + (animateMovement ? "ENABLED" : "DISABLED"));
            if (!animateMovement) {
                // Reset players to current positions when animation disabled
                for (Player p : homePlayers) {
                    p.currentPos = new Point(p.startPos);
                    p.updatePixelPosition();
                }
                for (Player p : awayPlayers) {
                    p.currentPos = new Point(p.startPos);
                    p.updatePixelPosition();
                }
            }
            repaint();
        });
        
        togglePanel.add(tacticalCheck);
        togglePanel.add(desiredCheck);
        togglePanel.add(currentCheck);
        togglePanel.add(threatCheck);
        togglePanel.add(intentCheck);
        togglePanel.add(pathsCheck);
        togglePanel.add(threatOverrideCheck);
        togglePanel.add(animateCheck);
        
        // Middle: Player Inspector
        JPanel inspectorPanel = new JPanel(new BorderLayout());
        inspectorPanel.setBorder(BorderFactory.createTitledBorder("Player Inspector"));
        inspectorLabel = new JLabel("<html>Select a player to inspect</html>");
        inspectorLabel.setVerticalAlignment(SwingConstants.TOP);
        inspectorPanel.add(new JScrollPane(inspectorLabel), BorderLayout.CENTER);
        inspectorPanel.setPreferredSize(new Dimension(280, 200));
        
        // Bottom: Event Log
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Event Log"));
        eventLog = new JTextArea(10, 30);
        eventLog.setEditable(false);
        eventLog.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane logScroll = new JScrollPane(eventLog);
        logPanel.add(logScroll, BorderLayout.CENTER);
        
        // Combine panels
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(modePanel, BorderLayout.NORTH);
        topPanel.add(togglePanel, BorderLayout.CENTER);
        
        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(inspectorPanel, BorderLayout.CENTER);
        panel.add(logPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private void updateBallPixelPosition() {
        ballPixelPos.x = ballPos.x * CELL_SIZE + CELL_SIZE / 2;
        ballPixelPos.y = ballPos.y * CELL_SIZE + CELL_SIZE / 2;
    }
    
    private int getBallZone() {
        return ballPos.y * GRID_SIZE + ballPos.x;
    }
    
    private void updateTacticalPositions() {
        int newZone = getBallZone();
        if (newZone != currentBallZone) {
            currentBallZone = newZone;
            logEvent("Ball Zone changed to: " + currentBallZone);
        }
        
        // Always update tactical positions (for threat override toggle)
        for (Player p : homePlayers) {
            p.tacticalPos = getTacticalPosition(p.name, "HOME", currentBallZone);
        }
        for (Player p : awayPlayers) {
            p.tacticalPos = getTacticalPosition(p.name, "AWAY", currentBallZone);
        }
        
        // Apply threat override if enabled
        if (threatOverrideEnabled) {
            updateThreatDetection();
        } else {
            // Reset to tactical positions
            for (Player p : homePlayers) {
                p.desiredPos = new Point(p.tacticalPos);
                p.intent = Player.Intent.RETURN_TO_SHAPE;
                p.reason = "Tactical Editor";
            }
            for (Player p : awayPlayers) {
                p.desiredPos = new Point(p.tacticalPos);
                p.intent = Player.Intent.RETURN_TO_SHAPE;
                p.reason = "Tactical Editor";
            }
        }
        
        logEvent("Tactical Targets updated");
    }
    
    private Point getTacticalPosition(String playerName, String team, int zone) {
        // Tactical Editor Matrix - hardcoded positions for all 25 ball zones
        // This demonstrates how the Tactical Editor drives player positioning
        int zoneX = zone % GRID_SIZE;
        int zoneY = zone / GRID_SIZE;
        
        if (team.equals("HOME")) {
            switch (playerName) {
                case "Home 1":
                    // Home 1 tactical position based on ball zone
                    if (zoneX <= 1) return new Point(0, zoneY);
                    if (zoneX >= 3) return new Point(1, zoneY);
                    return new Point(1, Math.max(0, zoneY - 1));
                case "Home 2":
                    // Home 2 tactical position based on ball zone
                    if (zoneY <= 1) return new Point(1, 0);
                    if (zoneY >= 3) return new Point(1, 4);
                    return new Point(1, zoneY);
                case "Home 3":
                    // Home 3 tactical position based on ball zone
                    if (zoneX <= 1) return new Point(0, zoneY);
                    if (zoneX >= 3) return new Point(1, zoneY);
                    return new Point(1, Math.min(4, zoneY + 1));
                default:
                    return new Point(0, 2);
            }
        } else {
            switch (playerName) {
                case "Away 1":
                    // Away 1 tactical position based on ball zone
                    if (zoneX <= 1) return new Point(3, zoneY);
                    if (zoneX >= 3) return new Point(4, zoneY);
                    return new Point(3, Math.max(0, zoneY - 1));
                case "Away 2":
                    // Away 2 tactical position based on ball zone
                    if (zoneY <= 1) return new Point(3, 0);
                    if (zoneY >= 3) return new Point(3, 4);
                    return new Point(3, zoneY);
                case "Away 3":
                    // Away 3 tactical position based on ball zone
                    if (zoneX <= 1) return new Point(3, zoneY);
                    if (zoneX >= 3) return new Point(4, zoneY);
                    return new Point(3, Math.min(4, zoneY + 1));
                default:
                    return new Point(4, 2);
            }
        }
    }
    
    private void updateThreatDetection() {
        // Process each team separately
        selectPressingPlayer(homePlayers);
        selectPressingPlayer(awayPlayers);
    }
    
    private void selectPressingPlayer(List<Player> teamPlayers) {
        // Find all players in this team who have ball in adjacent zone
        List<Player> playersInRange = new ArrayList<>();
        for (Player p : teamPlayers) {
            if (isAdjacentZone(p.currentPos, ballPos)) {
                playersInRange.add(p);
            }
        }
        
        // If multiple players in range, select only the closest one to press
        if (!playersInRange.isEmpty()) {
            Player closestPlayer = null;
            double minDistance = Double.MAX_VALUE;
            
            for (Player p : playersInRange) {
                double dist = distance(p.currentPos, ballPos);
                if (dist < minDistance) {
                    minDistance = dist;
                    closestPlayer = p;
                }
            }
            
            // Update all players in this team
            for (Player p : teamPlayers) {
                if (p == closestPlayer) {
                    // This player presses the ball
                    if (p.intent != Player.Intent.PRESS) {
                        p.intent = Player.Intent.PRESS;
                        p.desiredPos = new Point(ballPos);
                        p.reason = "Ball in adjacent zone (closest)";
                        logEvent(p.name + " - Ball in adjacent zone, Intent: PRESS");
                    }
                } else {
                    // Other players return to tactical position
                    if (p.intent == Player.Intent.PRESS) {
                        p.intent = Player.Intent.RETURN_TO_SHAPE;
                        p.desiredPos = new Point(p.tacticalPos);
                        p.reason = "Tactical Editor (teammate pressing)";
                        logEvent(p.name + " - Teammate pressing, Intent: RETURN_TO_SHAPE");
                    }
                }
            }
        } else {
            // No players in range, all return to tactical position
            for (Player p : teamPlayers) {
                if (p.intent == Player.Intent.PRESS) {
                    p.intent = Player.Intent.RETURN_TO_SHAPE;
                    p.desiredPos = new Point(p.tacticalPos);
                    p.reason = "Tactical Editor";
                    logEvent(p.name + " - Ball not adjacent, Intent: RETURN_TO_SHAPE");
                }
            }
        }
    }
    
    private boolean isAdjacentZone(Point p1, Point p2) {
        int dx = Math.abs(p1.x - p2.x);
        int dy = Math.abs(p1.y - p2.y);
        // Adjacent means dx <= 1 and dy <= 1, but not the same cell
        return (dx <= 1 && dy <= 1) && !(dx == 0 && dy == 0);
    }
    
    private double distance(Point p1, Point p2) {
        double dx = p1.x - p2.x;
        double dy = p1.y - p2.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    private void startAnimation() {
        animationTimer = new Timer(16, e -> {
            // Animate players toward desired position if enabled
            if (animateMovement) {
                for (Player p : homePlayers) {
                    animatePlayer(p);
                }
                for (Player p : awayPlayers) {
                    animatePlayer(p);
                }
            }
            
            // Update inspector if player selected
            if (selectedPlayer != null) {
                updateInspector(selectedPlayer);
            }
            
            repaint();
        });
        animationTimer.start();
    }
    
    private void animatePlayer(Player p) {
        double targetPixelX = p.desiredPos.x * CELL_SIZE + CELL_SIZE / 2;
        double targetPixelY = p.desiredPos.y * CELL_SIZE + CELL_SIZE / 2;
        double currentPixelX = p.pixelPos.x;
        double currentPixelY = p.pixelPos.y;
        
        double dx = targetPixelX - currentPixelX;
        double dy = targetPixelY - currentPixelY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        
        if (dist > ANIMATION_SPEED) {
            p.pixelPos.x += (dx / dist) * ANIMATION_SPEED;
            p.pixelPos.y += (dy / dist) * ANIMATION_SPEED;
            p.currentPos.x = (int) Math.round(p.pixelPos.x / CELL_SIZE);
            p.currentPos.y = (int) Math.round(p.pixelPos.y / CELL_SIZE);
        } else {
            p.pixelPos.x = (int) targetPixelX;
            p.pixelPos.y = (int) targetPixelY;
            p.currentPos = new Point(p.desiredPos);
        }
    }
    
    private void logEvent(String message) {
        SwingUtilities.invokeLater(() -> {
            eventLog.append("[" + System.currentTimeMillis() % 10000 + "] " + message + "\n");
            eventLog.setCaretPosition(eventLog.getDocument().getLength());
        });
    }
    
    private void updateInspector(Player p) {
        double distToBall = distance(p.currentPos, ballPos);
        String html = String.format(
            "<html><b>Name:</b> %s<br>" +
            "<b>Team:</b> %s<br>" +
            "<b>Current Position:</b> (%d, %d)<br>" +
            "<b>Desired Position:</b> (%d, %d)<br>" +
            "<b>Tactical Position:</b> (%d, %d)<br>" +
            "<b>Intent:</b> %s<br>" +
            "<b>Reason:</b> %s<br>" +
            "<b>Threat Target:</b> %s<br>" +
            "<b>Distance to Ball:</b> %.1f<br>" +
            "<b>Current Ball Zone:</b> %d</html>",
            p.name, p.team,
            p.currentPos.x, p.currentPos.y,
            p.desiredPos.x, p.desiredPos.y,
            p.tacticalPos.x, p.tacticalPos.y,
            p.intent, p.reason,
            p.threatTarget != null ? "(" + p.threatTarget.x + ", " + p.threatTarget.y + ")" : "None",
            distToBall, currentBallZone
        );
        inspectorLabel.setText(html);
    }
    
    private class DrawingPanel extends JPanel {
        public DrawingPanel() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    // Check for player selection first
                    boolean playerClicked = handlePlayerSelection(e.getPoint());
                    // Only move ball if no player was clicked
                    if (!playerClicked) {
                        handleMouseClick(e.getPoint());
                    }
                }
            });
        }
        
        private void handleMouseClick(Point click) {
            int gridX = click.x / CELL_SIZE;
            int gridY = click.y / CELL_SIZE;
            
            if (gridX < 0 || gridX >= GRID_SIZE || gridY < 0 || gridY >= GRID_SIZE) {
                return;
            }
            
            // Only Move Ball mode - primary interaction
            ballPos.x = gridX;
            ballPos.y = gridY;
            updateBallPixelPosition();
            updateTacticalPositions();
            logEvent("Ball moved to (" + gridX + ", " + gridY + ")");
            
            repaint();
        }
        
        private boolean handlePlayerSelection(Point click) {
            // Check if clicking on a player for inspection
            for (Player p : homePlayers) {
                int playerPixelX = p.pixelPos.x;
                int playerPixelY = p.pixelPos.y;
                double dist = Math.sqrt(Math.pow(click.x - playerPixelX, 2) + Math.pow(click.y - playerPixelY, 2));
                
                if (dist <= PLAYER_RADIUS + 10) {
                    selectedPlayer = p;
                    updateInspector(p);
                    logEvent("Player selected: " + p.name);
                    return true;
                }
            }
            for (Player p : awayPlayers) {
                int playerPixelX = p.pixelPos.x;
                int playerPixelY = p.pixelPos.y;
                double dist = Math.sqrt(Math.pow(click.x - playerPixelX, 2) + Math.pow(click.y - playerPixelY, 2));
                
                if (dist <= PLAYER_RADIUS + 10) {
                    selectedPlayer = p;
                    updateInspector(p);
                    logEvent("Player selected: " + p.name);
                    return true;
                }
            }
            return false;
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Draw grid
            g2d.setColor(Color.LIGHT_GRAY);
            for (int i = 0; i <= GRID_SIZE; i++) {
                g2d.drawLine(i * CELL_SIZE, 0, i * CELL_SIZE, PANEL_SIZE);
                g2d.drawLine(0, i * CELL_SIZE, PANEL_SIZE, i * CELL_SIZE);
            }
            
            // Highlight current ball zone
            int zoneX = ballPos.x * CELL_SIZE;
            int zoneY = ballPos.y * CELL_SIZE;
            g2d.setColor(new Color(255, 255, 0, 50));
            g2d.fillRect(zoneX, zoneY, CELL_SIZE, CELL_SIZE);
            g2d.setColor(Color.ORANGE);
            g2d.setStroke(new BasicStroke(3));
            g2d.drawRect(zoneX, zoneY, CELL_SIZE, CELL_SIZE);
            
            // Draw movement paths
            if (showPaths) {
                drawMovementPaths(g2d);
            }
            
            // Draw tactical targets
            if (showTacticalTargets) {
                drawTacticalTargets(g2d);
            }
            
            // Draw desired positions
            if (showDesiredPosition) {
                drawDesiredPositions(g2d);
            }
            
            // Draw threat radii
            if (showThreatRadius) {
                drawThreatRadii(g2d);
            }
            
            // Draw players
            if (showCurrentPosition) {
                drawPlayers(g2d);
            }
            
            // Draw ball
            drawBall(g2d);
        }
        
        private void drawTacticalTargets(Graphics2D g2d) {
            g2d.setColor(Color.GRAY);
            for (Player p : homePlayers) {
                int x = p.tacticalPos.x * CELL_SIZE + CELL_SIZE / 2;
                int y = p.tacticalPos.y * CELL_SIZE + CELL_SIZE / 2;
                g2d.fillOval(x - 5, y - 5, 10, 10);
            }
            for (Player p : awayPlayers) {
                int x = p.tacticalPos.x * CELL_SIZE + CELL_SIZE / 2;
                int y = p.tacticalPos.y * CELL_SIZE + CELL_SIZE / 2;
                g2d.fillOval(x - 5, y - 5, 10, 10);
            }
        }
        
        private void drawDesiredPositions(Graphics2D g2d) {
            g2d.setColor(Color.GREEN);
            for (Player p : homePlayers) {
                int x = p.desiredPos.x * CELL_SIZE + CELL_SIZE / 2;
                int y = p.desiredPos.y * CELL_SIZE + CELL_SIZE / 2;
                g2d.fillOval(x - 8, y - 8, 16, 16);
                g2d.setColor(Color.BLACK);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawOval(x - 8, y - 8, 16, 16);
                g2d.setColor(Color.GREEN);
            }
            for (Player p : awayPlayers) {
                int x = p.desiredPos.x * CELL_SIZE + CELL_SIZE / 2;
                int y = p.desiredPos.y * CELL_SIZE + CELL_SIZE / 2;
                g2d.fillOval(x - 8, y - 8, 16, 16);
                g2d.setColor(Color.BLACK);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawOval(x - 8, y - 8, 16, 16);
                g2d.setColor(Color.GREEN);
            }
        }
        
        private void drawMovementPaths(Graphics2D g2d) {
            g2d.setColor(Color.BLUE);
            g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{5, 3}, 0));
            
            for (Player p : homePlayers) {
                int currentX = p.pixelPos.x;
                int currentY = p.pixelPos.y;
                int desiredX = p.desiredPos.x * CELL_SIZE + CELL_SIZE / 2;
                int desiredY = p.desiredPos.y * CELL_SIZE + CELL_SIZE / 2;
                
                // Draw arrow from current to desired
                g2d.drawLine(currentX, currentY, desiredX, desiredY);
                
                // Draw arrow head
                double angle = Math.atan2(desiredY - currentY, desiredX - currentX);
                int arrowSize = 8;
                g2d.drawLine(desiredX, desiredY, 
                             (int)(desiredX - arrowSize * Math.cos(angle - Math.PI / 6)),
                             (int)(desiredY - arrowSize * Math.sin(angle - Math.PI / 6)));
                g2d.drawLine(desiredX, desiredY,
                             (int)(desiredX - arrowSize * Math.cos(angle + Math.PI / 6)),
                             (int)(desiredY - arrowSize * Math.sin(angle + Math.PI / 6)));
            }
            
            g2d.setColor(Color.RED);
            for (Player p : awayPlayers) {
                int currentX = p.pixelPos.x;
                int currentY = p.pixelPos.y;
                int desiredX = p.desiredPos.x * CELL_SIZE + CELL_SIZE / 2;
                int desiredY = p.desiredPos.y * CELL_SIZE + CELL_SIZE / 2;
                
                // Draw arrow from current to desired
                g2d.drawLine(currentX, currentY, desiredX, desiredY);
                
                // Draw arrow head
                double angle = Math.atan2(desiredY - currentY, desiredX - currentX);
                int arrowSize = 8;
                g2d.drawLine(desiredX, desiredY,
                             (int)(desiredX - arrowSize * Math.cos(angle - Math.PI / 6)),
                             (int)(desiredY - arrowSize * Math.sin(angle - Math.PI / 6)));
                g2d.drawLine(desiredX, desiredY,
                             (int)(desiredX - arrowSize * Math.cos(angle + Math.PI / 6)),
                             (int)(desiredY - arrowSize * Math.sin(angle + Math.PI / 6)));
            }
        }
        
        private void drawThreatRadii(Graphics2D g2d) {
            g2d.setColor(new Color(255, 0, 0, 30));
            for (Player p : homePlayers) {
                int x = (int) p.pixelPos.x;
                int y = (int) p.pixelPos.y;
                g2d.fillOval(x - p.threatRadius, y - p.threatRadius, p.threatRadius * 2, p.threatRadius * 2);
            }
            for (Player p : awayPlayers) {
                int x = (int) p.pixelPos.x;
                int y = (int) p.pixelPos.y;
                g2d.fillOval(x - p.threatRadius, y - p.threatRadius, p.threatRadius * 2, p.threatRadius * 2);
            }
        }
        
        private void drawPlayers(Graphics2D g2d) {
            // Draw home players (blue)
            for (Player p : homePlayers) {
                int x = (int) p.pixelPos.x;
                int y = (int) p.pixelPos.y;
                Color color = p == selectedPlayer ? Color.CYAN : Color.BLUE;
                drawPlayerCircle(g2d, x, y, color, p.name, p.intent);
            }
            
            // Draw away players (red)
            for (Player p : awayPlayers) {
                int x = (int) p.pixelPos.x;
                int y = (int) p.pixelPos.y;
                Color color = p == selectedPlayer ? Color.MAGENTA : Color.RED;
                drawPlayerCircle(g2d, x, y, color, p.name, p.intent);
            }
        }
        
        private void drawPlayerCircle(Graphics2D g2d, int x, int y, Color color, String name, Player.Intent intent) {
            g2d.setColor(color);
            g2d.fillOval(x - PLAYER_RADIUS, y - PLAYER_RADIUS, PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawOval(x - PLAYER_RADIUS, y - PLAYER_RADIUS, PLAYER_RADIUS * 2, PLAYER_RADIUS * 2);
            
            // Draw name
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Arial", Font.BOLD, 10));
            FontMetrics fm = g2d.getFontMetrics();
            int nameWidth = fm.stringWidth(name);
            g2d.drawString(name, x - nameWidth / 2, y + PLAYER_RADIUS + 12);
            
            // Draw intent if enabled
            if (showIntent) {
                g2d.setFont(new Font("Arial", Font.BOLD, 9));
                String intentStr = intent.toString();
                int intentWidth = fm.stringWidth(intentStr);
                g2d.drawString(intentStr, x - intentWidth / 2, y - PLAYER_RADIUS - 8);
            }
        }
        
        private void drawBall(Graphics2D g2d) {
            g2d.setColor(Color.WHITE);
            g2d.fillOval(ballPixelPos.x - BALL_RADIUS, ballPixelPos.y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawOval(ballPixelPos.x - BALL_RADIUS, ballPixelPos.y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
            
            // Pentagon pattern
            g2d.setColor(Color.BLACK);
            g2d.fillOval(ballPixelPos.x - 4, ballPixelPos.y - 4, 8, 8);
        }
        
    }
    
    /**
     * Player model for AI visualization
     */
    public static class Player {
        public enum Intent { RETURN_TO_SHAPE, PRESS }
        
        public String name;
        public String team;
        public Point currentPos;      // Grid coordinates
        public Point startPos;        // Starting position (for reset)
        public Point desiredPos;      // Grid coordinates
        public Point tacticalPos;     // Grid coordinates (from Tactical Editor)
        public Intent intent;
        public String reason;
        public int threatRadius;
        public Point threatTarget;
        public Point pixelPos;        // Pixel coordinates for rendering
        
        Player(String name, String team, Point startPos, int threatRadius) {
            this.name = name;
            this.team = team;
            this.currentPos = new Point(startPos);
            this.startPos = new Point(startPos);
            this.desiredPos = new Point(startPos);
            this.tacticalPos = new Point(startPos);
            this.intent = Intent.RETURN_TO_SHAPE;
            this.reason = "Tactical Editor";
            this.threatRadius = threatRadius;
            this.threatTarget = null;
            this.pixelPos = new Point(0, 0);
            updatePixelPosition();
        }
        
        void updatePixelPosition() {
            pixelPos.x = currentPos.x * CELL_SIZE + CELL_SIZE / 2;
            pixelPos.y = currentPos.y * CELL_SIZE + CELL_SIZE / 2;
        }
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PlayerTrackingDemo demo = new PlayerTrackingDemo();
            demo.setVisible(true);
        });
    }
}
