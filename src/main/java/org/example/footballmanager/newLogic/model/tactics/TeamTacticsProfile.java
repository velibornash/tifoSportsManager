package org.example.footballmanager.newLogic.model.tactics;

import jakarta.persistence.*;
import lombok.Data;
import org.example.footballmanager.newLogic.model.Team;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "team_tactics_profile", uniqueConstraints = {
        @UniqueConstraint(name = "uk_team_tactics_profile_team", columnNames = "team_id")
})
public class TeamTacticsProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false, unique = true)
    private Team team;

    @Column(nullable = false)
    private String formation;

    @Column(nullable = false)
    private String style;

    @Column(name = "rules_json", columnDefinition = "text")
    private String rulesJson;

    @Column(name = "set_pieces_json", columnDefinition = "text")
    private String setPiecesJson;

    @Column(nullable = false)
    private Long version = 1L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}