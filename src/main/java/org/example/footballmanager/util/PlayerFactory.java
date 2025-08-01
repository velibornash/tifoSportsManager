package org.example.footballmanager.util;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;

import java.util.*;

public class PlayerFactory {

    private static final Random random = new Random();

    public static List<Player> createOmladinacPlayers(Team team) {
        List<Player> players = new ArrayList<>();

        players.add(createPlayer("Zvezdan Vukomanović", 22, team, 186900000, 1888000, 177, 75, 10, 12,
                9, 17, 4, 9, 4, 4, 8, 3, Position.GK));

        players.add(createPlayer("Borislav Negovanović", 20, team, 39320000, 482000, 180, 75.2, 14, 15,
                9, 0, 4, 14, 11, 5, 6, 11, Position.ATT));

        players.add(createPlayer("Ljupče Ožegović", 20, team, 33740000, 366000, 190, 90.9, 17, 13,
                7, 1, 3, 14, 6, 7, 4, 11, Position.ATT));

        players.add(createPlayer("Aleksandar Simić", 20, team, 20280000, 262000, 181, 78.2, 6, 12,
                6, 1, 11, 8, 10, 4, 7, 12, Position.ATT));


        players.add(createPlayer("Žika Veljković", 24, team, 103880000, 1684000, 168, 58.5, 10, 16,
                11, 0, 11, 16, 14, 14, 15, 7, Position.MID));

        players.add(createPlayer("Šumenko Dabić", 24, team, 124740000, 1802000, 189, 80.9, 16, 17,
                11, 1, 7, 16, 14, 13, 15, 6, Position.MID));

        players.add(createPlayer("Darko Živanov", 25, team, 113560000, 1548000, 170, 64.5, 18, 16,
                11, 1, 15, 16, 12, 11, 11, 7, Position.DEF));

        players.add(createPlayer("David-Ionuţ Petri", 25, team, 138580000, 1982000, 183, 76.3, 18, 15,
                11, 1, 16, 17, 10, 12, 11, 9, Position.DEF));

        players.add(createPlayer("Ivica Tomić", 25, team, 141720000, 2008000, 184, 87.9, 18, 17,
                11, 1, 9, 16, 15, 14, 14, 6, Position.MID));

        players.add(createPlayer("Nenad Kačar", 26, team, 61660000, 1514000, 166, 63.2, 0, 15,
                11, 0, 16, 14, 12, 9, 13, 9, Position.DEF));

        players.add(createPlayer("Vladislav Cvijić", 29, team, 138560000, 2332000, 199, 94.9, 17, 17,
                11, 1, 17, 17, 9, 8, 8, 6, Position.DEF));

        players.add(createPlayer("Radenko Timić", 23, team, 106780000, 930000, 159, 65.5, 15, 11,
                11, 13, 4, 15, 4, 4, 7, 4, Position.GK));

        players.add(createPlayer("Luigi Verdone", 31, team, 118560000, 1680000, 185, 78.7, 17, 13,
                11, 0, 16, 14, 11, 12, 13, 8, Position.MID));

        players.add(createPlayer("Velibor Mandzo", 61, team, 200000, 32000, 181, 74.3, 2, 16,
                6, 0, 0, 0, 0, 0, 0, 0, Position.ATT));

        players.add(createPlayer("Remorker Đetić", 17, team, 2640000, 56000, 182, 86.1, 5, 0,
                1, 0, 0, 5, 0, 0, 1, 8, Position.DEF));

        return players;
    }

    public static List<Player> createRandomTeamPlayers(String teamName, Team team) {
        List<Player> players = new ArrayList<>();
        Set<Integer> gkIndexes = new HashSet<>(List.of(random.nextInt(11), 11 + random.nextInt(4)));

        for (int i = 0; i < 15; i++) {
            String name = NameGenerator.fullName();
            Position position;
            if (gkIndexes.contains(i)) {
                position = Position.GK;
            } else {
                position = Position.values()[1 + random.nextInt(3)]; // DEF, MID, ATT
            }

            players.add(createPlayer(
                    name,
                    18 + random.nextInt(15),
                    team,
                    1000000 + random.nextInt(50000000),
                    50000 + random.nextInt(1000000),
                    160 + random.nextInt(40),
                    55 + random.nextDouble() * 40,
                    4 + random.nextDouble() * 6,
                    5 + random.nextInt(6),
                    random.nextInt(18),
                    random.nextInt(18),
                    random.nextInt(18),
                    random.nextInt(18),
                    random.nextInt(18),
                    random.nextInt(18),
                    random.nextInt(18),
                    random.nextInt(18),
                    position
            ));
        }
        return players;
    }

    public static Player createPlayer(String name, int age, Team team, double value, double earnings,
                                      double height, double weight, double form, int discipline,
                                      int stamina, int keeper, int defender, int pace,
                                      int technique, int playmaker, int passing, int striker,
                                      Position position) {
        Player p = new Player();
        p.setName(name);
        p.setAge(age);
        p.setTeam(team);
        p.setPlayerValue(value);
        p.setEarnings(earnings);
        p.setHeight(height / 100.0); // convert to meters
        p.setWeight(weight);
        p.setForm(form);
        p.setTalent((20.0 - (discipline + form)) / 2.0); // approx
        p.setPosition(position.name());
        Skills s = new Skills();
        s.setStamina(stamina);
        s.setGoalkeeper(keeper);
        s.setDefender(defender);
        s.setPace(pace);
        s.setTechnique(technique);
        s.setPlaymaker(playmaker);
        s.setPassing(passing);
        s.setStriker(striker);
        p.setSkills(s);
        return p;
    }
}