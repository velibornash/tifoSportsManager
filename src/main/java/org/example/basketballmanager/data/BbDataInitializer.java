package org.example.basketballmanager.data;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.basketballmanager.model.*;
import org.example.basketballmanager.repository.*;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.model.CommonSeason;
import org.example.commonmanager.repository.CommonCompetitionRepository;
import org.example.commonmanager.repository.CommonSeasonRepository;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
public class BbDataInitializer {

    private static final String OWNER_EMAIL = "velibor@example.com";
    private static final int SEASON_YEAR = 2025;

    private final BbTeamRepository teamRepository;
    private final BbPlayerRepository playerRepository;
    private final BbMatchRepository matchRepository;
    private final BbSeasonCompetitionRepository seasonCompetitionRepository;
    private final BbCompetitionEntryRepository competitionEntryRepository;
    private final BbMatchFixtureRepository matchFixtureRepository;
    private final CommonCompetitionRepository commonCompetitionRepository;
    private final CommonSeasonRepository commonSeasonRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    public BbDataInitializer(BbTeamRepository teamRepository,
                             BbPlayerRepository playerRepository,
                             BbMatchRepository matchRepository,
                             BbSeasonCompetitionRepository seasonCompetitionRepository,
                             BbCompetitionEntryRepository competitionEntryRepository,
                             BbMatchFixtureRepository matchFixtureRepository,
                             CommonCompetitionRepository commonCompetitionRepository,
                             CommonSeasonRepository commonSeasonRepository,
                             UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.commonCompetitionRepository = commonCompetitionRepository;
        this.commonSeasonRepository = commonSeasonRepository;
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initBasketballData() {
        if (commonCompetitionRepository.findBySport("BASKETBALL").size() > 0) {
            log.info("Basketball data already seeded");
            return;
        }

        log.info("Seeding basketball data...");

        CommonSeason season = commonSeasonRepository.findBySeasonYear(SEASON_YEAR)
                .orElseGet(() -> commonSeasonRepository.save(
                        CommonSeason.builder().seasonYear(SEASON_YEAR).description("Season " + SEASON_YEAR).build()));

        List<CommonCompetition> competitions = createCompetitions(season);
        CommonCompetition superLiga = competitions.get(0);

        List<BbTeam> allTeams = new ArrayList<>();

        for (CommonCompetition comp : competitions) {
            List<BbTeam> compTeams = createTeamsForCompetition(comp);
            allTeams.addAll(compTeams);
        }

        teamRepository.saveAll(allTeams);

        for (CommonCompetition comp : competitions) {
            List<BbTeam> compTeams = allTeams.stream()
                    .filter(t -> t.getCompetition() != null && t.getCompetition().getId().equals(comp.getId()))
                    .toList();
            createPlayersForTeams(compTeams);
        }

        for (CommonCompetition comp : competitions) {
            List<BbTeam> compTeams = allTeams.stream()
                    .filter(t -> t.getCompetition() != null && t.getCompetition().getId().equals(comp.getId()))
                    .toList();
            setupSeasonCompetition(comp, season, compTeams);
        }

        assignOwnerTeam(superLiga, allTeams);

        log.info("Basketball data seeded: {} competitions, {} teams, competitions.size={}",
                competitions.size(), allTeams.size(), competitions.size());
    }

    private List<CommonCompetition> createCompetitions(CommonSeason season) {
        List<CommonCompetition> list = new ArrayList<>();
        String sport = "BASKETBALL";
        String country = "RS";

        int[][] tiers = {
                {1, 1, 10, 2, 2},
                {2, 2, 10, 2, 2},
                {3, 4, 10, 1, 1},
                {4, 8, 10, 1, 0},
                {5, 16, 10, 0, 0}
        };

        for (int[] t : tiers) {
            int tier = t[0], divCount = t[1], teamsPerDiv = t[2], promo = t[3], releg = t[4];
            for (int div = 1; div <= divCount; div++) {
                String name = switch (tier) {
                    case 1 -> "Košarkaška Super Liga";
                    case 2 -> "Prva Košarkaška Liga " + div;
                    case 3 -> "Druga Košarkaška Liga " + div;
                    case 4 -> "Treća Košarkaška Liga " + div;
                    case 5 -> "Četvrta Košarkaška Liga " + div;
                    default -> "Liga " + tier + "." + div;
                };
                CommonCompetition comp = commonCompetitionRepository.save(CommonCompetition.builder()
                        .name(name)
                        .shortName("BB" + tier + "." + div)
                        .countryCode(country)
                        .sport(sport)
                        .tier(tier)
                        .divisionLevel(div)
                        .teamsPerCompetition(teamsPerDiv)
                        .promotionSpots(promo)
                        .relegationSpots(releg)
                        .pointsWin(3)
                        .pointsDraw(1)
                        .pointsLoss(0)
                        .build());
                list.add(comp);
            }
        }
        return list;
    }

    private List<BbTeam> createTeamsForCompetition(CommonCompetition comp) {
        List<BbTeam> teams = new ArrayList<>();
        int count = comp.getTeamsPerCompetition();

        if (comp.getTier() == 1 && comp.getDivisionLevel() == 1) {
            // Super Liga: KK Omladinac + KK Partizan + 8 AI teams
            teams.add(makeTeam("KK Omladinac", "OML", "Beograd", "Hala Omladinca", 5000, "#f5a623", true, comp));
            teams.add(makeTeam("KK Partizan", "PAR", "Beograd", "Štark Arena", 20000, "#000000", false, comp));
            for (int i = 3; i <= count; i++) {
                teams.add(makeAiTeam(comp));
            }
        } else {
            for (int i = 1; i <= count; i++) {
                teams.add(makeAiTeam(comp));
            }
        }
        return teams;
    }

    private BbTeam makeTeam(String name, String shortName, String city, String hall, int cap, String color, boolean human, CommonCompetition comp) {
        return BbTeam.builder()
                .name(name)
                .shortName(shortName)
                .city(city)
                .hallName(hall)
                .hallCapacity(cap)
                .color(color)
                .humanControlled(human)
                .competition(comp)
                .build();
    }

    private static final String[] AI_NAMES = {"KK Vojvodina", "KK Omladinac", "KK Mega Basket",
            "KK FMP", "KK Borac", "KK Spartak", "KK Radnički", "KK Sloboda", "KK Tamiš",
            "KK Sloga", "KK Dunav", "KK Vršac", "KK Konstantin", "KK Mladost",
            "KK Metalac", "KK Napredak", "KK Jedinstvo", "KK Budućnost", "KK Zlatibor"};
    private static final String[] AI_SHORT = {"VOJ", "CZV", "MEG", "FMP", "BOR", "SPA", "RAD", "SLO", "TAM",
            "SLG", "DUN", "VRS", "KON", "MLA", "MET", "NAP", "JED", "BUD", "ZLA"};
    private static final String[] AI_CITIES = {"Novi Sad", "Beograd", "Beograd", "Beograd", "Čačak",
            "Subotica", "Kragujevac", "Užice", "Pančevo", "Kraljevo", "Stari Banovci",
            "Vršac", "Niš", "Valjevo", "Gornji Milanovac", "Kruševac", "Bijenelje Polje",
            "Podgorica", "Zlatibor"};
    private static final String[] AI_COLORS = {"#e74c3c", "#c0392b", "#2ecc71", "#3498db", "#f39c12",
            "#1abc9c", "#9b59b6", "#2c3e50", "#16a085", "#d35400", "#2980b9",
            "#8e44ad", "#e67e22", "#27ae60", "#7f8c8d", "#e84393", "#00cec9",
            "#6c5ce7", "#fdcb6e"};

    private int aiTeamCounter = 0;

    private BbTeam makeAiTeam(CommonCompetition comp) {
        int idx2 = aiTeamCounter % AI_NAMES.length;
        int group = aiTeamCounter / AI_NAMES.length;
        aiTeamCounter++;
        String suffix = group > 0 ? " (" + group + ")" : "";
        return BbTeam.builder()
                .name(AI_NAMES[idx2] + suffix)
                .shortName(AI_SHORT[idx2] + suffix.replace(" ", "").replace("(", "").replace(")", ""))
                .city(AI_CITIES[idx2])
                .hallName(AI_NAMES[idx2] + " Hall")
                .hallCapacity(2000 + random.nextInt(16000))
                .color(AI_COLORS[idx2])
                .humanControlled(false)
                .competition(comp)
                .build();
    }

    private void createPlayersForTeams(List<BbTeam> teams) {
        for (BbTeam team : teams) {
            List<BbPlayer> players = generatePlayers(team);
            playerRepository.saveAll(players);
            team.setPlayers(players);
        }
        teamRepository.saveAll(teams);
    }

    private List<BbPlayer> generatePlayers(BbTeam team) {
        List<BbPlayer> players = new ArrayList<>();
        List<String> names = generateSerbianNames();

        for (int i = 0; i < 12; i++) {
            BbPlayer.Position position = BbPlayer.Position.values()[i % 5];
            int height = switch (position) {
                case PG -> 178 + random.nextInt(15);
                case SG -> 188 + random.nextInt(10);
                case SF -> 193 + random.nextInt(12);
                case PF -> 198 + random.nextInt(15);
                case C -> 205 + random.nextInt(20);
            };
            int weight = switch (position) {
                case PG -> 75 + random.nextInt(15);
                case SG -> 82 + random.nextInt(12);
                case SF -> 88 + random.nextInt(15);
                case PF -> 95 + random.nextInt(20);
                case C -> 100 + random.nextInt(25);
            };

            int pace = baseSkill();
            int steals = baseSkill();
            int blocks = baseSkill();
            int freeThrows = baseSkill();
            int twoPtShot = baseSkill();
            int threePtShot = baseSkill();
            int rebounding = baseSkill();
            int playmaking = baseSkill();

            switch (position) {
                case PG -> { playmaking = highSkill(); pace = highSkill(); threePtShot = 8 + random.nextInt(8); }
                case SG -> { threePtShot = highSkill(); twoPtShot = highSkill(); pace = highSkill(); }
                case SF -> { twoPtShot = highSkill(); pace = highSkill(); rebounding = 8 + random.nextInt(8); }
                case PF -> { rebounding = highSkill(); blocks = 8 + random.nextInt(8); twoPtShot = highSkill(); }
                case C -> { rebounding = 13 + random.nextInt(5); blocks = highSkill(); twoPtShot = highSkill(); freeThrows = 6 + random.nextInt(8); }
            }

            BbPlayer player = BbPlayer.builder()
                    .name(names.get(i))
                    .position(position)
                    .height(height)
                    .weight(weight)
                    .jerseyNumber(i + 1)
                    .injured(false)
                    .fatigue(random.nextInt(30))
                    .skillPace(clamp(pace))
                    .skillSteals(clamp(steals))
                    .skillBlocks(clamp(blocks))
                    .skillFreeThrows(clamp(freeThrows))
                    .skillTwoPtShot(clamp(twoPtShot))
                    .skillThreePtShot(clamp(threePtShot))
                    .skillRebounding(clamp(rebounding))
                    .skillPlaymaking(clamp(playmaking))
                    .team(team)
                    .stats(new org.example.basketballmanager.model.BbPlayerStats())
                    .build();

            players.add(player);
        }
        return players;
    }

    private void setupSeasonCompetition(CommonCompetition comp, CommonSeason season, List<BbTeam> compTeams) {
        BbSeasonCompetition sc = BbSeasonCompetition.builder()
                .competition(comp)
                .season(season)
                .finished(false)
                .build();
        sc = seasonCompetitionRepository.save(sc);

        for (BbTeam team : compTeams) {
            BbCompetitionEntry entry = BbCompetitionEntry.builder()
                    .seasonCompetition(sc)
                    .team(team)
                    .points(0)
                    .pointsScored(0)
                    .pointsConceded(0)
                    .wins(0)
                    .losses(0)
                    .build();
            competitionEntryRepository.save(entry);
        }

        generateRoundRobinFixtures(comp, season, compTeams);
    }

    private void generateRoundRobinFixtures(CommonCompetition comp, CommonSeason season, List<BbTeam> teams) {
        int numTeams = teams.size();
        int rounds = (numTeams - 1) * 2;
        LocalDateTime baseDate = LocalDateTime.of(SEASON_YEAR, 9, 15, 18, 0);

        // Simple round-robin (circle method)
        List<BbTeam> rotated = new ArrayList<>(teams);
        for (int round = 0; round < numTeams - 1; round++) {
            // First vs last, second vs second-last, etc.
            for (int i = 0; i < numTeams / 2; i++) {
                BbTeam home = rotated.get(i);
                BbTeam away = rotated.get(numTeams - 1 - i);
                if (random.nextBoolean()) {
                    // Swap for alternating home/away
                    BbTeam t = home; home = away; away = t;
                }
                saveFixture(home, away, comp, season, round + 1, baseDate.plusDays(round * 7L));
                // Return leg
                saveFixture(away, home, comp, season, round + 1 + (numTeams - 1), baseDate.plusDays((round + numTeams) * 7L));
            }
            // Rotate (keep first fixed, rotate others clockwise)
            BbTeam first = rotated.get(0);
            List<BbTeam> rest = new ArrayList<>(rotated.subList(1, rotated.size()));
            rotated = new ArrayList<>();
            rotated.add(first);
            rotated.add(rest.get(rest.size() - 1));
            rotated.addAll(rest.subList(0, rest.size() - 1));
        }
    }

    private void saveFixture(BbTeam home, BbTeam away, CommonCompetition comp, CommonSeason season, int round, LocalDateTime date) {
        BbMatchFixture fixture = BbMatchFixture.builder()
                .homeTeam(home)
                .awayTeam(away)
                .competition(comp)
                .seasonYear(season.getSeasonYear())
                .roundNumber(round)
                .weekNumber(round)
                .matchDate(date)
                .played(false)
                .build();
        matchFixtureRepository.save(fixture);
    }

    private void assignOwnerTeam(CommonCompetition superLiga, List<BbTeam> allTeams) {
        Optional<User> owner = userRepository.findByUsernameOrEmail(OWNER_EMAIL);
        if (owner.isPresent()) {
            User user = owner.get();
            BbTeam omladinac = allTeams.stream()
                    .filter(t -> "KK Omladinac".equals(t.getName()))
                    .findFirst().orElse(null);
            if (omladinac != null) {
                user.setBasketballTeam(omladinac);
                userRepository.save(user);
                log.info("KK Omladinac assigned to owner");
            }
        }
    }

    private List<String> generateSerbianNames() {
        String[] firstNames = {"Nikola", "Luka", "Bogdan", "Vasilije", "Aleksa", "Marko", "Milan", "Stefan",
                "Nemanja", "Filip", "Dušan", "Ognjen", "Petar", "Vladimir", "Dejan", "Miroslav",
                "Ivan", "Dragan", "Saša", "Zoran", "Mladen", "Aleksandar", "Darko", "Uroš"};
        String[] lastNames = {"Jokić", "Dončić", "Bogdanović", "Micić", "Avramović", "Petrušev",
                "Jović", "Kalinić", "Lučić", "Teodosić", "Bjelica", "Marjanović", "Raduljica",
                "Simonović", "Mitrović", "Pokuševski",
                "Topić", "Milojević", "Pavlović"};
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            String name;
            do {
                name = firstNames[random.nextInt(firstNames.length)] + " " + lastNames[random.nextInt(lastNames.length)];
            } while (used.contains(name));
            used.add(name);
            names.add(name);
        }
        return names;
    }

    private int baseSkill() { return 5 + random.nextInt(8); }
    private int highSkill() { return 10 + random.nextInt(8); }
    private int clamp(int val) { return Math.max(1, Math.min(20, val)); }
}
