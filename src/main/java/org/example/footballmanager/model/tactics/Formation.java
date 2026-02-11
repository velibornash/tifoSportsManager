package org.example.footballmanager.model.tactics;

import jakarta.persistence.*;
import lombok.Data;
import org.example.footballmanager.model.Position;

import java.util.HashMap;
import java.util.Map;

@Data
@Entity
public class Formation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // npr. "4-3-3", "4-4-2"

    @ElementCollection
    @CollectionTable(name = "formation_positions", joinColumns = @JoinColumn(name = "formation_id"))
    @MapKeyColumn(name = "position")
    @Column(name = "count")
    @Enumerated(EnumType.STRING)
    private Map<Position, Integer> positions = new HashMap<>();

    private double offenseModifier = 1.0;
    private double defenseModifier = 1.0;
    private double possessionModifier = 1.0;

    public int getPlayersOnPosition(Position position) {
        return positions.getOrDefault(position, 0);
    }
}
