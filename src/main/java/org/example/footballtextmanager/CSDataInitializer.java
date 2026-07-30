package org.example.footballtextmanager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballtextmanager.model.*;
import org.example.footballtextmanager.repository.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CSDataInitializer {

    private final CSCountryRepository csCountryRepository;
    private final CSCompetitionRepository csCompetitionRepository;
    private final CSSeasonCompetitionRepository csSeasonCompetitionRepository;
    private final CSCompetitionEntryRepository csCompetitionEntryRepository;
    private final CSTeamRepository csTeamRepository;
    private final CSPlayerRepository csPlayerRepository;

    private static final String[] SERBIAN_FIRST_NAMES = {
        "Marko", "Nikola", "Stefan", "Milan", "Dragan", "Zoran", "Dejan", "Slobodan",
        "Vladimir", "Ivan", "Aleksandar", "Petar", "Miloš", "Dušan", "Filip", "Lazar",
        "Andrija", "Matija", "Strahinja", "Vuk", "Bogdan", "Nemanja", "Jovan", "Stevan",
        "Branislav", "Goran", "Miloš", "Radovan", "Tihomir", "Željko"
    };

    private static final String[] SERBIAN_LAST_NAMES = {
        "Jovanović", "Petrović", "Nikolić", "Marković", "Đorđević", "Stojanović", "Ilić",
        "Stanković", "Pavlović", "Milošević", "Todorović", "Stevanović", "Kovačević",
        "Popović", "Savić", "Vasić", "Obradović", "Mitrović", "Radović", "Filipović",
        "Tadić", "Ivanović", "Sekulić", "Vuković", "Gajić", "Miljković", "Ristić"
    };

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureCSDataOnStartup() {
        if (csCountryRepository.count() > 0) {
            log.info("CS data already seeded");
            return;
        }

        log.info("Seeding CS (text manager) data...");

        // Serbia
        CSCountry serbia = new CSCountry();
        serbia.setName("Serbia");
        serbia.setIsoCode("SRB");
        serbia.setFlagImagePath("/images/flags/srb.png");
        serbia.setCurrencyCode("RSD");
        serbia.setReputation(55);
        serbia.setYouthRating(65);
        serbia = csCountryRepository.save(serbia);
        Random rng = new Random(42);

        // Link any existing CTeams that lack csCountry to Serbia
        List<CTeam> existingTeams = csTeamRepository.findAll();
        for (CTeam t : existingTeams) {
            if (t.getCsCountry() == null) {
                t.setCsCountry(serbia);
                csTeamRepository.save(t);
            }
        }

        // Serbia First League
        CSCompetition league = new CSCompetition();
        league.setName("Serbian First League");
        league.setType(CSCompetitionType.LEAGUE);
        league.setScope(CSCompetitionScope.NATIONAL);
        league.setTeamType(CSCompetitionTeamType.CLUB);
        league.setCsCountry(serbia);
        league.setTier(1);
        league.setDivisionLevel(1);
        league.setTeamsPerCompetition(16);
        league.setHasPlayoff(false);
        league.setHasPlayout(false);
        league.setPromotionSpots(1);
        league.setRelegationSpots(3);
        league.setReputationWeight(10);
        league.setHasSeeding(false);
        league = csCompetitionRepository.save(league);

        // Current season
        int currentYear = Year.now().getValue();
        CSSeasonCompetition season = new CSSeasonCompetition();
        season.setSeasonYear(currentYear);
        season.setCsCompetition(league);
        season.setFinished(false);
        season = csSeasonCompetitionRepository.save(season);

        // Link existing CTeams that lack CSCompetition to the league
        for (CTeam t : existingTeams) {
            if (t.getCSCompetition() == null) {
                t.setCSCompetition(league);
                csTeamRepository.save(t);
                CSCompetitionEntry entry = new CSCompetitionEntry();
                entry.setCsSeasonCompetition(season);
                entry.setCTeam(t);
                entry.setPoints(rng.nextInt(50));
                entry.setGoalsScored(10 + rng.nextInt(30));
                entry.setGoalsConceded(10 + rng.nextInt(30));
                entry.setPosition(1);
                entry.setWins(rng.nextInt(10));
                entry.setDraws(rng.nextInt(8));
                entry.setLosses(rng.nextInt(8));
                csCompetitionEntryRepository.save(entry);
            }
        }

        // Previous season (for historical data)
        CSSeasonCompetition prevSeason = new CSSeasonCompetition();
        prevSeason.setSeasonYear(currentYear - 1);
        prevSeason.setCsCompetition(league);
        prevSeason.setFinished(true);
        csSeasonCompetitionRepository.save(prevSeason);

        // Create 16 teams
        String[] teamNames = {
            "OFK Omladinac", "GFK Pobeda Ćuprija", "FK Radnički Svilajnac",
            "FK Timočanin", "FK Moravac", "FK Đerdap", "FK Župa",
            "FK Remont", "FK Budućnost", "FK Sloga", "FK Mladost",
            "FK Napredak", "FK Proleter", "FK Metalac", "FK Rudar",
            "OFK Kruševac"
        };

        List<CTeam> allTeams = new ArrayList<>();

        for (int i = 0; i < teamNames.length; i++) {
            String teamName = teamNames[i];
            CTeam team = csTeamRepository.findByName(teamName).orElseGet(() -> {
                CTeam newTeam = new CTeam();
                newTeam.setName(teamName);
                return newTeam;
            });
            team.setType(CSCompetitionTeamType.CLUB);
            team.setCsCountry(serbia);
            team.setCSCompetition(league);
            team.setBudget(500_000.0 + rng.nextInt(500_000));
            team.setReputation(40.0 + rng.nextDouble() * 30.0);
            team.setJuniorCoachSkill(30 + rng.nextInt(50));
            if (!team.isHumanControlled() && i == 0) team.setHumanControlled(true);
            team = csTeamRepository.save(team);

            // Create stadium only if team doesn't have one
            if (team.getCsStadium() == null) {
                CSStadium stadium = new CSStadium();
                stadium.setName("Stadion " + teamName.replaceAll("^(OFK|GFK|FK) ", ""));
                stadium.setCapacity(3000 + rng.nextInt(12000));
                stadium.setTicketPrice(300.0 + rng.nextDouble() * 500.0);
                stadium.setPitchQuality(50.0 + rng.nextDouble() * 40.0);
                stadium.setCondition(60 + rng.nextInt(35));
                stadium.setTrainingQuality(40 + rng.nextInt(40));
                team.setCsStadium(stadium);
                team = csTeamRepository.save(team);
            }

            // Create competition entry (skip if exists)
            Optional<CSCompetitionEntry> existingEntry = csCompetitionEntryRepository.findByCsSeasonCompetitionAndCTeam(season, team);
            if (existingEntry.isEmpty()) {
                CSCompetitionEntry entry = new CSCompetitionEntry();
                entry.setCsSeasonCompetition(season);
                entry.setCTeam(team);
                entry.setPoints(rng.nextInt(50));
                entry.setGoalsScored(10 + rng.nextInt(30));
                entry.setGoalsConceded(10 + rng.nextInt(30));
                entry.setPosition(i + 1);
                entry.setWins(entry.getGoalsScored() / 3);
                entry.setDraws(rng.nextInt(8));
                entry.setLosses(10 - entry.getWins() - entry.getDraws());
                if (entry.getLosses() < 0) entry.setLosses(0);
                csCompetitionEntryRepository.save(entry);
            }

            // Create 15 players per team (skip if exists)
            if (csPlayerRepository.countByCTeam(team) >= 15) continue;

            Set<String> usedNames = new HashSet<>();
            for (int j = 0; j < 15; j++) {
                CSPosition pos = pickPosition(j);
                CPlayer player = new CPlayer();
                player.setCTeam(team);
                player.setCSPosition(pos);
                player.setSquadNumber(j + 1);
                player.setAge(18 + rng.nextInt(15));
                player.setForm(4.0 + rng.nextDouble() * 6.0);
                player.setHeight(1.70 + rng.nextDouble() * 0.20);
                player.setWeight(65.0 + rng.nextDouble() * 20.0);

                CSSkills skills = new CSSkills();
                int base = 4 + rng.nextInt(8);
                skills.setStamina(5 + rng.nextInt(10));
                skills.setGoalkeeper(pos == CSPosition.GK ? 8 + rng.nextInt(10) : 1 + rng.nextInt(4));
                skills.setDefender(pos == CSPosition.DEF || pos == CSPosition.GK ? 7 + rng.nextInt(10) : 3 + rng.nextInt(6));
                skills.setPace(4 + rng.nextInt(12));
                skills.setTechnique(4 + rng.nextInt(12));
                skills.setPlaymaker(pos == CSPosition.MID ? 8 + rng.nextInt(10) : 3 + rng.nextInt(6));
                skills.setPassing(4 + rng.nextInt(12));
                skills.setStriker(pos == CSPosition.ATT ? 8 + rng.nextInt(10) : 3 + rng.nextInt(6));
                skills.setFatigue(0);
                skills.initializeExactFromVisibleIfNeeded();
                player.setCSSkills(skills);

                player.setRating(computeRating(skills, pos));
                player.setPlayerValue(10000.0 + rng.nextDouble() * 90000.0);
                player.setEarnings(500.0 + rng.nextDouble() * 4500.0);
                player.setTotalGoals(0);
                player.setTotalAssists(0);

                String name = uniqueName(rng, usedNames);
                player.setName(name);
                player = csPlayerRepository.save(player);
            }
            allTeams.add(team);
        }

        log.info("Seeded {} CS countries, {} competitions, {} season entries, {} teams with players",
                1, 1, 1, allTeams.size());
    }

    private CSPosition pickPosition(int index) {
        if (index == 0) return CSPosition.GK;
        if (index <= 4) return CSPosition.DEF;
        if (index <= 7) return CSPosition.MID;
        if (index <= 9) return CSPosition.WNG;
        return CSPosition.ATT;
    }

    private int computeRating(CSSkills skills, CSPosition pos) {
        return (int) Math.round(skills.getRatingScore(pos) / 10.0);
    }

    private String uniqueName(Random rng, Set<String> used) {
        for (int i = 0; i < 100; i++) {
            String first = SERBIAN_FIRST_NAMES[rng.nextInt(SERBIAN_FIRST_NAMES.length)];
            String last = SERBIAN_LAST_NAMES[rng.nextInt(SERBIAN_LAST_NAMES.length)];
            String name = first + " " + last;
            if (used.add(name)) return name;
        }
        String fallback = "Igrač " + (used.size() + 1);
        used.add(fallback);
        return fallback;
    }
}
