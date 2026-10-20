package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The four national-team competitions (owner, 2026-10-06).
 *
 * <p>Senior and U-21 are <b>separate competitions, not tabs on one</b>. Two reasons: they run on the
 * same two weeks of the calendar, so sharing one competition would mean a week-12 draw that could not
 * say which level it belonged to; and a U-21 result is not evidence about a senior side.
 *
 * <p>Scope is {@link CompetitionScope#INTERNATIONAL} and team type is
 * {@link CompetitionTeamType#NATIONAL_TEAM}. International because these are competitions between
 * countries, which is also what keeps the continental club cups and these apart by scope.
 *
 * <p>Neither is created on boot: world building happens on an admin action, and a boot that created
 * four competitions would put them in a world that has not been seeded.
 */
@Component
public class NationalTeamCompetitions {

    private static final Logger log = LoggerFactory.getLogger(NationalTeamCompetitions.class);

    public static final String SENIOR_QUALIFYING = "World Cup Qualifiers";
    public static final String SENIOR_TOURNAMENT = "World Cup";
    public static final String U21_QUALIFYING = "U-21 World Cup Qualifiers";
    public static final String U21_TOURNAMENT = "U-21 World Cup";

    private final CompetitionRepository competitions;

    public NationalTeamCompetitions(CompetitionRepository competitions) {
        this.competitions = competitions;
    }

    /** The competition for one level and stage, or empty when it has not been created yet. */
    public Optional<Competition> find(NationalTeamLevel level, NationalStage stage) {
        return competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.TOURNAMENT)
                .filter(c -> c.getNationalLevel() == level)
                .filter(c -> c.getNationalStage() == stage)
                .findFirst();
    }

    /** The qualifying competition for a level, or empty. */
    public Optional<Competition> qualifiers(NationalTeamLevel level) {
        return find(level, NationalStage.QUALIFYING);
    }

    /** The tournament competition for a level, or empty. */
    public Optional<Competition> tournament(NationalTeamLevel level) {
        return find(level, NationalStage.WORLD_CUP);
    }

    public boolean exists(NationalTeamLevel level, NationalStage stage) {
        return find(level, stage).isPresent();
    }

    /**
     * Creates any of the four that are missing, and returns all four.
     *
     * <p>Idempotent by the competition row itself, matched on level and stage rather than on name, so
     * a renamed competition is found rather than duplicated.
     */
    @Transactional
    public List<Competition> ensureAll() {
        int made = 0;
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            for (NationalStage stage : List.of(NationalStage.QUALIFYING, NationalStage.WORLD_CUP)) {
                if (exists(level, stage)) {
                    continue;
                }
                competitions.save(create(level, stage));
                made++;
            }
        }
        if (made > 0) {
            log.info("National-team competitions: {} created.", made);
        }
        return competitions.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.TOURNAMENT)
                .toList();
    }

    private Competition create(NationalTeamLevel level, NationalStage stage) {
        Competition competition = new Competition();
        competition.setName(nameFor(level, stage));
        competition.setType(CompetitionType.TOURNAMENT);
        competition.setScope(CompetitionScope.INTERNATIONAL);
        competition.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
        competition.setNationalLevel(level);
        competition.setNationalStage(stage);
        competition.setTier(1);
        return competition;
    }

    /** The display name, which is a label and is never read to work out level or stage. */
    public static String nameFor(NationalTeamLevel level, NationalStage stage) {
        boolean senior = level == NationalTeamLevel.SENIOR;
        boolean qualifying = stage == NationalStage.QUALIFYING;
        if (senior) {
            return qualifying ? SENIOR_QUALIFYING : SENIOR_TOURNAMENT;
        }
        return qualifying ? U21_QUALIFYING : U21_TOURNAMENT;
    }
}