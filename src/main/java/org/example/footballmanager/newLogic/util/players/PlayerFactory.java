package org.example.footballmanager.newLogic.util.players;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
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
        Map<String, Player> existingByName = playerRepository.findByTeam(team).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Player::getName,
                        player -> player,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<Player> missingPlayers = new ArrayList<>();

        // Podaci o igračima Omladinca (ime, pozicija, godine, itd.)
        Object[][] data = {
                {"Zvezdan Vukomanović", Position.GK, 22, 186900000.0, 1888000.0, 177.0, 75.0, 10.0, 12, 9, 17, 4, 9, 4, 4, 8, 3, 1},
                {"Borislav Negovanović", Position.ATT, 20, 39320000.0, 482000.0, 180.0, 75.2, 14.0, 15, 9, 0, 4, 14, 11, 5, 6, 13, 9},
                {"Ljupče Ožegović", Position.ATT, 20, 33740000.0, 366000.0, 190.0, 90.9, 17.0, 13, 7, 1, 3, 14, 6, 7, 4, 14, 11},
                {"Aleksandar Simić", Position.ATT, 20, 20280000.0, 262000.0, 181.0, 78.2, 6.0, 12, 6, 1, 11, 8, 10, 4, 7, 13, 10},
                {"Žika Veljković", Position.MID, 24, 103880000.0, 1684000.0, 168.0, 58.5, 10.0, 16, 11, 0, 11, 16, 14, 14, 15, 7, 6},
                {"Šumenko Dabić", Position.MID, 24, 124740000.0, 1802000.0, 189.0, 80.9, 16.0, 17, 11, 1, 7, 16, 14, 13, 15, 6, 8},
                {"Darko Živanov", Position.DEF, 25, 113560000.0, 1548000.0, 170.0, 64.5, 18.0, 16, 11, 1, 15, 16, 12, 11, 11, 7, 2},
                {"David_Ionut Petri", Position.DEF, 25, 138580000.0, 1982000.0, 183.0, 76.3, 18.0, 15, 11, 1, 16, 17, 10, 12, 11, 9, 3},
                {"Ivica Tomić", Position.MID, 25, 141720000.0, 2008000.0, 184.0, 87.9, 18.0, 17, 11, 1, 9, 16, 15, 14, 14, 6, 7},
                {"Nenad Kačar", Position.DEF, 26, 61660000.0, 1514000.0, 166.0, 63.2, 0.0, 15, 11, 0, 16, 14, 12, 9, 13, 9, 4},
                {"Vladislav Cvijić", Position.DEF, 29, 138560000.0, 2332000.0, 199.0, 94.9, 17.0, 17, 11, 1, 17, 17, 9, 8, 8, 6, 5},
                {"Radenko Timić", Position.WNG, 23, 106780000.0, 930000.0, 159.0, 65.5, 15.0, 11, 11, 13, 4, 15, 4, 4, 7, 4, 12},
                {"Luigi Verdone", Position.MID, 31, 118560000.0, 1680000.0, 185.0, 78.7, 17.0, 13, 11, 0, 16, 14, 11, 12, 13, 8, 13},
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
            int stamina = (int) row[9];
            int keeper = (int) row[10];
            int defender = (int) row[11];
            int pace = (int) row[8];
            int technique = (int) row[12];
            int playmaker = (int) row[15];
            int passing = (int) row[13];
            int striker = (int) row[16];
            int squadNumber = (int) row[17];

            // === KLJUČNA LOGIKA: FIND OR CREATE ===
            Player player = existingByName.get(name);
            if (player == null) {
                player = createPlayer(name, age, team, value, earnings,
                        height, weight, form, 10, stamina, keeper, defender,
                        pace, technique, playmaker, passing, striker, position, squadNumber);
                missingPlayers.add(player);
                existingByName.put(name, player);
            }

            players.add(player);
        }

        if (!missingPlayers.isEmpty()) {
            playerRepository.saveAll(missingPlayers);
        }

        System.out.println("Omladinac igrači učitani/kreirani: " + players.size());
        return players;
    }

    /**
     * The FK Sremac Berkasovo squad, as named on the club's own match sheet.
     *
     * <p>Find-or-create, exactly like {@link #createOmladinacPlayers(Team)}, so re-seeding or a reset
     * never produces two Nenad Ugrenovićs.
     *
     * <p><b>These are a fifth-tier club.</b> The numbers are deliberately nothing like Omladinac's:
     * a Superliga squad runs to 18s, and a municipal one lives in the 5-11 band. Seeding a village
     * side with top-flight skills would make the bottom of the pyramid a place where players are
     * born at full ability, and no amount of correct promotion and relegation above it would hold up.
     * Talent is set explicitly for the same reason — the tier-5 clubs sit around 3-6 on the 1-10
     * scale, so a gifted fifteen-year-old here is genuinely promising rather than already good.
     *
     * <p>Numbers 13-17 are the bench and have no published position, so they are given a spread that
     * makes an 18-man matchday squad selectable: two defenders, two midfielders, an attacker.
     */
    public List<Player> createSremacPlayers(Team team) {
        // name, position, age, heightCm, weightKg, form, squadNumber,
        // stamina, keeper, defender, pace, technique, playmaker, passing, striker
        Object[][] data = {
                {"Nenad Ugrenović", Position.GK, 27, 187.0, 81.0, 6.5, 1, 8, 11, 6, 6, 5, 4, 5, 2},
                {"Miloš Matić", Position.DEF, 26, 182.0, 79.0, 6.0, 2, 9, 3, 10, 7, 6, 6, 7, 3},
                {"Živko Malić", Position.DEF, 31, 184.0, 84.0, 5.5, 3, 8, 3, 11, 7, 5, 6, 7, 4},
                {"Slobodan Milanković", Position.DEF, 29, 180.0, 77.0, 6.0, 4, 9, 3, 10, 7, 6, 5, 7, 3},
                {"Jovica Bogdanović", Position.DEF, 23, 178.0, 75.0, 6.5, 5, 10, 3, 9, 7, 6, 6, 6, 3},
                {"Milanko Subić", Position.MID, 28, 178.0, 74.0, 6.0, 6, 10, 2, 7, 9, 10, 9, 9, 6},
                {"Vasilj Abramović", Position.MID, 24, 176.0, 71.0, 6.5, 7, 10, 2, 6, 8, 9, 8, 8, 7},
                {"Nenad Petrović", Position.MID, 22, 180.0, 76.0, 6.0, 8, 9, 1, 7, 8, 9, 8, 8, 6},
                {"Srđan Arambašić", Position.ATT, 26, 179.0, 73.0, 6.5, 9, 9, 1, 10, 8, 6, 6, 6, 11},
                {"Boris Gospojević", Position.MID, 30, 177.0, 78.0, 5.5, 10, 8, 1, 6, 8, 9, 8, 7, 6},
                {"Srđan Subić", Position.ATT, 25, 181.0, 75.0, 6.0, 11, 9, 1, 9, 10, 7, 6, 6, 10},
                {"Stefan Ćetojević", Position.GK, 20, 189.0, 83.0, 5.5, 12, 8, 9, 5, 5, 4, 4, 5, 1},
                {"Milan Ćetojević", Position.MID, 21, 175.0, 70.0, 5.0, 13, 8, 1, 5, 7, 7, 7, 7, 5},
                {"Uroš Jovanović", Position.DEF, 20, 179.0, 76.0, 5.0, 14, 9, 1, 8, 6, 5, 5, 5, 3},
                {"Lazar Brenjevarac", Position.MID, 23, 181.0, 79.0, 5.5, 15, 9, 1, 6, 7, 8, 7, 6, 6},
                {"Nikola Jović", Position.ATT, 19, 177.0, 71.0, 5.5, 16, 8, 1, 9, 7, 5, 5, 5, 9},
                {"Branislav Andrić", Position.DEF, 27, 183.0, 82.0, 5.0, 17, 9, 1, 9, 6, 5, 6, 6, 3},
        };

        // Explicit talent, weakest-first so the spread is deliberate rather than a re-seed changing.
        double[] talentByRow = {4.5, 5.0, 4.0, 4.5, 5.5, 5.0, 5.5, 6.0, 5.5, 4.5, 5.5,
                                6.0, 5.5, 6.5, 5.0, 6.5, 4.0, 4.5};

        Map<String, Player> existingByName = playerRepository.findByTeam(team).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Player::getName, player -> player, (left, right) -> left, LinkedHashMap::new));

        List<Player> players = new ArrayList<>();
        List<Player> toSave = new ArrayList<>();

        for (int i = 0; i < data.length; i++) {
            Object[] row = data[i];
            String name = (String) row[0];
            Position position = (Position) row[1];
            int age = (int) row[2];
            double height = (double) row[3];
            double weight = (double) row[4];
            double form = (double) row[5];
            int squadNumber = (int) row[6];

            Player player = existingByName.get(name);
            if (player == null) {
                // Value and wage scale off skill level rather than being invented per row, so a
                // change to the skill band moves the whole squad's worth with it.
                double best = Math.max(Math.max((int) row[7], (int) row[8]), Math.max(
                        Math.max((int) row[9], (int) row[10]),
                        Math.max((int) row[11], Math.max((int) row[12],
                                Math.max((int) row[13], (int) row[14])))));
                double value = 4_000 + best * 4_500;
                double earnings = 250 + best * 170;

                player = createPlayer(name, age, team, value, earnings, height, weight, form, 6,
                        (int) row[7], (int) row[8], (int) row[9], (int) row[10], (int) row[11],
                        (int) row[12], (int) row[13], (int) row[14], position, squadNumber);
                toSave.add(player);
                existingByName.put(name, player);
            }
            player.setPosition(position);
            player.setSquadNumber(squadNumber);
            player.setTalent(Math.max(1.0, Math.min(10.0, talentByRow[i])));
            players.add(player);
        }

        if (!toSave.isEmpty()) {
            playerRepository.saveAll(toSave);
        }
        return players;
    }

    public List<Player> createRandomTeamPlayers(String teamName, Team team) {

        List<Player> existingPlayers = playerRepository.findByTeamId(team.getId());

        if (!existingPlayers.isEmpty()) {
            System.out.println("→ Tim " + teamName + " već ima igrače.");
            return existingPlayers;
        }

        List<Player> players = new ArrayList<>();
        Set<Integer> gkIndexes = new HashSet<>(List.of(random.nextInt(11), 11 + random.nextInt(4)));

        for (int i = 0; i < 15; i++) {

            String name = NameGenerator.fullName();
            Position position = gkIndexes.contains(i)
                    ? Position.GK
                    : Position.values()[1 + random.nextInt(3)];

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
                    1 + random.nextInt(10),
                    1 + random.nextInt(17),
                    1 + random.nextInt(17),
                    1 + random.nextInt(17),
                    1 + random.nextInt(17),
                    1 + random.nextInt(17),
                    1 + random.nextInt(17),
                    1 + random.nextInt(17),
                    position, null
            );

            players.add(newPlayer);
        }

        playerRepository.saveAll(players);

        System.out.println("→ Kreirani random igrači za tim: " + teamName);
        return players;
    }


    public static Player createPlayer(String name, int age, Team team, double value, double earnings,
                                      double height, double weight, double form, int discipline,
                                      int stamina, int keeper, int defender, int pace,
                                      int technique, int playmaker, int passing, int striker,
                                      Position position, Integer squadNumber) {
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
        p.setSquadNumber(squadNumber);

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
