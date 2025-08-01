package org.example.footballmanager.model.tactics;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Tactics {
    private double aggression;       // 0.0 – 2.0
    private double pressing;        // 0.0 – 2.0
    private double counterAttack;   // 0.0 – 2.0
    private double ballControl;     // 0.0 – 2.0

    public static Tactics defaultBalanced() {
        return new Tactics(1.0, 1.0, 1.0, 1.0);
    }

    public void setAggression(double aggression) {
        this.aggression = Math.max(0.0, Math.min(2.0, aggression));
    }

    public void setPressing(double pressing) {
        this.pressing = Math.max(0.0, Math.min(2.0, pressing));
    }

    public void setCounterAttack(double counterAttack) {
        this.counterAttack = Math.max(0.0, Math.min(2.0, counterAttack));
    }

    public void setBallControl(double ballControl) {
        this.ballControl = Math.max(0.0, Math.min(2.0, ballControl));
    }
}