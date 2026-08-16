package org.example.footballmanager.demo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * BallTrackingDemo - Demonstracija praćenja loptice
 * 
 * LOGIKA PRAĆENJA:
 * ----------------
 * Plava loptica prati crvenu koristeći sledeći algoritam:
 * 
 * 1. Računa se vektor od plave ka crvenoj loptici:
 *    dx = crvenaX - plavaX
 *    dy = crvenaY - plavaY
 * 
 * 2. Računa se distance između loptica:
 *    distance = sqrt(dx² + dy²)
 * 
 * 3. Ako je distance > 0, normalizuje se vektor (da bi brzina bila konstantna):
 *    normalizedX = dx / distance
 *    normalizedY = dy / distance
 * 
 * 4. Plava loptica se pomera za fiksni korak (SPEED) u smeru ka crvenoj:
 *    plavaX += normalizedX * SPEED
 *    plavaY += normalizedY * SPEED
 * 
 * 5. Ovo se ponavlja svakih 16ms (≈60 FPS) kroz javax.swing.Timer
 * 
 * 6. Kada korisnik prevuče crvenu lopticu, promena se odmah reflektuje
 *    u sledećem tick-u timera, pa plava loptica automatski menja pravac.
 * 
 * PREBACIVANJE CILJA (ZELENA LOPTICA):
 * -----------------------------------
 * 7. Ako plava loptica dodje na jednu kockicu od zelene (distance <= CELL_SIZE),
 *    ona prebacuje cilj sa crvene na zelenu lopticu.
 * 8. Nakon toga plava loptica prati zelenu umesto crvene.
 */
public class BallTrackingDemo extends JFrame {
    
    private static final int GRID_SIZE = 5;
    private static final int CELL_SIZE = 100;
    private static final int PANEL_SIZE = GRID_SIZE * CELL_SIZE;
    private static final int BALL_RADIUS = 20;
    private static final double BLUE_SPEED = 2.0; // Brzina plave loptice po tick-u
    
    // Pozicije loptica (u koordinatama grid-a, 0-4)
    private Point redBall = new Point(0, 0);      // Gornji levi ugao
    private Point blueBall = new Point(4, 4);     // Donji desni ugao
    private Point greenBall = new Point(4, 0);    // Donji levi ugao
    
    // Pozicije za crtanje (u pikselima)
    private Point redPixelPos = new Point(0, 0);
    private Point bluePixelPos = new Point(0, 0);
    private Point greenPixelPos = new Point(0, 0);
    
    // Za drag and drop
    private boolean draggingRed = false;
    private boolean draggingGreen = false;
    private Point dragOffset = new Point();
    
    // Trenutni cilj plave loptice (true = crvena, false = zelena)
    private boolean targetIsRed = true;
    
    // Timer za animaciju plave loptice
    private Timer animationTimer;
    
    public BallTrackingDemo() {
        setTitle("Ball Tracking Demo - Plana prati crvenu/zelenu");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        
        // Inicijalizuj piksel pozicije
        updatePixelPositions();
        
        // Kreiraj panel za crtanje
        DrawingPanel panel = new DrawingPanel();
        panel.setPreferredSize(new Dimension(PANEL_SIZE, PANEL_SIZE));
        
        // Dodaj mouse listener za drag and drop
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point clickPos = e.getPoint();
                // Proveri da li je kliknuto na crvenu lopticu
                int redCenterX = redPixelPos.x + CELL_SIZE / 2;
                int redCenterY = redPixelPos.y + CELL_SIZE / 2;
                double distRed = Math.sqrt(Math.pow(clickPos.x - redCenterX, 2) + Math.pow(clickPos.y - redCenterY, 2));
                
                // Proveri da li je kliknuto na zelenu lopticu
                int greenCenterX = greenPixelPos.x + CELL_SIZE / 2;
                int greenCenterY = greenPixelPos.y + CELL_SIZE / 2;
                double distGreen = Math.sqrt(Math.pow(clickPos.x - greenCenterX, 2) + Math.pow(clickPos.y - greenCenterY, 2));
                
                if (distRed <= BALL_RADIUS + 5) {
                    draggingRed = true;
                    dragOffset.x = clickPos.x - redPixelPos.x;
                    dragOffset.y = clickPos.y - redPixelPos.y;
                } else if (distGreen <= BALL_RADIUS + 5) {
                    draggingGreen = true;
                    dragOffset.x = clickPos.x - greenPixelPos.x;
                    dragOffset.y = clickPos.y - greenPixelPos.y;
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggingRed) {
                    draggingRed = false;
                    // Zaokruži na najbližu grid poziciju
                    redBall.x = Math.max(0, Math.min(GRID_SIZE - 1, (int) Math.round(redPixelPos.x / (double) CELL_SIZE)));
                    redBall.y = Math.max(0, Math.min(GRID_SIZE - 1, (int) Math.round(redPixelPos.y / (double) CELL_SIZE)));
                    updatePixelPositions();
                } else if (draggingGreen) {
                    draggingGreen = false;
                    // Zaokruži na najbližu grid poziciju
                    greenBall.x = Math.max(0, Math.min(GRID_SIZE - 1, (int) Math.round(greenPixelPos.x / (double) CELL_SIZE)));
                    greenBall.y = Math.max(0, Math.min(GRID_SIZE - 1, (int) Math.round(greenPixelPos.y / (double) CELL_SIZE)));
                    updatePixelPositions();
                }
            }
        });
        
        panel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggingRed) {
                    redPixelPos.x = e.getX() - dragOffset.x;
                    redPixelPos.y = e.getY() - dragOffset.y;
                    
                    // Ograniči unutar panela
                    redPixelPos.x = Math.max(0, Math.min(PANEL_SIZE - CELL_SIZE, redPixelPos.x));
                    redPixelPos.y = Math.max(0, Math.min(PANEL_SIZE - CELL_SIZE, redPixelPos.y));
                    
                    panel.repaint();
                } else if (draggingGreen) {
                    greenPixelPos.x = e.getX() - dragOffset.x;
                    greenPixelPos.y = e.getY() - dragOffset.y;
                    
                    // Ograniči unutar panela
                    greenPixelPos.x = Math.max(0, Math.min(PANEL_SIZE - CELL_SIZE, greenPixelPos.x));
                    greenPixelPos.y = Math.max(0, Math.min(PANEL_SIZE - CELL_SIZE, greenPixelPos.y));
                    
                    panel.repaint();
                }
            }
        });
        
        add(panel);
        pack();
        setLocationRelativeTo(null);
        
        // Pokreni timer za animaciju plave loptice
        startAnimation();
    }
    
    private void updatePixelPositions() {
        redPixelPos.x = redBall.x * CELL_SIZE;
        redPixelPos.y = redBall.y * CELL_SIZE;
        bluePixelPos.x = blueBall.x * CELL_SIZE;
        bluePixelPos.y = blueBall.y * CELL_SIZE;
        greenPixelPos.x = greenBall.x * CELL_SIZE;
        greenPixelPos.y = greenBall.y * CELL_SIZE;
    }
    
    private void startAnimation() {
        animationTimer = new Timer(16, e -> {
            // Centri loptica u pikselima
            double redCenterX = redPixelPos.x + CELL_SIZE / 2.0;
            double redCenterY = redPixelPos.y + CELL_SIZE / 2.0;
            double blueCenterX = bluePixelPos.x + CELL_SIZE / 2.0;
            double blueCenterY = bluePixelPos.y + CELL_SIZE / 2.0;
            double greenCenterX = greenPixelPos.x + CELL_SIZE / 2.0;
            double greenCenterY = greenPixelPos.y + CELL_SIZE / 2.0;
            
            // Proveri da li je plava blizu zelene (jedna kockica)
            double distToGreen = Math.sqrt(Math.pow(greenCenterX - blueCenterX, 2) + Math.pow(greenCenterY - blueCenterY, 2));
            if (distToGreen <= CELL_SIZE) {
                targetIsRed = false; // Prebaci na zelenu
            } else {
                targetIsRed = true; // Vrati na crvenu ako zelena izađe iz blizine
            }
            
            // Odredi cilj (crvena ili zelena)
            double targetX = targetIsRed ? redCenterX : greenCenterX;
            double targetY = targetIsRed ? redCenterY : greenCenterY;
            
            // 1. Računanje vektora od plave ka cilju
            double dx = targetX - blueCenterX;
            double dy = targetY - blueCenterY;
            
            // 2. Računanje distance
            double distance = Math.sqrt(dx * dx + dy * dy);
            
            // 3. Ako je dovoljno daleko, pomeri plavu lopticu
            if (distance > BLUE_SPEED) {
                // Normalizuj vektor i pomeri
                bluePixelPos.x += (dx / distance) * BLUE_SPEED;
                bluePixelPos.y += (dy / distance) * BLUE_SPEED;
                
                // Ažuriraj grid poziciju plave loptice
                blueBall.x = Math.max(0, Math.min(GRID_SIZE - 1, (int) Math.round(bluePixelPos.x / (double) CELL_SIZE)));
                blueBall.y = Math.max(0, Math.min(GRID_SIZE - 1, (int) Math.round(bluePixelPos.y / (double) CELL_SIZE)));
            }
            
            repaint();
        });
        animationTimer.start();
    }
    
    private class DrawingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Nacrtaj grid
            g2d.setColor(Color.LIGHT_GRAY);
            for (int i = 0; i <= GRID_SIZE; i++) {
                g2d.drawLine(i * CELL_SIZE, 0, i * CELL_SIZE, PANEL_SIZE);
                g2d.drawLine(0, i * CELL_SIZE, PANEL_SIZE, i * CELL_SIZE);
            }
            
            // Nacrtaj crveni X znak
            drawX(g2d, redPixelPos.x + CELL_SIZE / 2, redPixelPos.y + CELL_SIZE / 2, Color.RED);
            
            // Nacrtaj plavog fudbalera
            drawPlayer(g2d, bluePixelPos.x + CELL_SIZE / 2, bluePixelPos.y + CELL_SIZE / 2, Color.BLUE);
            
            // Nacrtaj zelenu loptu
            drawBall(g2d, greenPixelPos.x + CELL_SIZE / 2, greenPixelPos.y + CELL_SIZE / 2, Color.GREEN);
            
            // Dodaj labelu za instrukcije
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            g2d.drawString("Prevuci CRVENU ili ZELENU lopticu - plava prati crvenu, ali ako dodje blizu zelene prebaci cilj", 10, PANEL_SIZE - 10);
        }
    }
    
    /**
     * Nacrtaj X znak na datoj poziciji
     */
    private void drawX(Graphics2D g2d, int centerX, int centerY, Color color) {
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(4));
        int size = 25;
        g2d.drawLine(centerX - size, centerY - size, centerX + size, centerY + size);
        g2d.drawLine(centerX + size, centerY - size, centerX - size, centerY + size);
    }
    
    /**
     * Nacrtaj fudbalsku loptu na datoj poziciji
     */
    private void drawBall(Graphics2D g2d, int centerX, int centerY, Color color) {
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2));
        
        // Glavni krug lopte
        int ballRadius = 18;
        g2d.fillOval(centerX - ballRadius, centerY - ballRadius, ballRadius * 2, ballRadius * 2);
        g2d.setColor(Color.BLACK);
        g2d.drawOval(centerX - ballRadius, centerY - ballRadius, ballRadius * 2, ballRadius * 2);
        
        // Pentagoni na lopti (klasični fudbalski dizajn)
        g2d.setColor(Color.WHITE);
        int pentagonSize = 6;
        g2d.fillOval(centerX - pentagonSize, centerY - pentagonSize, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX - 12, centerY - 5, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX + 6, centerY - 5, pentagonSize * 2, pentagonSize * 2);
        g2d.fillOval(centerX - 6, centerY + 8, pentagonSize * 2, pentagonSize * 2);
    }
    
    /**
     * Nacrtaj stick figure fudbalera na datoj poziciji
     */
    private void drawPlayer(Graphics2D g2d, int centerX, int centerY, Color color) {
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2));
        
        // Glava (krug)
        int headRadius = 8;
        g2d.fillOval(centerX - headRadius, centerY - 35, headRadius * 2, headRadius * 2);
        g2d.setColor(Color.BLACK);
        g2d.drawOval(centerX - headRadius, centerY - 35, headRadius * 2, headRadius * 2);
        
        // Telo (linija)
        g2d.setColor(color);
        g2d.drawLine(centerX, centerY - 25, centerX, centerY);
        
        // Ruke (linije)
        g2d.drawLine(centerX - 15, centerY - 15, centerX + 15, centerY - 15);
        
        // Noge (linije)
        g2d.drawLine(centerX, centerY, centerX - 10, centerY + 20);
        g2d.drawLine(centerX, centerY, centerX + 10, centerY + 20);
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            BallTrackingDemo demo = new BallTrackingDemo();
            demo.setVisible(true);
        });
    }
}
