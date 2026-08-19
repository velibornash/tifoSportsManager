package org.example.footballmanager.demo.swingUIDemo;

import java.util.Locale;
import java.util.Objects;

/**
 * Pozicija na mrezi 9x8, izrazena koordinatom (row, column) u model koordinatama
 * (red 1 = fizički DONJI red terena, red 7 = GORNJI; kolone rastu levo na desno).
 *
 * Vrednosti ne moraju biti celi brojevi — mogu oznacavati granice celija:
 *   - (1, 3.5)  → spoj celija c1_3 i c1_4 (granica kolona 3/4)
 *   - (4.5, 2)  → linija izmedju redova 4 i 5
 *   - (4, 3.075)→ pored lopte u centru (visak od 34px od spoja c4_3/c4_4)
 *
 * Ovo je iskljucivo podatkovni tip (value object), bez ikakve logike.
 */
public class Position {

    private final double row;
    private final double column;

    public Position(double row, double column) {
        this.row = row;
        this.column = column;
    }

    public double getRow() {
        return row;
    }

    public double getColumn() {
        return column;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position other = (Position) o;
        return Double.compare(row, other.row) == 0
                && Double.compare(column, other.column) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(row, column);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "(%.2f,%.2f)", row, column);
    }
}
