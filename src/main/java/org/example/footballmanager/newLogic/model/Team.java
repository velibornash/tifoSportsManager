package org.example.footballmanager.newLogic.model;

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
@Entity(name = "Team")
@Getter @Setter
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Enumerated(EnumType.STRING)
    private CompetitionTeamType type;
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonManagedReference
    private Country country;
    @ManyToOne(fetch = FetchType.LAZY)
    private Competition competition;
    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "stadium_id")
    @EqualsAndHashCode.Exclude
    @JsonBackReference
    private Stadium stadium;
    private Double budget;
    private Double reputation;
    private Integer juniorCoachSkill;
    private boolean humanControlled;
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

    // Fluent accessors for MatchSimulator compatibility
    public Long id() { return this.id; }
    public String name() { return this.name; }

    // --- Transient simulation state (not persisted) ---

    @Transient
    private List<Player> startingXI = new ArrayList<>();
    @Transient
    private List<Player> substitutes = new ArrayList<>();
    @Transient
    private String formation;
    @Transient
    private List<String> slotKeys = new ArrayList<>();
    @Transient
    private TacticRules tacticRules;

    public List<Player> startingXI() { return startingXI; }
    public List<Player> substitutes() { return substitutes; }
    public String formation() { return formation; }
    public List<String> slotKeys() { return slotKeys; }
    public TacticRules tacticRules() { return tacticRules; }

    public List<Player> allPlayers() {
        List<Player> all = new ArrayList<>(startingXI);
        all.addAll(substitutes);
        return all;
    }

    public double attackRating() {
        return startingXI.stream().mapToDouble(p -> p.getSkills().getRatingScore(Position.ATT)).average().orElse(0);
    }

    public double midfieldRating() {
        return startingXI.stream().mapToDouble(p -> p.getSkills().getRatingScore(Position.MID)).average().orElse(0);
    }

    public double defenseRating() {
        return startingXI.stream().mapToDouble(p -> p.getSkills().getRatingScore(Position.DEF)).average().orElse(0);
    }

    public void selectLineup(List<Player> starters, List<Player> subs) {
        this.startingXI = new ArrayList<>(starters);
        this.substitutes = new ArrayList<>(subs);
    }

    public void setTacticRules(TacticRules rules, List<String> slots) {
        this.tacticRules = rules;
        this.slotKeys = new ArrayList<>(slots);
    }

}