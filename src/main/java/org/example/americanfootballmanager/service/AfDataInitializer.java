package org.example.americanfootballmanager.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.americanfootballmanager.model.*;
import org.example.americanfootballmanager.repository.*;
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
public class AfDataInitializer {

    private static final String OWNER_EMAIL = "velibor@example.com";
    private static final int SEASON_YEAR = 2025;

    private final AfTeamRepository teamRepository;
    private final AfPlayerRepository playerRepository;
    private final AfMatchRepository matchRepository;
    private final AfSeasonCompetitionRepository seasonCompetitionRepository;
    private final AfCompetitionEntryRepository competitionEntryRepository;
    private final AfMatchFixtureRepository matchFixtureRepository;
    private final CommonCompetitionRepository commonCompetitionRepository;
    private final CommonSeasonRepository commonSeasonRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    public AfDataInitializer(AfTeamRepository teamRepository,
                              AfPlayerRepository playerRepository,
                              AfMatchRepository matchRepository,
                              AfSeasonCompetitionRepository seasonCompetitionRepository,
                              AfCompetitionEntryRepository competitionEntryRepository,
                              AfMatchFixtureRepository matchFixtureRepository,
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
    public void initAmericanFootballData() {
        if (commonCompetitionRepository.findBySport("AMERICAN_FOOTBALL").size() > 0) {
            log.info("American Football data already seeded");
            return;
        }

        log.info("Seeding American Football data...");

        CommonSeason season = commonSeasonRepository.findBySeasonYear(SEASON_YEAR)
                .orElseGet(() -> commonSeasonRepository.save(
                        CommonSeason.builder().seasonYear(SEASON_YEAR).description("Season " + SEASON_YEAR).build()));

        List<CommonCompetition> competitions = createCompetitions(season);
        CommonCompetition superLiga = competitions.get(0);

        List<AfTeam> allTeams = new ArrayList<>();

        for (CommonCompetition comp : competitions) {
            List<AfTeam> compTeams = createTeamsForCompetition(comp);
            allTeams.addAll(compTeams);
        }

        teamRepository.saveAll(allTeams);

        for (CommonCompetition comp : competitions) {
            List<AfTeam> compTeams = allTeams.stream()
                    .filter(t -> t.getCompetition() != null && t.getCompetition().getId().equals(comp.getId()))
                    .toList();
            createPlayersForTeams(compTeams);
        }

        for (CommonCompetition comp : competitions) {
            List<AfTeam> compTeams = allTeams.stream()
                    .filter(t -> t.getCompetition() != null && t.getCompetition().getId().equals(comp.getId()))
                    .toList();
            setupSeasonCompetition(comp, season, compTeams);
        }

        assignOwnerTeam(superLiga, allTeams);

        log.info("American Football data seeded: {} competitions, {} teams", competitions.size(), allTeams.size());
    }

    private List<CommonCompetition> createCompetitions(CommonSeason season) {
        List<CommonCompetition> list = new ArrayList<>();
        String sport = "AMERICAN_FOOTBALL";
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
                    case 1 -> "Fudbalska Super Liga (AF)";
                    case 2 -> "Prva Liga (AF) " + div;
                    case 3 -> "Druga Liga (AF) " + div;
                    case 4 -> "Treća Liga (AF) " + div;
                    case 5 -> "Četvrta Liga (AF) " + div;
                    default -> "AF Liga " + tier + "." + div;
                };
                CommonCompetition comp = commonCompetitionRepository.save(CommonCompetition.builder()
                        .name(name)
                        .shortName("AF" + tier + "." + div)
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

    private List<AfTeam> createTeamsForCompetition(CommonCompetition comp) {
        List<AfTeam> teams = new ArrayList<>();
        int count = comp.getTeamsPerCompetition();

        if (comp.getTier() == 1 && comp.getDivisionLevel() == 1) {
            teams.add(makeTeam("AF Omladinac", "OML", "Beograd", "Stadion Omladinca", 15000, "#f5a623", true, comp));
            teams.add(makeTeam("AF Partizan", "PAR", "Beograd", "Partizan CSStadium", 32000, "#000000", false, comp));
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

    private AfTeam makeTeam(String name, String shortName, String city, String stadium, int cap, String color, boolean human, CommonCompetition comp) {
        return AfTeam.builder()
                .name(name)
                .shortName(shortName)
                .city(city)
                .stadiumName(stadium)
                .stadiumCapacity(cap)
                .color(color)
                .humanControlled(human)
                .competition(comp)
                .build();
    }

    private static final String[] AI_NAMES = {"AF Vojvodina", "AF Crvena Zvezda", "AF Mega",
            "AF FMP", "AF Borac", "AF Spartak", "AF Radnički", "AF Sloboda", "AF Tamiš",
            "AF Sloga", "AF Dunav", "AF Vršac", "AF Konstantin", "AF Mladost",
            "AF Metalac", "AF Napredak", "AF Jedinstvo", "AF Budućnost", "AF Zlatibor"};
    private static final String[] AI_SHORT = {"VOJ", "CZV", "MEG", "FMP", "BOR", "SPA", "RAD", "SLO", "TAM",
            "SLG", "DUN", "VRS", "KON", "MLA", "MET", "NAP", "JED", "BUD", "ZLA"};
    private static final String[] AI_CITIES = {"Novi Sad", "Beograd", "Beograd", "Beograd", "Čačak",
            "Subotica", "Kragujevac", "Užice", "Pančevo", "Kraljevo", "Stari Banovci",
            "Vršac", "Niš", "Valjevo", "Gornji Milanovac", "Kruševac", "Bijelo Polje",
            "Podgorica", "Zlatibor"};
    private static final String[] AI_COLORS = {"#e74c3c", "#c0392b", "#2ecc71", "#3498db", "#f39c12",
            "#1abc9c", "#9b59b6", "#2c3e50", "#16a085", "#d35400", "#2980b9",
            "#8e44ad", "#e67e22", "#27ae60", "#7f8c8d", "#e84393", "#00cec9",
            "#6c5ce7", "#fdcb6e"};

    private int aiTeamCounter = 0;

    private AfTeam makeAiTeam(CommonCompetition comp) {
        int idx2 = aiTeamCounter % AI_NAMES.length;
        int group = aiTeamCounter / AI_NAMES.length;
        aiTeamCounter++;
        String suffix = group > 0 ? " (" + group + ")" : "";
        return AfTeam.builder()
                .name(AI_NAMES[idx2] + suffix)
                .shortName(AI_SHORT[idx2] + suffix.replace(" ", "").replace("(", "").replace(")", ""))
                .city(AI_CITIES[idx2])
                .stadiumName(AI_NAMES[idx2] + " Field")
                .stadiumCapacity(10000 + random.nextInt(40000))
                .color(AI_COLORS[idx2])
                .humanControlled(false)
                .competition(comp)
                .build();
    }

    private void createPlayersForTeams(List<AfTeam> teams) {
        for (AfTeam team : teams) {
            List<AfPlayer> players = generatePlayers(team);
            playerRepository.saveAll(players);
            team.setPlayers(players);
        }
        teamRepository.saveAll(teams);
    }

    private List<AfPlayer> generatePlayers(AfTeam team) {
        List<AfPlayer> players = new ArrayList<>();
        List<String> names = generateNames();
        AfPlayer.Position[] positions = {
                AfPlayer.Position.QB, AfPlayer.Position.RB, AfPlayer.Position.WR,
                AfPlayer.Position.WR, AfPlayer.Position.TE, AfPlayer.Position.OL,
                AfPlayer.Position.OL, AfPlayer.Position.OL,
                AfPlayer.Position.DE, AfPlayer.Position.DT, AfPlayer.Position.LB,
                AfPlayer.Position.LB, AfPlayer.Position.CB, AfPlayer.Position.CB,
                AfPlayer.Position.S, AfPlayer.Position.S, AfPlayer.Position.K, AfPlayer.Position.P
        };

        for (int i = 0; i < 18; i++) {
            AfPlayer.Position pos = positions[i % positions.length];

            int stamina = baseSkill();
            int strength = baseSkill();
            int pace = baseSkill();
            int playmaking = baseSkill();
            int passing = baseSkill();
            int running = baseSkill();
            int tackling = baseSkill();
            int shooting = baseSkill();

            switch (pos) {
                case QB -> { playmaking = highSkill(); passing = highSkill(); pace = 8 + random.nextInt(6); }
                case RB -> { running = highSkill(); strength = highSkill(); pace = highSkill(); }
                case WR -> { running = highSkill(); pace = highSkill(); playmaking = 8 + random.nextInt(6); }
                case TE -> { strength = highSkill(); tackling = 8 + random.nextInt(6); running = 8 + random.nextInt(6); }
                case OL -> { strength = 13 + random.nextInt(5); stamina = highSkill(); tackling = highSkill(); }
                case DE -> { strength = highSkill(); pace = 8 + random.nextInt(6); tackling = highSkill(); }
                case DT -> { strength = 13 + random.nextInt(5); tackling = highSkill(); stamina = highSkill(); }
                case LB -> { tackling = highSkill(); strength = highSkill(); pace = 8 + random.nextInt(6); }
                case CB -> { pace = highSkill(); tackling = 8 + random.nextInt(6); running = highSkill(); }
                case S -> { pace = highSkill(); tackling = 8 + random.nextInt(6); playmaking = highSkill(); }
                case K -> { shooting = 13 + random.nextInt(5); strength = 8 + random.nextInt(6); }
                case P -> { shooting = highSkill(); passing = highSkill(); strength = 8 + random.nextInt(6); }
            }

            AfPlayer player = AfPlayer.builder()
                    .name(names.get(i))
                    .position(pos)
                    .jerseyNumber(i + 1)
                    .injured(false)
                    .fatigue(random.nextInt(30))
                    .skillStamina(clamp(stamina))
                    .skillStrength(clamp(strength))
                    .skillPace(clamp(pace))
                    .skillPlaymaking(clamp(playmaking))
                    .skillPassing(clamp(passing))
                    .skillRunning(clamp(running))
                    .skillTackling(clamp(tackling))
                    .skillShooting(clamp(shooting))
                    .team(team)
                    .stats(new AfPlayerStats())
                    .build();

            players.add(player);
        }
        return players;
    }

    private void setupSeasonCompetition(CommonCompetition comp, CommonSeason season, List<AfTeam> compTeams) {
        AfSeasonCompetition sc = AfSeasonCompetition.builder()
                .competition(comp)
                .season(season)
                .finished(false)
                .build();
        sc = seasonCompetitionRepository.save(sc);

        for (AfTeam team : compTeams) {
            AfCompetitionEntry entry = AfCompetitionEntry.builder()
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

    private void generateRoundRobinFixtures(CommonCompetition comp, CommonSeason season, List<AfTeam> teams) {
        int numTeams = teams.size();
        int rounds = (numTeams - 1) * 2;
        LocalDateTime baseDate = LocalDateTime.of(SEASON_YEAR, 9, 15, 18, 0);

        List<AfTeam> rotated = new ArrayList<>(teams);
        for (int round = 0; round < numTeams - 1; round++) {
            for (int i = 0; i < numTeams / 2; i++) {
                AfTeam home = rotated.get(i);
                AfTeam away = rotated.get(numTeams - 1 - i);
                if (random.nextBoolean()) {
                    AfTeam t = home; home = away; away = t;
                }
                saveFixture(home, away, comp, season, round + 1, baseDate.plusDays(round * 7L));
                saveFixture(away, home, comp, season, round + 1 + (numTeams - 1), baseDate.plusDays((round + numTeams) * 7L));
            }
            AfTeam first = rotated.get(0);
            List<AfTeam> rest = new ArrayList<>(rotated.subList(1, rotated.size()));
            rotated = new ArrayList<>();
            rotated.add(first);
            rotated.add(rest.get(rest.size() - 1));
            rotated.addAll(rest.subList(0, rest.size() - 1));
        }
    }

    private void saveFixture(AfTeam home, AfTeam away, CommonCompetition comp, CommonSeason season, int round, LocalDateTime date) {
        AfMatchFixture fixture = AfMatchFixture.builder()
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

    private void assignOwnerTeam(CommonCompetition superLiga, List<AfTeam> allTeams) {
        Optional<User> owner = userRepository.findByUsernameOrEmail(OWNER_EMAIL);
        if (owner.isPresent()) {
            User user = owner.get();
            AfTeam omladinac = allTeams.stream()
                    .filter(t -> "AF Omladinac".equals(t.getName()))
                    .findFirst().orElse(null);
            if (omladinac != null) {
                user.setAmericanFootballTeam(omladinac);
                userRepository.save(user);
                log.info("AF Omladinac assigned to owner");
            }
        }
    }

    private List<String> generateNames() {
        String[] firstNames = {"Nikola", "Marko", "Stefan", "Nemanja", "Filip", "Dušan", "Petar",
                "Vladimir", "Dejan", "Milan", "Ivan", "Aleksandar", "Uroš", "Mladen",
                "Saša", "Zoran", "Darko", "Boris"};
        String[] lastNames = {"Kovačević", "Janković", "Petrović", "Nikolić", "Mitrović",
                "Stojanović", "Pavlović", "Đorđević", "Milosavljević", "Obradović",
                "Vasić", "Tomić", "Popović", "Ilić", "Marković", "Simić", "Đukić"};
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 18; i++) {
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
