package org.example.basketballmanager.service;

import org.example.basketballmanager.model.BbCompetitionEntry;
import org.example.basketballmanager.model.BbLeagueTableEntry;
import org.example.basketballmanager.model.BbMatch;
import org.example.basketballmanager.model.BbSeasonCompetition;
import org.example.basketballmanager.model.BbTeam;
import org.example.basketballmanager.repository.BbCompetitionEntryRepository;
import org.example.basketballmanager.repository.BbMatchRepository;
import org.example.basketballmanager.repository.BbSeasonCompetitionRepository;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.repository.CommonCompetitionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BbLeagueService {

    private final CommonCompetitionRepository competitionRepository;
    private final BbMatchRepository matchRepository;
    private final BbCompetitionEntryRepository competitionEntryRepository;
    private final BbSeasonCompetitionRepository seasonCompetitionRepository;

    public BbLeagueService(CommonCompetitionRepository competitionRepository,
                           BbMatchRepository matchRepository,
                           BbCompetitionEntryRepository competitionEntryRepository,
                           BbSeasonCompetitionRepository seasonCompetitionRepository) {
        this.competitionRepository = competitionRepository;
        this.matchRepository = matchRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
    }

    public List<CommonCompetition> getAllLeagues() {
        return competitionRepository.findBySport("BASKETBALL");
    }

    public Optional<CommonCompetition> getLeagueById(Long id) {
        return competitionRepository.findById(id);
    }

    public List<BbLeagueTableEntry> calculateTable(Long competitionId, Integer seasonYear) {
        // Find the season competition
        Optional<BbSeasonCompetition> scOpt = seasonCompetitionRepository
                .findByCompetitionIdAndSeasonSeasonYear(competitionId, seasonYear);
        if (scOpt.isEmpty()) return List.of();
        BbSeasonCompetition seasonComp = scOpt.get();

        // Pre-populate with all teams from competition entries (even with 0 matches)
        List<BbCompetitionEntry> entries = competitionEntryRepository
                .findBySeasonCompetitionId(seasonComp.getId());

        Map<Long, BbLeagueTableEntry> entryMap = new LinkedHashMap<>();
        for (BbCompetitionEntry entry : entries) {
            BbTeam team = entry.getTeam();
            if (team == null) continue;
            BbLeagueTableEntry e = new BbLeagueTableEntry();
            e.setTeamId(team.getId());
            e.setTeamName(team.getName());
            e.setTeamShortName(team.getShortName());
            e.setTeamColor(team.getColor());
            e.setHallName(team.getHallName());
            e.setPlayed(0);
            e.setWins(0);
            e.setLosses(0);
            e.setPoints(0);
            e.setPointsFor(0);
            e.setPointsAgainst(0);
            e.setPointDiff(0);
            e.setForm(new java.util.ArrayList<>());
            entryMap.put(team.getId(), e);
        }

        // Overlay played match data
        List<BbMatch> matches = matchRepository
                .findByCompetitionIdAndSeasonYearAndPlayedOrderByMatchDate(competitionId, seasonYear, true);

        for (BbMatch match : matches) {
            updateEntry(entryMap, match.getHomeTeam(), match.getHomeScore(), match.getAwayScore(), match);
            updateEntry(entryMap, match.getAwayTeam(), match.getAwayScore(), match.getHomeScore(), match);
        }

        // Sort and assign positions
        List<BbLeagueTableEntry> sorted = entryMap.values().stream()
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

    private void updateEntry(Map<Long, BbLeagueTableEntry> entryMap, BbTeam team,
                             Integer scored, Integer conceded, BbMatch match) {
        BbLeagueTableEntry entry = entryMap.computeIfAbsent(team.getId(), id -> {
            BbLeagueTableEntry e = new BbLeagueTableEntry();
            e.setTeamId(team.getId());
            e.setTeamName(team.getName());
            e.setTeamShortName(team.getShortName());
            e.setTeamColor(team.getColor());
            e.setHallName(team.getHallName());
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
