package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.PromotionRule;
import org.example.footballmanager.newLogic.model.RuleType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.PromotionRuleRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.util.players.PlayerFactory;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a playable five-tier club pyramid for a country (owner, 2026-09-30).
 *
 * <p>{@code CountryState.ACTIVE} existed and nothing read it. It was set once for Serbia by the
 * catalogue seeder, styled one way on the World page, and had no other meaning — so the admin panel the
 * owner asked for would have been a button that flipped a label. A country is active when it has
 * football in it: divisions, clubs, squads, a table and a fixture list.
 *
 * <p><b>Reuses the canonical season machinery</b> — {@link SeasonService#ensureEntriesForSeasonCompetition}
 * and {@link SeasonService#ensureDoubleRoundRobinSchedule} — rather than writing fixtures here. The
 * calendar is the thing that decides a week and a day, and a second fixture writer would be a second
 * opinion about it. That is exactly how four rounds once ended up in one week.
 *
 * <p><b>Not the Serbia path.</b> {@code DatabaseInitializer} seeds Serbia with real club names, the
 * Šid municipal league and two hand-authored squads, and every test in the suite runs through it.
 * Rewriting it to call this would be a change to the world build for no gain, and a country activated
 * from here is a different operation: generated names, one standard per tier, no human club. The shape
 * is the same; the content is not, and that difference is the reason they are two methods.
 */
@Service
public class PyramidBuilder {

    private static final Logger log = LoggerFactory.getLogger(PyramidBuilder.class);

    /** How many divisions sit in each tier, top to bottom. 1 + 2 + 4 + 8 + 16 = 31. */
    public static final int[] DIVISIONS_PER_TIER = {1, 2, 4, 8, 16};

    public static final int CLUBS_PER_DIVISION = 10;

    private final CompetitionRepository competitions;
    private final org.example.footballmanager.newLogic.repository.TeamRepository teams;
    private final PromotionRuleRepository promotionRules;
    private final PlayerFactory playerFactory;
    private final SquadNumberAssigner squadNumbers;
    private final SeasonService seasons;

    public PyramidBuilder(CompetitionRepository competitions,
                          org.example.footballmanager.newLogic.repository.TeamRepository teams,
                          PromotionRuleRepository promotionRules,
                          PlayerFactory playerFactory,
                          SquadNumberAssigner squadNumbers,
                          SeasonService seasons) {
        this.competitions = competitions;
        this.teams = teams;
        this.promotionRules = promotionRules;
        this.playerFactory = playerFactory;
        this.squadNumbers = squadNumbers;
        this.seasons = seasons;
    }

    /** What one pyramid cost, so the panel can show the owner what the button did. */
    public record Result(int divisions, int clubs, int players, boolean alreadyBuilt) {

        public int total() {
            return divisions + clubs + players;
        }
    }

    /**
     * Gives a country its divisions, clubs, squads, tables and fixtures.
     *
     * <p>Idempotent by <b>the divisions that already exist</b>, not by the country's state flag. A
     * country can be SIMULATED and still have a pyramid — Serbia is ACTIVE with 312 clubs, but the
     * seeder that built them is a different code path and the flag is not proof of anything. Asking the
     * database is.
     */
    @Transactional
    public Result build(Country country, int seasonYear) {
        List<Competition> existing = competitions
                .findByCountryIsoCodeAndType(country.getIsoCode(), CompetitionType.LEAGUE);
        if (!existing.isEmpty()) {
            log.info("{} already has {} league division(s); not building a second pyramid.",
                    country.getName(), existing.size());
            return new Result(existing.size(), 0, 0, true);
        }

        int divisions = 0;
        int clubs = 0;
        int players = 0;

        for (int tier = 1; tier <= DIVISIONS_PER_TIER.length; tier++) {
            for (int division = 1; division <= DIVISIONS_PER_TIER[tier - 1]; division++) {
                Competition league = createDivision(country, tier, division, seasonYear);
                divisions++;
                clubs += fillDivision(league, country, seasonYear);
                // <b>Every division, not just the top flight.</b> The first version scheduled only the
                // premier league, which produced thirty divisions with clubs, table rows and no fixture
                // list at all — a league that never plays a match and looks complete from the outside.
                // The test that caught it walks all thirty-one and asks each one for its fixtures,
                // because "the pyramid exists" is exactly the kind of claim that is true of a table
                // and false of a football league.
                //
                // The calendar dates round one from the current game date, so a division created
                // mid-season starts playing this week rather than being born six weeks in the past.
                seasons.ensureDoubleRoundRobinSchedule(league, seasonYear);
            }
        }

        addPromotionRuleForTopFlight(country);

        log.info("Pyramid for {}: {} division(s), {} club(s), {} player(s).",
                country.getName(), divisions, clubs, players);
        return new Result(divisions, clubs, players, false);
    }

    private Competition createDivision(Country country, int tier, int division, int seasonYear) {
        String name = divisionName(country, tier, division);
        Optional<Competition> existing =
                competitions.findByNameAndCountryIsoCode(name, country.getIsoCode());
        if (existing.isPresent()) {
            return existing.get();
        }

        Competition competition = new Competition();
        competition.setName(name);
        competition.setType(CompetitionType.LEAGUE);
        competition.setScope(CompetitionScope.NATIONAL);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition.setCountry(country);
        competition.setTier(tier);
        competition.setDivisionLevel(division);
        competition.setTeamsPerCompetition(CLUBS_PER_DIVISION);
        competition.setReputationWeight(tier * 20);
        competition = competitions.save(competition);

        seasons.ensureSeasonCompetition(competition, seasonYear);
        return competition;
    }

    /**
     * Generated names, in English, carrying the country.
     *
     * <p>The country is in the name on purpose. Two clubs are allowed to share a name but two
     * <em>competitions</em> in different countries are not, and the lookup key is name plus ISO — so a
     * division called "First Division A" in two countries would have been one row, and one country's
     * clubs would have played in another's league.
     */
    private String divisionName(Country country, int tier, int division) {
        String base = switch (tier) {
            case 1 -> "Premier Division";
            case 2 -> "First Division";
            case 3 -> "Second Division";
            case 4 -> "Regional Division";
            default -> "Municipal Division";
        };
        return division == 1 && tier == 1
                ? country.getName() + " " + base
                : country.getName() + " " + base + " " + letter(division);
    }

    private String letter(int division) {
        return String.valueOf((char) ('A' + division - 1));
    }

    /** Ten clubs with a squad each, then the table rows and the fixture list. */
    private int fillDivision(Competition league, Country country, int seasonYear) {
        List<Team> made = new ArrayList<>();
        for (int index = 0; index < CLUBS_PER_DIVISION; index++) {
            Optional<Team> existingClub = teams.findAllByNameIgnoreCase(clubName(country, league, index)).stream().findFirst();
            Team team = existingClub.orElse(null);
            if (team == null) {
                team = new Team();
                team.setName(clubName(country, league, index));
                team.setCountry(country);
                team.setCompetition(league);
                team.setHumanControlled(false);
                // Reputation on the club scale the economy reads, inside the band the seeder uses. It
                // is not an Elo number and must not be confused with a country's rating — the two share
                // a column name and nothing else.
                team.setReputation(40.0 + league.getTier() * 8.0);
                team.setBudget(2_000_000.0 + (6 - league.getTier()) * 1_500_000.0);
                team = teams.save(team);
            }
            // The squad is generated to this division's standard, so a club in the municipal tier is
            // genuinely weaker than one in the premier tier rather than a name in a lower division.
            playerFactory.createRandomTeamPlayers(team.getName(), team);
            squadNumbers.assignMissingNumbers(team);
            made.add(team);
        }

        seasons.ensureEntriesForSeasonCompetition(league, seasonYear);
        return made.size();
    }

    /**
     * The club name, and it has to be unique inside the country.
     *
     * <p>It takes the division letter as well as the division's short name. Trimming "First Division A"
     * and "First Division B" to "First" produced two divisions of one tier whose clubs were all called
     * {@code GER First FC01} — and {@code findByName} <b>throws</b> on a duplicate rather than
     * returning the first, so the second division would have failed the activation outright.
     */
    private String clubName(Country country, Competition league, int index) {
        String code = country.getIsoCode() == null ? country.getName() : country.getIsoCode();
        int division = league.getDivisionLevel() == null ? 1 : league.getDivisionLevel();
        String shortName = shortDivisionName(league.getTier()) + (division > 1 ? letter(division) : "");
        return code + " " + shortName + " " + String.format("FC%02d", index + 1);
    }

    private String shortDivisionName(Integer tier) {
        return switch (tier == null ? 1 : tier) {
            case 1 -> "Premier";
            case 2 -> "First";
            case 3 -> "Second";
            case 4 -> "Regional";
            default -> "Municipal";
        };
    }

    /**
     * Relegation out of the top flight, and a playoff for the places below it.
     *
     * <p>Same two rules Serbia has, including the gap that <b>relegation has no target competition</b> —
     * the seeded pyramid's rule sets {@code target = null}, so a relegated club leaves the top flight
     * and does not arrive anywhere. That is a real hole in the existing world and it is called out on
     * the board as its own task rather than quietly fixed inside a country activation.
     */
    private void addPromotionRuleForTopFlight(Country country) {
        Competition topFlight = topFlight(country);
        if (topFlight == null || promotionRules.count() > 0 && hasRuleFor(topFlight)) {
            return;
        }
        PromotionRule relegation = new PromotionRule();
        relegation.setCompetition(topFlight);
        relegation.setRuleType(RuleType.RELEGATION);
        relegation.setPositionFrom(9);
        relegation.setPositionTo(10);
        relegation.setTargetCompetition(null);
        relegation.setIsPlayoff(false);
        promotionRules.save(relegation);

        PromotionRule playoff = new PromotionRule();
        playoff.setCompetition(topFlight);
        playoff.setRuleType(RuleType.PLAYOFF);
        playoff.setPositionFrom(7);
        playoff.setPositionTo(8);
        playoff.setTargetCompetition(null);
        playoff.setIsPlayoff(true);
        promotionRules.save(playoff);
    }

    private boolean hasRuleFor(Competition competition) {
        return promotionRules.findAll().stream()
                .anyMatch(rule -> rule.getCompetition() != null
                        && rule.getCompetition().getId().equals(competition.getId()));
    }

    private Competition topFlight(Country country) {
        return competitions
                .findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc(
                        country.getIsoCode(), CompetitionType.LEAGUE)
                .stream()
                .filter(league -> league.getTier() != null && league.getTier() == 1)
                .findFirst()
                .orElse(null);
    }
}
