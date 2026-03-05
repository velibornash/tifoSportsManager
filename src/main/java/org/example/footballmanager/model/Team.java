package org.example.footballmanager.model;

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
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Enumerated(EnumType.STRING)
    private CompetitionTeamType type; // CLUB ili NATIONAL
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonManagedReference
    private Country country;
    @ManyToOne(fetch = FetchType.LAZY)
    private Competition competition;
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "stadium_id")
    @EqualsAndHashCode.Exclude  // ⬅ isključeno da ne bi zvao Stadium.hashCode
    @JsonBackReference
    private Stadium stadium;
    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<CompetitionEntry> entries;
    private Double budget; // samo za CLUB
    private Double reputation; // 0-100
    private Integer juniorCoachSkill; // 1-100
    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Player> players = new ArrayList<>();
    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Junior> juniors = new ArrayList<>();
    public void addPlayer(Player player) {
        players.add(player);
        player.setTeam(this);
    }
    public void removePlayer(Player player) {
        players.remove(player);
        player.setTeam(null);
    }
    public double getAverageRating() {
        OptionalDouble avg = players.stream()
                .mapToInt(Player::getRating)
                .average();
        return avg.orElse(0.0);
    }
    public double getAverageSkill(Position position) {
        OptionalDouble avg = players.stream()
                .filter(p -> p.getPosition() == position)
                .mapToDouble(p -> p.getSkills().getRatingScore(position))
                .average();
        return avg.orElse(0.0);
    }
    public long getAvailablePlayers() {
        return players.stream()
                .filter(p -> p.getForm() > 3.0 && p.getSkills().getFatigue() < 8)
                .count();
    }
    public boolean isMatchReady() {
        return getAvailablePlayers() >= 11;
    }
}
