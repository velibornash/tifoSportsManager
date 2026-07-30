package org.example.footballmanager.newLogic.util.match;

import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.tactics.Tactics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Getter
@Setter
public class MatchContext {

    private Match match;
    private Team homeTeam;
    private Team awayTeam;

    private List<Player> homePlayersOnField = new ArrayList<>();
    private List<Player> awayPlayersOnField = new ArrayList<>();

    private Player possessionPlayer;
    private Team possessionTeam;

    private int currentMinute;
    private Random random = new Random();

    private Crowd crowd;
    private Referee referee;
    private Tactics homeTactics;
    private Tactics awayTactics;
    private double fatigueFactor = 1.0; // 1.0 = bez efekta, može se menjati u simulatoru




    public MatchContext(Match match, Crowd crowd, Referee referee, Tactics homeTactics, Tactics awayTactics) {
        this.match = match;
        this.homeTeam = match.getHomeTeam();
        this.awayTeam = match.getAwayTeam();
        this.currentMinute = 0;
        this.crowd = crowd;
        this.referee = referee;
        this.homeTactics = homeTactics;
        this.awayTactics = awayTactics;

        if (match.getHomeLineup() != null) homePlayersOnField.addAll(match.getHomeLineup().getStartingPlayers());
        if (match.getAwayLineup() != null) awayPlayersOnField.addAll(match.getAwayLineup().getStartingPlayers());
    }

    public void nextMinute() {
        currentMinute++;
        updateFatigue();
    }

    private void updateFatigue() {
        homePlayersOnField.forEach(p -> {
            int newFatigue = Math.min(100, p.getSkills().getFatigue() + 1);
            p.getSkills().setSkill(org.example.footballmanager.newLogic.model.SkillName.FATIGUE, newFatigue);
        });
        awayPlayersOnField.forEach(p -> {
            int newFatigue = Math.min(100, p.getSkills().getFatigue() + 1);
            p.getSkills().setSkill(org.example.footballmanager.newLogic.model.SkillName.FATIGUE, newFatigue);
        });
    }

    public List<Player> getAllPlayersOnField() {
        List<Player> all = new ArrayList<>();
        all.addAll(homePlayersOnField);
        all.addAll(awayPlayersOnField);
        return all;
    }
}
