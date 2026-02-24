package org.example.footballmanager;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.old.MatchService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;
    private final LineupRepository lineupRepository;
    private final MatchService matchService;

    @Override
    @Transactional
    public void run(String... args) {
/*
        Team homeTeam = new Team();
        homeTeam.setName("Omladinac");

        Team awayTeam = new Team();
        awayTeam.setName("Sremac");

        List<Player> homePlayers = new ArrayList<>();
        List<Player> awayPlayers = new ArrayList<>();

        homePlayers = PlayerFactory.createOmladinacPlayers(homeTeam);
        teamRepository.save(homeTeam);
        awayPlayers = PlayerFactory.createRandomTeamPlayers("Sremac", awayTeam);
        teamRepository.save(awayTeam);

        Lineup homeLineup = new Lineup();
        homeLineup.setTeam(homeTeam);
        homeLineup.setStartingPlayers(homePlayers.subList(0, 11));
        homeLineup.setSubstitutes(homePlayers.subList(11, 15));
        homeLineup.setFormation("4-4-2");
        lineupRepository.save(homeLineup);
        Lineup awayLineup = new Lineup();
        awayLineup.setTeam(awayTeam);
        awayLineup.setStartingPlayers(awayPlayers.subList(0, 11));
        awayLineup.setSubstitutes(awayPlayers.subList(11, 15));
        awayLineup.setFormation("4-2-3-1");
        lineupRepository.save(awayLineup);
        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeLineup(homeLineup);
        match.setAwayLineup(awayLineup);
        match.setHomeFormation(Formation.F_442.name());
        match.setAwayFormation(Formation.F_433.name());
        match.setHomeTactics(Tactics.defaultBalanced());
        match.setAwayTactics(Tactics.defaultBalanced());
        match.setMatchDate(LocalDateTime.now());
        matchRepository.save(match);

        Match played = matchService.playMatch(match.getId());
        matchService.printMatchDetails(played);
*/

    }
}