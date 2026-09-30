package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "Team")
@Table(indexes = {
        @Index(name = "ix_team_name_prefix", columnList = "name"),
        @Index(name = "ix_team_competition", columnList = "competition_id")
})
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

    /**
     * Whether this club runs a junior school (Sprint 5.3a, owner 2026-09-27).
     *
     * <p><b>The academy is a purchase, not a default.</b> Before this, every one of the 310 clubs
     * rolled an intake every season for free whether a human managed it or not. It now has to be
     * switched on in week 1 and paid for, and switched off in week 12 — which is what turns it from a
     * background process into a decision.
     *
     * <p><b>No AI club has one.</b> Bot squads keep the same players and train at the default pace.
     * A club with no academy has to buy its way to young players, which is the entire reason the
     * scouting network and the transfer market matter.
     *
     * <p>Nullable means "never had one", which is the same as {@code false} for behaviour but not for
     * reporting: a club that has never opened a school is different from one that closed it, and the
     * distinction is what a manager reads to understand why his intake stopped.
     */
    @Column(name = "junior_school_active")
    private Boolean juniorSchoolActive;

    /** The season the school was last switched on, so a report can say how long it has run. */
    @Column(name = "junior_school_since_season")
    private Integer juniorSchoolSinceSeason;

    /**
     * Dressing-room cohesion, 0-100 (Sprint 4.6).
     *
     * <p>How settled this squad is: the same faces having played together. Grows a little every week
     * the squad is stable and falls when players arrive, so a club that rebuilds annually never quite
     * gets settled. Worth a couple of percent on the pitch and a few on the training ground, which is
     * seasoning rather than a second growth system.
     *
     * <p><b>Nullable, deliberately.</b> {@code 0} is a legal value — a completely shattered dressing
     * room is a real state and it must be expressible — so it cannot double as the "never measured"
     * sentinel. Null is that sentinel, and reads as 50: an average, unremarkable dressing room, so a
     * club that has never been measured does not spend a season looking broken. This is the same
     * lesson as the training facilities, where a null that defaulted to level 1 would have quietly
     * penalised every club in the database.
     */
    @ColumnDefault("50")
    private Double cohesion = 50.0;

    /**
     * Path to the club badge, served from {@code static/images} — the only persisted image field in
     * the football domain.
     *
     * <p>Until this existed the Omladinac badge was picked by a hardcoded {@code name.contains()}
     * check in {@code TeamController} and {@code dashboard.js}, which {@code expertAudit.md} already
     * flags as a defect. That approach has no room for a second club badge, and the second
     * human-managed club is exactly the case it breaks on. A column costs nothing and {@code
     * ddl-auto=update} adds it, so the alternative was a code edit per logo.
     *
     * <p>Null means "no badge" and the UI falls back to its default image. It is deliberately not
     * defaulted to the default image path: storing a path in a column so the code can ignore it is
     * the same stored-and-unused pattern this column was added to fix.
     */
    private String logoUrl;
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