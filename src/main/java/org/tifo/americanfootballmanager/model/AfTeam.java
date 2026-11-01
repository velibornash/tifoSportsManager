package org.tifo.americanfootballmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.tifo.commonmanager.model.CommonCompetition;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "af_teams")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 10)
    private String shortName;

    @Column(length = 100)
    private String city;

    @Column(name = "stadium_name", length = 100)
    private String stadiumName;

    @Column(name = "stadium_capacity")
    private Integer stadiumCapacity;

    @Column(length = 7)
    private String color;

    @Column(name = "human_controlled")
    @Builder.Default
    private Boolean humanControlled = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "competition_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CommonCompetition competition;

    @OneToOne(mappedBy = "americanFootballTeam")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private org.tifo.commonmanager.model.User user;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AfPlayer> players = new ArrayList<>();

    @Column(name = "budget", columnDefinition = "bigint not null default 5000000")
    @Builder.Default
    private Long budget = 5_000_000L;

    @Column(name = "play_style", length = 16, columnDefinition = "varchar(16) not null default 'BALANCED'")
    @Builder.Default
    private String playStyle = "BALANCED";

    @Column(name = "starter_ids", length = 500)
    private String starterIds;

    @Column(name = "training_focus", length = 16, columnDefinition = "varchar(16) not null default 'BALANCED'")
    @Builder.Default
    private String trainingFocus = "BALANCED";

    public double getAverageOverall() {
        if (players.isEmpty()) return 0;
        return players.stream().mapToInt(AfPlayer::getOverall).average().orElse(0);
    }
}
