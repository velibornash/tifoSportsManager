package org.example.footballmanager.demo.service.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Position on the 9x8 grid (row 1-7 pitch, col 1-6 pitch).
 * Value object — no simulation logic.
 */
public class Position {

    private final double row;
    private final double column;

    public Position(double row, double column) {
        this.row = row;
        this.column = column;
    }

    public static Position zero() { return new Position(0, 0); }

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
