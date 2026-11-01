package org.tifo.basketballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.tifo.basketballmanager.model.*;
import org.tifo.basketballmanager.repository.*;
import org.tifo.commonmanager.model.CommonCompetition;
import org.tifo.commonmanager.model.CommonSeason;
import org.tifo.commonmanager.repository.CommonCompetitionRepository;
import org.tifo.commonmanager.repository.CommonSeasonRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BbSeasonService {

    private final CommonCompetitionRepository competitionRepository;
    private final CommonSeasonRepository seasonRepository;
    private final BbTeamRepository teamRepository;
    private final BbPlayerRepository playerRepository;
    private final BbSeasonCompetitionRepository seasonCompetitionRepository;
    private final BbCompetitionEntryRepository competitionEntryRepository;
    private final BbMatchFixtureRepository fixtureRepository;
    private final BbMatchRepository matchRepository;
    private final BbPlayerSeasonStatsRepository seasonStatsRepository;
    private final Random random = new Random();

    public BbSeasonService(CommonCompetitionRepository competitionRepository,
                           CommonSeasonRepository seasonRepository,
                           BbTeamRepository teamRepository,
                           BbPlayerRepository playerRepository,
                           BbSeasonCompetitionRepository seasonCompetitionRepository,
                           BbCompetitionEntryRepository competitionEntryRepository,
                           BbMatchFixtureRepository fixtureRepository,
                           BbMatchRepository matchRepository,
                           BbPlayerSeasonStatsRepository seasonStatsRepository) {
        this.competitionRepository = competitionRepository;
        this.seasonRepository = seasonRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchRepository = matchRepository;
        this.seasonStatsRepository = seasonStatsRepository;
    }

    public int getCurrentSeasonYear() {
        return seasonCompetitionRepository.findAll().stream()
                .map(sc -> sc.getSeason() != null ? sc.getSeason().getSeasonYear() : null)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(2025);
    }

    @Transactional
    public Map<String, Object> startNewSeason() {
        int currentYear = getCurrentSeasonYear();
        int newYear = currentYear + 1;

        CommonSeason newSeason = seasonRepository.findBySeasonYear(newYear)
                .orElseGet(() -> seasonRepository.save(CommonSeason.builder()
                        .seasonYear(newYear).description("Season " + newYear).build()));

        List<CommonCompetition> competitions = competitionRepository.findBySport("BASKETBALL");

        for (CommonCompetition comp : competitions) {
            List<BbTeam> compTeams = teamRepository.findAll().stream()
                    .filter(t -> t.getCompetition() != null && t.getCompetition().getId().equals(comp.getId()))
                    .collect(Collectors.toList());
            if (compTeams.isEmpty()) continue;

            BbSeasonCompetition sc = BbSeasonCompetition.builder()
                    .competition(comp).season(newSeason).finished(false).build();
            sc = seasonCompetitionRepository.save(sc);

            for (BbTeam team : compTeams) {
                competitionEntryRepository.save(BbCompetitionEntry.builder()
                        .seasonCompetition(sc).team(team).points(0).pointsScored(0)
                        .pointsConceded(0).wins(0).losses(0).build());
            }

            generateFixtures(comp, newSeason, compTeams);
        }

        // Reset player fatigue/injury (career stats kept)
        for (BbPlayer p : playerRepository.findAll()) {
            p.setFatigue(0);
            p.setInjured(false);
            playerRepository.save(p);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("previousSeason", currentYear);
        resp.put("newSeason", newYear);
        return resp;
    }

    private void generateFixtures(CommonCompetition comp, CommonSeason season, List<BbTeam> teams) {
        int numTeams = teams.size();
        LocalDateTime baseDate = LocalDateTime.of(season.getSeasonYear(), 9, 15, 18, 0);
        List<BbTeam> rotated = new ArrayList<>(teams);
        for (int round = 0; round < numTeams - 1; round++) {
            for (int i = 0; i < numTeams / 2; i++) {
                BbTeam home = rotated.get(i);
                BbTeam away = rotated.get(numTeams - 1 - i);
                if (random.nextBoolean()) { BbTeam t = home; home = away; away = t; }
                saveFixture(home, away, comp, season, round + 1, baseDate.plusDays(round * 7L));
                saveFixture(away, home, comp, season, round + 1 + (numTeams - 1), baseDate.plusDays((round + numTeams) * 7L));
            }
            BbTeam first = rotated.get(0);
            List<BbTeam> rest = new ArrayList<>(rotated.subList(1, rotated.size()));
            rotated = new ArrayList<>();
            rotated.add(first);
            rotated.add(rest.get(rest.size() - 1));
            rotated.addAll(rest.subList(0, rest.size() - 1));
        }
    }

    private void saveFixture(BbTeam home, BbTeam away, CommonCompetition comp, CommonSeason season, int round, LocalDateTime date) {
        fixtureRepository.save(BbMatchFixture.builder()
                .homeTeam(home).awayTeam(away).competition(comp)
                .seasonYear(season.getSeasonYear()).roundNumber(round).weekNumber(round)
                .matchDate(date).played(false).build());
    }
}