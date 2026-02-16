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
                                               List<Player> players, int minute) {

        if (roll < 0.09) { // 9% šansa za gol
            Player scorer = players.get(random.nextInt(players.size()));
            GoalEvent goal = new GoalEvent();
            goal.setMinute(minute);
            goal.setMatch(match);
            goal.setTeam(team);
            goal.setScorer(scorer);
            goal.isScored();
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
            Player taker = players.get(random.nextInt(players.size()));
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
            Player taker = players.get(random.nextInt(players.size()));
            fk.setMatch(match);
            fk.setTeam(team);
            fk.setTaker(taker);
            fk.setPlayer(taker);
            fk.setMinute(minute);
            return fk;
        }
        else if (roll < 0.26) { // korner
            Player taker = players.get(random.nextInt(players.size()));
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(team);
            c.setPlayer(taker);
            c.setMinute(minute);
            return c;
        }
        else if (roll < 0.37) { // šut na gol
            Player shooter = players.get(random.nextInt(players.size()));
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(team);
            s.setShooter(shooter);
            s.setMinute(minute);
            return s;
        }
        else if (roll < 0.52) { // šut van
            Player shooter = players.get(random.nextInt(players.size()));
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(team);
            s.setShooter(shooter);
            s.setMinute(minute);
            return s;
        }
        else {
            ChanceEvent ch = new ChanceEvent();
            Player player = players.get(random.nextInt(players.size()));
            ch.setMinute(minute);
            ch.setMatch(match);
            ch.setTeam(team);
            ch.setPlayer(player);
            ch.setMinute(minute);
            return ch;
        }
    }
}
