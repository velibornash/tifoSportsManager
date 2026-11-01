package org.tifo.americanfootballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.tifo.americanfootballmanager.model.*;
import org.tifo.americanfootballmanager.repository.*;
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
public class AfSeasonService {

    private final CommonCompetitionRepository competitionRepository;
    private final CommonSeasonRepository seasonRepository;
    private final AfTeamRepository teamRepository;
    private final AfPlayerRepository playerRepository;
    private final AfSeasonCompetitionRepository seasonCompetitionRepository;
    private final AfCompetitionEntryRepository competitionEntryRepository;
    private final AfMatchFixtureRepository fixtureRepository;
    private final AfMatchRepository matchRepository;
    private final AfPlayerSeasonStatsRepository seasonStatsRepository;
    private final AfInboxService inboxService;
    private final Random random = new Random();

    public AfSeasonService(CommonCompetitionRepository competitionRepository,
                           CommonSeasonRepository seasonRepository,
                           AfTeamRepository teamRepository,
                           AfPlayerRepository playerRepository,
                           AfSeasonCompetitionRepository seasonCompetitionRepository,
                           AfCompetitionEntryRepository competitionEntryRepository,
                           AfMatchFixtureRepository fixtureRepository,
                           AfMatchRepository matchRepository,
                           AfPlayerSeasonStatsRepository seasonStatsRepository,
                           AfInboxService inboxService) {
        this.competitionRepository = competitionRepository;
        this.seasonRepository = seasonRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.fixtureRepository = fixtureRepository;
        this.matchRepository = matchRepository;
        this.seasonStatsRepository = seasonStatsRepository;
        this.inboxService = inboxService;
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

        List<CommonCompetition> competitions = competitionRepository.findBySport("AMERICAN_FOOTBALL");

        for (CommonCompetition comp : competitions) {
            List<AfTeam> compTeams = teamRepository.findAll().stream()
                    .filter(t -> t.getCompetition() != null && t.getCompetition().getId().equals(comp.getId()))
                    .collect(Collectors.toList());
            if (compTeams.isEmpty()) continue;

            AfSeasonCompetition sc = AfSeasonCompetition.builder()
                    .competition(comp).season(newSeason).finished(false).build();
            sc = seasonCompetitionRepository.save(sc);

            for (AfTeam team : compTeams) {
                competitionEntryRepository.save(AfCompetitionEntry.builder()
                        .seasonCompetition(sc).team(team).points(0).pointsScored(0)
                        .pointsConceded(0).wins(0).losses(0).build());
            }

            generateFixtures(comp, newSeason, compTeams);
        }

        for (AfPlayer p : playerRepository.findAll()) {
            p.setFatigue(0); // if field exists? AfPlayer has skillStamina not fatigue. Check.
            playerRepository.save(p);
        }

        for (AfTeam t : teamRepository.findAll()) {
            try { inboxService.addMessage(t.getId(), "WORLD", "New season", "Season " + newYear + " has started.", newYear); } catch (Exception ignored) { }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("previousSeason", currentYear);
        resp.put("newSeason", newYear);
        return resp;
    }

    private void generateFixtures(CommonCompetition comp, CommonSeason season, List<AfTeam> teams) {
        int numTeams = teams.size();
        LocalDateTime baseDate = LocalDateTime.of(season.getSeasonYear(), 9, 15, 18, 0);
        List<AfTeam> rotated = new ArrayList<>(teams);
        for (int round = 0; round < numTeams - 1; round++) {
            for (int i = 0; i < numTeams / 2; i++) {
                AfTeam home = rotated.get(i);
                AfTeam away = rotated.get(numTeams - 1 - i);
                if (random.nextBoolean()) { AfTeam t = home; home = away; away = t; }
                saveFixture(home, away, comp, season, round + 1, baseDate.plusDays(round * 7L));
                saveFixture(away, home, comp, season, round + 1 + (numTeams - 1), baseDate.plusDays((round + numTeams) * 7L));
            }
            AfTeam first = rotated.get(0);
            List<AfTeam> rest = new ArrayList<>(rotated.subList(1, rotated.size()));
            rotated = new ArrayList<>();
            rotated.add(first);
            rotated.add(rest.get(rest.size() - 1));
            rotated.addAll(rest.subList(0, rest.size() - 1));
        }
    }

    private void saveFixture(AfTeam home, AfTeam away, CommonCompetition comp, CommonSeason season, int round, LocalDateTime date) {
        fixtureRepository.save(AfMatchFixture.builder()
                .homeTeam(home).awayTeam(away).competition(comp)
                .seasonYear(season.getSeasonYear()).roundNumber(round).weekNumber(round)
                .matchDate(date).played(false).build());
    }
}
