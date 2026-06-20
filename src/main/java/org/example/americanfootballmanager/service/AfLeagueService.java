package org.example.americanfootballmanager.service;

import org.example.americanfootballmanager.model.AfCompetitionEntry;
import org.example.americanfootballmanager.model.AfLeagueTableEntry;
import org.example.americanfootballmanager.model.AfMatch;
import org.example.americanfootballmanager.model.AfSeasonCompetition;
import org.example.americanfootballmanager.model.AfTeam;
import org.example.americanfootballmanager.repository.AfCompetitionEntryRepository;
import org.example.americanfootballmanager.repository.AfMatchRepository;
import org.example.americanfootballmanager.repository.AfSeasonCompetitionRepository;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.repository.CommonCompetitionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AfLeagueService {

    private final CommonCompetitionRepository competitionRepository;
    private final AfMatchRepository matchRepository;
    private final AfCompetitionEntryRepository competitionEntryRepository;
    private final AfSeasonCompetitionRepository seasonCompetitionRepository;

    public AfLeagueService(CommonCompetitionRepository competitionRepository,
                            AfMatchRepository matchRepository,
                            AfCompetitionEntryRepository competitionEntryRepository,
                            AfSeasonCompetitionRepository seasonCompetitionRepository) {
        this.competitionRepository = competitionRepository;
        this.matchRepository = matchRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
    }

    public List<CommonCompetition> getAllLeagues() {
        return competitionRepository.findBySport("AMERICAN_FOOTBALL");
    }

    public Optional<CommonCompetition> getLeagueById(Long id) {
        return competitionRepository.findById(id);
    }

    public List<AfLeagueTableEntry> calculateTable(Long competitionId, Integer seasonYear) {
        Optional<AfSeasonCompetition> scOpt = seasonCompetitionRepository
                .findByCompetitionIdAndSeasonSeasonYear(competitionId, seasonYear);
        if (scOpt.isEmpty()) return List.of();
        AfSeasonCompetition seasonComp = scOpt.get();

        List<AfCompetitionEntry> entries = competitionEntryRepository
                .findBySeasonCompetitionId(seasonComp.getId());

        Map<Long, AfLeagueTableEntry> entryMap = new LinkedHashMap<>();
        for (AfCompetitionEntry entry : entries) {
            AfTeam team = entry.getTeam();
            if (team == null) continue;
            AfLeagueTableEntry e = new AfLeagueTableEntry();
            e.setTeamId(team.getId());
            e.setTeamName(team.getName());
            e.setTeamShortName(team.getShortName());
            e.setTeamColor(team.getColor());
            e.setStadiumName(team.getStadiumName());
            e.setPlayed(0);
            e.setWins(0);
            e.setLosses(0);
            e.setPoints(0);
            e.setPointsFor(0);
            e.setPointsAgainst(0);
            e.setPointDiff(0);
            e.setForm(new ArrayList<>());
            entryMap.put(team.getId(), e);
        }

        List<AfMatch> matches = matchRepository
                .findByCompetitionIdAndSeasonYearAndPlayedOrderByMatchDate(competitionId, seasonYear, true);

        for (AfMatch match : matches) {
            updateEntry(entryMap, match.getHomeTeam(), match.getHomeScore(), match.getAwayScore(), match);
            updateEntry(entryMap, match.getAwayTeam(), match.getAwayScore(), match.getHomeScore(), match);
        }

        List<AfLeagueTableEntry> sorted = entryMap.values().stream()
                .sorted((a, b) -> {
                    if (!a.getPoints().equals(b.getPoints())) return b.getPoints() - a.getPoints();
                    int diffA = b.getPointDiff() - a.getPointDiff();
                    if (diffA != 0) return diffA;
                    return b.getPointsFor() - a.getPointsFor();
                })
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setPosition(i + 1);
        }

        return sorted;
    }

    private void updateEntry(Map<Long, AfLeagueTableEntry> entryMap, AfTeam team,
                              Integer scored, Integer conceded, AfMatch match) {
        AfLeagueTableEntry entry = entryMap.computeIfAbsent(team.getId(), id -> {
            AfLeagueTableEntry e = new AfLeagueTableEntry();
            e.setTeamId(team.getId());
            e.setTeamName(team.getName());
            e.setTeamShortName(team.getShortName());
            e.setTeamColor(team.getColor());
            e.setStadiumName(team.getStadiumName());
            e.setPlayed(0);
            e.setWins(0);
            e.setLosses(0);
            e.setPoints(0);
            e.setPointsFor(0);
            e.setPointsAgainst(0);
            e.setPointDiff(0);
            e.setForm(new ArrayList<>());
            return e;
        });

        entry.setPlayed(entry.getPlayed() + 1);
        entry.setPointsFor(entry.getPointsFor() + scored);
        entry.setPointsAgainst(entry.getPointsAgainst() + conceded);
        entry.setPointDiff(entry.getPointsFor() - entry.getPointsAgainst());

        if (scored > conceded) {
            entry.setWins(entry.getWins() + 1);
            entry.setPoints(entry.getPoints() + 3);
            entry.getForm().add("W");
        } else {
            entry.setLosses(entry.getLosses() + 1);
            entry.getForm().add("L");
        }

        if (entry.getForm().size() > 5) {
            entry.getForm().remove(0);
        }
    }
}
