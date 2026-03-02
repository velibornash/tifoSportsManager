package org.example.footballmanager.util;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.ResetService;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.teams.TeamFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final CountryRepository countryRepository;
    private final CompetitionRepository competitionRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PromotionRuleRepository promotionRuleRepository;
    private final PlayerFactory playerFactory;
    private final TeamFactory teamFactory;
    private final PasswordEncoder encoder;  // Spring Security BCrypt encoder
    private final Random random = new Random();
    private final ResetService resetService;

    @PostConstruct
    public void init() {
        log.info("Počinje automatska inicijalizacija baze podataka...");
        resetService.resetDatabase();
        // 1. Ako baza nema zemalja – pokreni punu inicijalizaciju strukture
        if (countryRepository.count() == 0) {
            log.info("Baza je prazna – pokrećem punu inicijalizaciju strukture...");
            initSerbianFootballStructure();
        } else {
            log.info("Baza već ima podatke – preskačem inicijalizaciju strukture.");
        }

        // 2. Kreiraj Owner korisnika ako ne postoji (sada baza ima strukturu, timovi postoje)
        createOwnerUserIfNotExists();

        log.info("Inicijalizacija završena.");
    }

    private void createOwnerUserIfNotExists() {
        if (userRepository.findByUsername("velibor@example.com").isEmpty()) {
            User owner = new User();
            owner.setUsername("velibor@example.com");
            owner.setEmail("velibor@example.com");

            // Hash lozinke – koristi encoder
            owner.setPassword(encoder.encode("A12345!"));

            owner.setRole(UserRole.OWNER);

            // Pronađi Omladinac (pretpostavljam da postoji nakon inicijalizacije)
            Team omladinac = teamRepository.findByName("OFK Omladinac")
                    .orElseGet(() -> {
                        log.warn("Omladinac nije pronađen – kreira se placeholder tim");
                        Team temp = teamFactory.findOrCreate("OFK Omladinac");
                        teamRepository.save(temp);
                        return temp;
                    });

            owner.setTeam(omladinac);
            userRepository.save(owner);

            log.info("Kreiran Owner korisnik 'velibor' sa timom OFK Omladinac (ID: {})", omladinac.getId());
        } else {
            log.info("Owner korisnik 'velibor' već postoji – preskačem kreiranje.");
        }
    }

    @Transactional
    private void initSerbianFootballStructure() {
        // 1. Država – Srbija + još nekoliko (modularno)
        createCountryIfNotExists("Srbija", "SRB", 65, 70);
        createCountryIfNotExists("Bosna i Hercegovina", "BIH", 55, 60);
        createCountryIfNotExists("Crna Gora", "MNE", 50, 55);
        createCountryIfNotExists("Hrvatska", "HRV", 70, 75);
        createCountryIfNotExists("Slovenija", "SVN", 60, 65);
        createCountryIfNotExists("Severna Makedonija", "MKD", 45, 50);
        createCountryIfNotExists("Nemačka", "DEU", 95, 95);
        createCountryIfNotExists("Engleska", "GBR", 95, 90);
        createCountryIfNotExists("Brazil", "BRA", 90, 85);

        Country serbia = countryRepository.findByIsoCode("SRB").orElseThrow();

        // 2. Kreiraj tekuću sezonu (2025)
        Season currentSeason = createSeasonIfNotExists(2025, "2025/2026 Season");

        // 3. Kreiraj lige ako ne postoje
        Competition tier1 = createLeagueIfNotExists(serbia, 1, "Superliga Srbije", 1, 10, currentSeason);
        createLeagueIfNotExists(serbia, 2, "Prva liga Srbije Grupa A", 1, 10, currentSeason);
        createLeagueIfNotExists(serbia, 2, "Prva liga Srbije Grupa B", 2, 10, currentSeason);

        // Tier 3 – 4 lige
        for (int i = 1; i <= 4; i++) {
            createLeagueIfNotExists(serbia, 3, "Srpska liga Grupa " + (char)('A' + i - 1), i, 10, currentSeason);
        }

        // Tier 4 – 8 liga
        for (int i = 1; i <= 8; i++) {
            createLeagueIfNotExists(serbia, 4, "Okružna liga Grupa " + i, i, 10, currentSeason);
        }

        // Tier 5 – 16 liga
        for (int i = 1; i <= 16; i++) {
            createLeagueIfNotExists(serbia, 5, "Opštinska liga Grupa " + i, i, 10, currentSeason);
        }

        // 4. Popuni timove u Tier 1 (Superliga) – obavezno Omladinac + 9 random/stvarnih
        populateLeagueWithTeams(tier1, 10, true, currentSeason);

        // Ostale lige popuni random timovima
        competitionRepository.findAll().stream()
                .filter(c -> c.getCountry().getIsoCode().equals("SRB") && c.getTier() > 1)
                .forEach(league -> populateLeagueWithTeams(league, 10, false, currentSeason));

        // 5. Dodaj PromotionRule za lige
        addPromotionRulesForLeagues(currentSeason);

        // 6. Dodaj Kup Srbije (nacionalni kup)
        createCupCompetitionIfNotExists(serbia, "Kup Srbije", 64, currentSeason);
    }

    private Country createCountryIfNotExists(String name, String isoCode, int reputation, int youthRating) {
        return countryRepository.findByIsoCode(isoCode)
                .orElseGet(() -> {
                    Country c = new Country();
                    c.setName(name);
                    c.setIsoCode(isoCode);
                    c.setCurrencyCode(isoCode.equals("SRB") ? "RSD" : "EUR");
                    c.setReputation(reputation);
                    c.setYouthRating(youthRating);
                    return countryRepository.save(c);
                });
    }

    private Season createSeasonIfNotExists(int year, String description) {
        return seasonRepository.findBySeasonYear(year)
                .orElseGet(() -> {
                    Season s = new Season();
                    s.setSeasonYear(year);
                    s.setDescription(description);
                    return seasonRepository.save(s);
                });
    }

    private Competition createLeagueIfNotExists(Country country, int tier, String name, int divisionLevel, int teamsCount, Season season) {
        Optional<Competition> existing = competitionRepository.findByNameAndCountryIsoCode(name, country.getIsoCode());
        if (existing.isPresent()) {
            return existing.get();
        }

        Competition comp = new Competition();
        comp.setName(name);
        comp.setType(CompetitionType.LEAGUE);
        comp.setScope(CompetitionScope.NATIONAL);
        comp.setTeamType(CompetitionTeamType.CLUB);
        comp.setCountry(country);
        comp.setTier(tier);
        comp.setDivisionLevel(divisionLevel);
        comp.setTeamsPerCompetition(teamsCount);
        comp.setReputationWeight(tier * 20);
        competitionRepository.save(comp);

        createSeasonCompetitionIfNotExists(comp, season);
        return comp;
    }

    private SeasonCompetition createSeasonCompetitionIfNotExists(Competition competition, Season season) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setSeasonYear(season.getSeasonYear());
        sc.setCompetition(competition);
        sc.setFinished(false);
        return seasonCompetitionRepository.save(sc);
    }

    private void populateLeagueWithTeams(Competition league, int teamCount, boolean includeOmladinac, Season season) {
        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow(() -> new RuntimeException("Sezona za ligu nije pronađena"));

        long currentTeams = competitionEntryRepository.countBySeasonCompetition(sc);
        int toCreate = teamCount - (int) currentTeams;

        if (toCreate <= 0) {
            log.info("Liga {} već ima {} timova → preskačem", league.getName(), currentTeams);
            return;
        }

        Set<String> usedNamesInLeague = competitionEntryRepository.findBySeasonCompetition(sc)
                .stream()
                .map(entry -> entry.getTeam().getName())
                .collect(Collectors.toSet());

        int attempts = 0;
        final int maxAttempts = 100;

        // 1. Dodaj Omladinac ako treba i ako ga nema
        if (includeOmladinac && !usedNamesInLeague.contains("OFK Omladinac")) {
            Team omladinac = teamFactory.findOrCreate("OFK Omladinac");
            addTeamToLeague(omladinac, sc);
            usedNamesInLeague.add("OFK Omladinac");
            toCreate--;
            log.info("Dodat Omladinac u ligu: {}", league.getName());
        }

        // 2. Dodaj preostale timove
        while (toCreate > 0 && attempts < maxAttempts) {
            String candidateName = getRandomTeamName();
            if (usedNamesInLeague.contains(candidateName)) {
                attempts++;
                continue;
            }

            Team team = teamFactory.findOrCreate(candidateName);
            if (competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, team).isEmpty()) {
                addTeamToLeague(team, sc);
                usedNamesInLeague.add(candidateName);
                toCreate--;
                log.info("Dodat tim {} u ligu {}", candidateName, league.getName());
            }
            attempts++;
        }

        if (toCreate > 0) {
            log.warn("Nisam uspeo da popunim ligu {} sa {} timova – ostalo je {} da se doda",
                    league.getName(), teamCount, toCreate);
        }
    }

    private void addTeamToLeague(Team team, SeasonCompetition sc) {
        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(sc);
        entry.setTeam(team);
        entry.setPoints(0);
        entry.setGoalsScored(0);
        entry.setGoalsConceded(0);
        entry.setPosition(0);
        entry.setWins(0);
        entry.setDraws(0);
        entry.setLosses(0);
        competitionEntryRepository.save(entry);

        // Kreiraj igrače ako ih nema
        if (playerRepository.countByTeam(team) == 0) {
            if (Objects.equals(team.getName(), "OFK Omladinac")) {
                playerFactory.createOmladinacPlayers(team);
            } else {
                playerFactory.createRandomTeamPlayers(team.getName(), team);
            }
        }
    }

    private String getRandomTeamName() {
        String[] prefixes = {"FK", "OFK", "RFK", "SK", "TSK", "NK"};
        String[] suffixes = {
                "Beograd", "Novi Sad", "Niš", "Kragujevac", "Subotica", "Zrenjanin", "Čačak", "Kraljevo",
                "Smederevo", "Leskovac", "Užice", "Valjevo", "Vranje", "Šabac", "Zaječar", "Pančevo",
                "Požarevac", "Surdulica", "Loznica", "Bor", "Prokuplje", "Gornji Milanovac"
        };
        String[] extras = {"United", "City", "1893", "1919", "Sport", "Metalac", "Radnički", "Sloga"};

        String base = prefixes[random.nextInt(prefixes.length)] + " " + suffixes[random.nextInt(suffixes.length)];
        if (random.nextBoolean()) {
            base += " " + extras[random.nextInt(extras.length)];
        }
        return base;
    }

    private void addPromotionRulesForLeagues(Season season) {
        Competition tier1 = competitionRepository.findByNameAndCountryIsoCode("Superliga Srbije", "SRB").orElseThrow();
        addPromotionRule(tier1, RuleType.RELEGATION, 9, 10, null, 2, false);
        addPromotionRule(tier1, RuleType.PLAYOFF, 7, 8, null, 2, true);
        // Dodaj ostale po potrebi
    }

    private void addPromotionRule(Competition competition, RuleType type, int positionFrom, int positionTo,
                                  Competition target, int spots, boolean isPlayoff) {
        PromotionRule rule = new PromotionRule();
        rule.setCompetition(competition);
        rule.setRuleType(type);
        rule.setPositionFrom(positionFrom);
        rule.setPositionTo(positionTo);
        rule.setTargetCompetition(target);
        rule.setIsPlayoff(isPlayoff);
        promotionRuleRepository.save(rule);
    }

    private Competition createCupCompetitionIfNotExists(Country country, String name, int teamsCount, Season season) {
        Optional<Competition> existing = competitionRepository.findByNameAndCountryIsoCode(name, country.getIsoCode());
        if (existing.isPresent()) {
            return existing.get();
        }

        Competition cup = new Competition();
        cup.setName(name);
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setCountry(country);
        cup.setTeamsPerCompetition(teamsCount);
        cup.setHasSeeding(true);
        cup.setSeededTeamsCount(teamsCount / 2);
        competitionRepository.save(cup);

        createSeasonCompetitionIfNotExists(cup, season);
        return cup;
    }
}