package org.example.footballmanager.util.events;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Component
public class EventCreator {

    private static final Random random = new Random();

    public static MatchEvent createEventByRoll(double roll, org.example.footballmanager.model.Match match, Team team, List<Player> players, int minute) {

        if (roll < 0.09) { // 9% šansa za gol

            // Kandidati za strelca (bez golmana)
            List<Player> scorers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();

            if (scorers.isEmpty()) return null;

            Player scorer = scorers.get(random.nextInt(scorers.size()));

            GoalEvent goal = new GoalEvent();
            goal.setMinute(minute);
            goal.setMatch(match);
            goal.setTeam(team);
            goal.setScorer(scorer);

            // Kandidati za asistenta (isti tim, nije scorer, nije golman)
            List<Player> assistants = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .filter(p -> !p.getId().equals(scorer.getId()))
                    .toList();

            if (!assistants.isEmpty()) {
                Player assistant = assistants.get(random.nextInt(assistants.size()));
                goal.setAssistant(assistant);
            }

            goal.apply();   // nemoj zvati isScored() jer ti samo setuje true
            return goal;
        }

        else if (roll < 0.14) { // 5% žuti karton
            Player offender = players.get(random.nextInt(players.size()));
            YellowCardEvent yc = new YellowCardEvent();
            yc.setMatch(match);
            yc.setTeam(team);
            yc.setPlayer(offender);
            yc.setMinute(minute);
            return yc;
        }
        else if (roll < 0.16) { // 2% crveni
            Player offender = players.get(random.nextInt(players.size()));
            RedCardEvent rc = new RedCardEvent();
            rc.setMatch(match);
            rc.setTeam(team);
            rc.setPlayer(offender);
            rc.setMinute(minute);
            return rc;
        }
        else if (roll < 0.19) { // penalty
            List<Player> takers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();
            if (takers.isEmpty()) return null;
            Player taker = takers.get(random.nextInt(takers.size()));

            PenaltyEvent p = new PenaltyEvent();
            p.setMatch(match);
            p.setTeam(team);
            p.setTaker(taker);
            p.setMinute(minute);
            int score = random.nextInt(10);
            if(score < 4) {p.setScored(false);} else {p.setScored(true);}

                return p;
        }
        else if (roll < 0.23) { // slobodan udarac
            FreeKickEvent fk = new FreeKickEvent();
            List<Player> takers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();
            if (takers.isEmpty()) return null;
            Player taker = takers.get(random.nextInt(takers.size()));
            fk.setMatch(match);
            fk.setTeam(team);
            fk.setTaker(taker);
            fk.setPlayer(taker);
            fk.setMinute(minute);
            return fk;
        }
        else if (roll < 0.26) { // korner
            List<Player> takers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();
            if (takers.isEmpty()) return null;
            Player taker = takers.get(random.nextInt(takers.size()));
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(team);
            c.setPlayer(taker);
            c.setMinute(minute);
            return c;
        }
        else if (roll < 0.37) { // šut na gol
            List<Player> takers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();
            if (takers.isEmpty()) return null;
            Player shooter = takers.get(random.nextInt(takers.size()));
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(team);
            s.setShooter(shooter);
            s.setMinute(minute);
            return s;
        }
        else if (roll < 0.52) { // šut van
            List<Player> takers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();
            if (takers.isEmpty()) return null;
            Player shooter = takers.get(random.nextInt(takers.size()));
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(team);
            s.setShooter(shooter);
            s.setMinute(minute);
            return s;
        }
        else {
            ChanceEvent ch = new ChanceEvent();
            List<Player> takers = players.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .toList();
            if (takers.isEmpty()) return null;
            Player player = takers.get(random.nextInt(takers.size()));
            ch.setMinute(minute);
            ch.setMatch(match);
            ch.setTeam(team);
            ch.setPlayer(player);
            ch.setMinute(minute);
            return ch;
        }
    }

    public GoalEvent createRandomGoalEvent(Match match, Team scoringTeam, List<Player> scoringPlayers, List<Player> opponentPlayers, Random rnd) {
        // Izaberi strelca (favorizuj napadače)
        List<Player> attackers = scoringPlayers.stream()
                .filter(p -> p.getPosition() == Position.ATT || p.getPosition() == Position.MID || p.getPosition() == Position.WNG)
                .collect(Collectors.toList());

        if (attackers.isEmpty()) attackers = scoringPlayers; // fallback

        Player scorer = attackers.get(rnd.nextInt(attackers.size()));

        GoalEvent goal = new GoalEvent();
        goal.setMatch(match);
        goal.setTeam(scoringTeam);
        goal.setScorer(scorer);
        goal.setMinute(rnd.nextInt(90) + 1);

        // 60% šanse da postoji asistent
        if (rnd.nextDouble() < 0.6) {
            List<Player> possibleAssistants = scoringPlayers.stream()
                    .filter(p -> !p.getId().equals(scorer.getId()))
                    .toList();

            if (!possibleAssistants.isEmpty()) {
                Player assistant = possibleAssistants.get(rnd.nextInt(possibleAssistants.size()));
                goal.setAssistant(assistant);
            }
        }

        return goal;
    }

}