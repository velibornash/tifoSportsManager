package org.example.footballtextmanager.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Getter @Setter
public class CTeam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Enumerated(EnumType.STRING)
    private CSCompetitionTeamType type; // CLUB ili NATIONAL
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonManagedReference
    private CSCountry csCountry;
    @ManyToOne(fetch = FetchType.LAZY)
    private CSCompetition CSCompetition;
    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "stadium_id")
    @EqualsAndHashCode.Exclude  // ⬅ isključeno da ne bi zvao CSStadium.hashCode
    @JsonBackReference
    private CSStadium csStadium;
    @OneToMany(mappedBy = "CTeam", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<CSCompetitionEntry> entries;
    private Double budget; // samo za CLUB
    private Double reputation; // 0-100
    private Integer juniorCoachSkill; // 1-100
    private boolean humanControlled;
    @OneToMany(mappedBy = "CTeam", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<CPlayer> CPlayers = new ArrayList<>();
    @OneToMany(mappedBy = "CTeam", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<CSJunior> CSJuniors = new ArrayList<>();
    public void addPlayer(CPlayer CPlayer) {
        CPlayers.add(CPlayer);
        CPlayer.setCTeam(this);
    }
    public void removePlayer(CPlayer CPlayer) {
        CPlayers.remove(CPlayer);
        CPlayer.setCTeam(null);
    }
    public double getAverageRating() {
        OptionalDouble avg = CPlayers.stream()
                .mapToInt(CPlayer::getRating)
                .average();
        return avg.orElse(0.0);
    }
    public double getAverageSkill(CSPosition CSPosition) {
        OptionalDouble avg = CPlayers.stream()
                .filter(p -> p.getCSPosition() == CSPosition)
                .mapToDouble(p -> p.getCSSkills().getRatingScore(CSPosition))
                .average();
        return avg.orElse(0.0);
    }
    public long getAvailablePlayers() {
        return CPlayers.stream()
                .filter(p -> p.getForm() > 3.0 && p.getCSSkills().getFatigue() < 8)
                .count();
    }
    public boolean isMatchReady() {
        return getAvailablePlayers() >= 11;
    }
}
