package org.example.footballmanager.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ball {

    // ========================
    // FIELDS
    // ========================
    private double x;           // trenutno x u pixelima
    private double y;           // trenutno y u pixelima
    private int cellX;          // cell koordinata ako koristiš grid (npr. 50x50)
    private int cellY;
    private Player owner;       // igrač koji drži loptu, null ako slobodna
    private double speed;       // brzina lopte u pikselima po tick-u
    private double height;      // visina za duge lopte (0 = tlo)

    // ========================
    // BEHAVIORAL METHODS
    // ========================

    /**
     * Premesti loptu ka x,y po trenutnoj brzini
     */
    public void moveTo(double targetX, double targetY) {
        this.x = targetX;
        this.y = targetY;

        // update cell
        this.cellX = (int) (x / 10); // primer, 50x50 grid
        this.cellY = (int) (y / 10);
    }

    /**
     * Dodaj brzinu (npr. kada se šutira)
     */
    public void applySpeed(double deltaX, double deltaY) {
        this.x += deltaX;
        this.y += deltaY;

        // update cell
        this.cellX = (int) (x / 10);
        this.cellY = (int) (y / 10);
    }

    /**
     * Prenesi loptu novom vlasniku
     */
    public void transferOwnership(Player newOwner) {
        this.owner = newOwner;
    }

    /**
     * Provera da li je lopta slobodna
     */
    public boolean isFree() {
        return this.owner == null;
    }
}
