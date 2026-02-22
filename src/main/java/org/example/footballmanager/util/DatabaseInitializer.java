package org.example.footballmanager.util;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final CountryRepository countryRepository;
    private final CompetitionRepository competitionRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PromotionRuleRepository promotionRuleRepository; // Novi repo
    private final PlayerFactory playerFactory;
    private final TeamFactory teamFactory;
    private final Random random = new Random();

    @PostConstruct
    public void init() {
        log.info("Počinje inicijalizacija strukture za Srbiju...");
        initSerbianFootballStructure();
        log.info("Inicijalizacija završena.");
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
        createCupCompetitionIfNotExists(serbia, "Kup Srbije", 64, currentSeason); // primer: 64 tima
    }

    private Country createCountryIfNotExists(String name, String isoCode, int reputation, int youthRating) {
        return countryRepository.findByIsoCode(isoCode)
                .orElseGet(() -> {
                    Country c = new Country();
                    c.setName(name);
                    c.setIsoCode(isoCode);
                    c.setCurrencyCode(isoCode.equals("SRB") ? "RSD" : "EUR"); // primer
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
        comp.setReputationWeight(tier * 20); // primer: viši tier veća reputacija
        competitionRepository.save(comp);

        // Kreiraj SeasonCompetition
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
        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, season.getSeasonYear()).orElseThrow();

        long currentTeams = competitionEntryRepository.countBySeasonCompetition(sc);
        int toCreate = teamCount - (int) currentTeams;

        if (toCreate <= 0) {
            return;
        }

        if (includeOmladinac) {
            Team omladinac = teamFactory.findOrCreate("OFK Omladinac");
            addTeamToLeague(omladinac, sc);
            toCreate--;
        }

        for (int i = 0; i < toCreate; i++) {
            String name = getRandomTeamName();
            Team team = teamFactory.findOrCreate(name);
            addTeamToLeague(team, sc);
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
        competitionEntryRepository.save(entry);

        // Kreiraj igrače ako ih nema
        if (playerRepository.countByTeam(team) == 0) {
            if(Objects.equals(team.getName(), "OFK Omladinac"))
            {
                playerFactory.createOmladinacPlayers(team);
            }
            else {
                playerFactory.createRandomTeamPlayers(team.getName(), team);
            }
        }
    }

    private String getRandomTeamName() {
        String[] prefixes = {"FK", "OFK", "RFK", "SK", "TSK", "Partizan", "Radnik", "Radnički","Sloga","Sloboda", "Proleter", "Železničar", "Crvena Zvezda"};
        String[] suffixes = {"Beograd", "Novi Sad", "Niš", "Kragujevac", "Subotica", "Zrenjanin", "Surdulica", "Bečej", "Jagodina", "Svilajnac"};
        return prefixes[random.nextInt(prefixes.length)] + " " + suffixes[random.nextInt(suffixes.length)];
    }

    private void addPromotionRulesForLeagues(Season season) {
        // Primer za Tier 1
        Competition tier1 = competitionRepository.findByNameAndCountryIsoCode("Superliga Srbije", "SRB").orElseThrow();
        addPromotionRule(tier1, RuleType.RELEGATION, 9, 10, null, 2, false); // Poslednja 2 direktno ispadaju
        addPromotionRule(tier1, RuleType.PLAYOFF, 7, 8, null, 2, true); // Naredna 2 u baraz sa Tier 2

        // Dodaj slično za ostale tiere (po tvom opisu)
        // ...
    }

    private void addPromotionRule(Competition competition, RuleType type, int positionFrom, int positionTo, Competition target, int spots, boolean isPlayoff) {
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
        cup.setSeededTeamsCount(teamsCount / 2); // primer: pola nosilaca
        competitionRepository.save(cup);

        createSeasonCompetitionIfNotExists(cup, season);

        return cup;
    }
}