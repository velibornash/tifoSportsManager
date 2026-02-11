package org.example.footballmanager.simulator;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.*;

import java.util.List;
import java.util.Random;

public class EventCreator {

    private static final Random random = new Random();

    public static MatchEvent createEventByRoll(double roll,
                                               org.example.footballmanager.model.Match match,
                                               Team team,
                                               List<Player> players) {

        if (roll < 0.09) { // 5% šansa za gol
            Player scorer = players.get(random.nextInt(players.size()));
            GoalEvent goal = new GoalEvent();
            goal.setMatch(match);
            goal.setTeam(team);
            goal.setScorer(scorer);
            goal.isScored();
            return goal;
        } else if (roll < 0.14) { // 5% žuti karton
            Player offender = players.get(random.nextInt(players.size()));
            YellowCardEvent yc = new YellowCardEvent();
            yc.setMatch(match);
            yc.setTeam(team);
            yc.setPlayer(offender);
            return yc;
        } else if (roll < 0.16) { // 2% crveni
            Player offender = players.get(random.nextInt(players.size()));
            RedCardEvent rc = new RedCardEvent();
            rc.setMatch(match);
            rc.setTeam(team);
            rc.setPlayer(offender);
            return rc;
        } else if (roll < 0.19) { // penalty
            Player taker = players.get(random.nextInt(players.size()));
            PenaltyEvent p = new PenaltyEvent();
            p.setMatch(match);
            p.setTeam(team);
            p.setTaker(taker);
            int score = random.nextInt(10);
            if(score < 4) {p.setScored(false);} else {p.setScored(true);}

                return p;
        } else if (roll < 0.21) { // slobodan udarac
            FreeKickEvent fk = new FreeKickEvent();
            Player taker = players.get(random.nextInt(players.size()));
            fk.setMatch(match);
            fk.setTeam(team);
            fk.setTaker(taker);
            return fk;
        } else if (roll < 0.23) { // korner
            Player taker = players.get(random.nextInt(players.size()));
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(team);
            c.setPlayer(taker);
            return c;
        } else if (roll < 0.37) { // šut na gol
            Player shooter = players.get(random.nextInt(players.size()));
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(team);
            s.setShooter(shooter);
            return s;
        } else if (roll < 0.52) { // šut van
            Player shooter = players.get(random.nextInt(players.size()));
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(team);
            s.setShooter(shooter);
            return s;
        }

        return null; // ostali eventi mogu se dodati
    }
}
