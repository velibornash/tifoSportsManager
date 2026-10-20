package org.tifo.footballmanager.newLogic.model.tactics;

import jakarta.persistence.*;
import lombok.Data;
import org.tifo.footballmanager.newLogic.model.Team;

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

    /**
     * Whether this profile is <b>the</b> tactic the whole world starts from.
     *
     * <p>Owner, 2026-10-18: <i>"da ova opcija snimi taktiku koju ću napraviti za Omladinac i da ta
     * taktika tj ti tactical rules preživi Reset db i da se stalno učitavaju nazad"</i>.
     *
     * <p>Before this flag existed there was no way to say WHICH profile was the default.
     * {@code seedDefaultTacticForEveryClub} called {@code findAll().stream().findFirst()}, which returns
     * whichever row the database happened to order first — so "give every club a default tactic" gave
     * every club an arbitrary one, and re-running it could hand out a different tactic on a rebuilt
     * database. A world-wide setting with no explicit owner is not a setting.
     *
     * <p>Exactly one row carries it; see
     * {@code TacticLibraryService#markProfileAsWorldDefault}.
     *
     * <p>It survives {@code Reset DB} because the profile table is not part of the football data that
     * reset clears, and because {@code TacticsProfileBackupService} keeps a durable copy of every
     * profile's content in {@code var/}.
     *
     * <p><b>The {@code columnDefinition} is load-bearing and must not be tidied away.</b> Written as
     * {@code nullable = false} alone, Hibernate emits
     * {@code ADD COLUMN world_default boolean not null} — which PostgreSQL rejects against a table that
     * already has rows, because they would all be NULL. The ALTER fails, the column is never created,
     * and every subsequent SELECT on this table fails with
     * {@code column ttp1_0.world_default does not exist}, taking the whole tactics editor down. That is
     * exactly what happened the first time this was deployed, on a live world.
     *
     * <p>With {@code DEFAULT false} the statement becomes
     * {@code ADD COLUMN world_default boolean not null default false}, which PostgreSQL applies by
     * back-filling every existing row. Existing profiles become "not the world default", which is the
     * truth: none of them was.
     */
    @Column(name = "world_default", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean worldDefault = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}