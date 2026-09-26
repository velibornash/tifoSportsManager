package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity(name = "Stadium")
public class Stadium {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Integer capacity;
    private String location;
    @ManyToOne(fetch = FetchType.LAZY)
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team owner;

    /**
     * Face value of a standard adult ticket, in euros. Gate income is attendance multiplied by
     * this, so it is a direct lever on the club's weekly income and the manager's main stadium
     * control.
     */
    private Double ticketPrice;

    /**
     * Long-term intrinsic quality of the surface (0-100): the quality of the grass and the
     * facilities that cannot be fixed by spending this week. It decays with use and is restored
     * by maintenance, which is why it is separate from {@link #pitchCondition}.
     */
    private Double pitchQuality;

    /**
     * Current condition of the playing surface (0-100). Drops with every match played on it and
     * recovers only through maintenance spending. This is what the match engine and the crowd
     * can feel, so it moves every week rather than being a fixed profile number.
     */
    private Integer pitchCondition;

    /**
     * How much of the current maintenance programme is still funded, 0-100. The club sets a
     * maintenance budget; each week that budget is spent against pitch wear, and whatever is left
     * of the programme carries into next week. At zero the pitch keeps degrading.
     */
    private Integer maintenanceRemaining;

    private Integer condition;
    private Integer trainingQuality;

    @OneToOne(mappedBy = "stadium")
    @EqualsAndHashCode.Exclude
    @JsonManagedReference
    private Team team;
}