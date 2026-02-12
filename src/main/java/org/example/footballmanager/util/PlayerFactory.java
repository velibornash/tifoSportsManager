package org.example.footballmanager.util;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PlayerFactory {

    private  final PlayerRepository playerRepository;
    private  final Random random = new Random();

    @Autowired
    public PlayerFactory(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    /**
     * Vraća igrače za Omladinac – učitava iz baze ako postoje, kreira samo ako ne postoje
     */
    public List<Player> createOmladinacPlayers(Team team) {
        List<Player> players = new ArrayList<>();

        // Podaci o igračima Omladinca (ime, pozicija, godine, itd.)
        Object[][] data = {
                {"Zvezdan Vukomanović", Position.GK, 22, 186900000.0, 1888000.0, 177.0, 75.0, 10.0, 12, 9, 17, 4, 9, 4, 4, 8, 3},
                {"Borislav Negovanović", Position.ATT, 20, 39320000.0, 482000.0, 180.0, 75.2, 14.0, 15, 9, 0, 4, 14, 11, 5, 6, 11},
                {"Ljupče Ožegović", Position.ATT, 20, 33740000.0, 366000.0, 190.0, 90.9, 17.0, 13, 7, 1, 3, 14, 6, 7, 4, 11},
                {"Aleksandar Simić", Position.ATT, 20, 20280000.0, 262000.0, 181.0, 78.2, 6.0, 12, 6, 1, 11, 8, 10, 4, 7, 12},
                {"Žika Veljković", Position.MID, 24, 103880000.0, 1684000.0, 168.0, 58.5, 10.0, 16, 11, 0, 11, 16, 14, 14, 15, 7},
                {"Šumenko Dabić", Position.MID, 24, 124740000.0, 1802000.0, 189.0, 80.9, 16.0, 17, 11, 1, 7, 16, 14, 13, 15, 6},
                {"Darko Živanov", Position.DEF, 25, 113560000.0, 1548000.0, 170.0, 64.5, 18.0, 16, 11, 1, 15, 16, 12, 11, 11, 7},
                {"David-Ionuţ Petri", Position.DEF, 25, 138580000.0, 1982000.0, 183.0, 76.3, 18.0, 15, 11, 1, 16, 17, 10, 12, 11, 9},
                {"Ivica Tomić", Position.MID, 25, 141720000.0, 2008000.0, 184.0, 87.9, 18.0, 17, 11, 1, 9, 16, 15, 14, 14, 6},
                {"Nenad Kačar", Position.DEF, 26, 61660000.0, 1514000.0, 166.0, 63.2, 0.0, 15, 11, 0, 16, 14, 12, 9, 13, 9},
                {"Vladislav Cvijić", Position.DEF, 29, 138560000.0, 2332000.0, 199.0, 94.9, 17.0, 17, 11, 1, 17, 17, 9, 8, 8, 6},
                {"Radenko Timić", Position.GK, 23, 106780000.0, 930000.0, 159.0, 65.5, 15.0, 11, 11, 13, 4, 15, 4, 4, 7, 4},
                {"Luigi Verdone", Position.MID, 31, 118560000.0, 1680000.0, 185.0, 78.7, 17.0, 13, 11, 0, 16, 14, 11, 12, 13, 8},
                // dodaj ostale igrače po potrebi...
        };

        for (Object[] row : data) {
            String name = (String) row[0];
            Position position = (Position) row[1];
            int age = (int) row[2];
            double value = (double) row[3];
            double earnings = (double) row[4];
            double height = (double) row[5];
            double weight = (double) row[6];
            double form = (double) row[7];
            int stamina = (int) row[8];
            int keeper = (int) row[9];
            int defender = (int) row[10];
            int pace = (int) row[11];
            int technique = (int) row[12];
            int playmaker = (int) row[13];
            int passing = (int) row[14];
            int striker = (int) row[15];

            // === KLJUČNA LOGIKA: FIND OR CREATE ===
            Player player = playerRepository.findByNameAndTeam(name, team)
                    .orElseGet(() -> {
                        Player newPlayer = createPlayer(name, age, team, value, earnings,
                                height, weight, form, 10, stamina, keeper, defender,
                                pace, technique, playmaker, passing, striker, position);

                        Player saved = playerRepository.save(newPlayer);
                        System.out.println("→ Kreiran novi igrač u bazi: " + name);
                        return saved;
                    });

            players.add(player);
        }

        System.out.println("Omladinac igrači učitani/kreirani: " + players.size());
        return players;
    }

    public List<Player> createRandomTeamPlayers(String teamName, Team team) {
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

            Player newPlayer = createPlayer(
                    name,
                    18 + random.nextInt(15),
                    team,
                    1000000 + random.nextInt(50000000),
                    50000 + random.nextInt(1000000),
                    160 + random.nextInt(40),
                    Math.round((55 + random.nextDouble() * 40) * 100.0) / 100.0,
                    Math.round((4 + random.nextDouble() * 6) * 100.0) / 100.0,
                    5 + random.nextInt(6),
                    1+random.nextInt(10),
                    1+random.nextInt(17),
                    1+random.nextInt(17),
                    1+random.nextInt(17),
                    1+random.nextInt(17),
                    1+random.nextInt(17),
                    1+random.nextInt(17),
                    1+random.nextInt(17),
                    position
            );

            // SAČUVAJ IGRAČA ODMAH
            Player savedPlayer = playerRepository.save(newPlayer);
            players.add(savedPlayer);
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
        p.setHeight(height / 100.0);
        p.setWeight(weight);
        p.setForm(form);
        p.setTalent((20.0 - (discipline + form)) / 2.0);
        p.setPosition(position);

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