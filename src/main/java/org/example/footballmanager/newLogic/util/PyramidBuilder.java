package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.PromotionRule;
import org.example.footballmanager.newLogic.model.RuleType;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.PromotionRuleRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import java.util.Comparator;
import org.example.footballmanager.newLogic.util.players.PlayerFactory;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;

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
    private final org.example.footballmanager.newLogic.repository.CompetitionEntryRepository entries;
    private final org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository seasonCompetitions;

    public PyramidBuilder(CompetitionRepository competitions,
                          org.example.footballmanager.newLogic.repository.TeamRepository teams,
                          PromotionRuleRepository promotionRules,
                          PlayerFactory playerFactory,
                          SquadNumberAssigner squadNumbers,
                          SeasonService seasons,
                          org.example.footballmanager.newLogic.repository.CompetitionEntryRepository entries,
                          org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository seasonCompetitions) {
        this.competitions = competitions;
        this.teams = teams;
        this.promotionRules = promotionRules;
        this.playerFactory = playerFactory;
        this.squadNumbers = squadNumbers;
        this.seasons = seasons;
        this.entries = entries;
        this.seasonCompetitions = seasonCompetitions;
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
            // The pyramid is durable, but its static standing table is season-scoped. A country may
            // have been seeded after the season that is now being used for international qualification,
            // or it may already have the pyramid from an older season. Re-assert the table rows for the
            // requested season without creating players, fixtures or a second pyramid.
            Set<String> existingNames = existingClubNamesIn(country);
            for (Competition league : existing) {
                fillStaticDivision(league, country, seasonYear, league.getTier(), existingNames);
            }
            return new Result(existing.size(), 0, 0, true);
        }

        int divisions = 0;
        int clubs = 0;
        int players = 0;
        // One query per country, not one per club. See existingClubNamesIn.
        Set<String> existingNamesInScope = existingClubNamesIn(country);

        for (int tier = 1; tier <= DIVISIONS_PER_TIER.length; tier++) {
            for (int division = 1; division <= DIVISIONS_PER_TIER[tier - 1]; division++) {
                Competition league = createDivision(country, tier, division, seasonYear);
                divisions++;
                clubs += fillDivision(league, country, seasonYear, existingNamesInScope);
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
    /**
     * The average skill of a tier, which is what a seeded country's strength is written down as.
     *
     * <p>Tier 1 and national teams are 12, then one lower per tier below (owner's scale, 2026-09-30).
     * U21 is 10.
     *
     * <p>For a <b>simulated</b> country there are no players at all, so this number is never summed
     * into a squad — it is carried on the club's reputation instead, which is the one quality column a
     * club has when it has no players to average.
     */
    public static final int TIER_1_SKILL = 12;
    public static final int U21_SKILL = 10;

    public static int tierSkill(int tier) {
        return TIER_1_SKILL - (tier - 1);
    }

    /**
     * A country's pyramid built as a <b>fixture of the world, not a simulation of it</b>.
     *
     * <p>A simulated country is seeded with its divisions, its clubs and their ratings, and a standing
     * table — and nothing else. It plays no matches and holds those positions until its league is
     * activated, at which point {@link #build} takes over and it starts simulating for real.
     *
     * <p>The two things {@link #build} does that this deliberately skips, and why:
     *
     * <ul>
     *   <li><b>No players.</b> 46 countries x 25 players a club is 370k rows for a team that has never
     *       kicked a ball. Names are generated lazily, and only for a club that actually turns up
     *       against a human one.</li>
     *   <li><b>No fixtures.</b> A schedule for a league that will not be played is a thousand empty
     *       rows per division and thirty-one divisions per country.</li>
     * </ul>
     *
     * <p>The table is filled by <b>reputation descending</b>, so the standing is stable across boots and
     * the draw downstream is the same every time — which is the reason the owner wanted the draw to be
     * reproducible.
     */
    public Result buildStatic(Country country, int seasonYear) {
        List<Competition> existing = competitions
                .findByCountryIsoCodeAndType(country.getIsoCode(), CompetitionType.LEAGUE);
        if (!existing.isEmpty()) {
            log.info("{} already has {} league division(s); not building a second pyramid.",
                    country.getName(), existing.size());
            return new Result(existing.size(), 0, 0, true);
        }

        int divisions = 0;
        int clubs = 0;
        // One query for the country's existing club names instead of one per club. See
        // existingClubNamesIn.
        Set<String> existingNames = existingClubNamesIn(country);

        for (int tier = 1; tier <= DIVISIONS_PER_TIER.length; tier++) {
            for (int division = 1; division <= DIVISIONS_PER_TIER[tier - 1]; division++) {
                Competition league = createDivision(country, tier, division, seasonYear);
                divisions++;
                clubs += fillStaticDivision(league, country, seasonYear, tier, existingNames);
            }
        }

        log.info("Static pyramid for {}: {} division(s), {} club(s), no players, no fixtures.",
                country.getName(), divisions, clubs);
        return new Result(divisions, clubs, 0, false);
    }


    /**
     * Every club name already in this country, lower-cased, in one query.
     *
     * <p><b>This is the whole seeding bottleneck, and it was one line.</b> The builder asked the database
     * "does a club called X exist?" once per club:
     *
     * <pre>{@code
     * teams.findAllByNameIgnoreCase(name)   // LOWER(name) = LOWER(?)
     * }</pre>
     *
     * Measured against a 14,880-club table — the scale this world actually reaches:
     *
     * <pre>
     * LOWER(name) = ...        10.0 ms   ->  149 s for 14,880 lookups
     * left(name, 6) = ...       1.0 ms   ->   15 s
     * name = ...                1.6 ms   ->   24 s
     * </pre>
     *
     * A functional index cannot serve {@code LOWER(name) = } as written, so the cheapest fix is not to
     * make the query faster but to stop making it: **one query per country instead of one per club**,
     * and the existence check becomes a set membership test in memory. 14,880 round trips become 48.
     *
     * <p>The query is still issued for a name that <em>is</em> in the set — that is the re-seeding path,
     * where the club has to be loaded anyway — so the saving is largest exactly where it matters, on a
     * fresh world where nothing exists and every one of the old lookups returned empty.
     */
    private Set<String> existingClubNamesIn(Country country) {
        return new java.util.HashSet<>(teams.findNamesForCountry(country.getId()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList());
    }

    /** Ten clubs with ratings and a standing table. No squads, no schedule. */
    private int fillStaticDivision(Competition league, Country country, int seasonYear, int tier,
                                   Set<String> existingNames) {
        List<Team> made = new ArrayList<>();
        List<Team> fresh = new ArrayList<>();
        for (int index = 0; index < CLUBS_PER_DIVISION; index++) {
            String name = clubName(country, league, index);
            Team team = existingNames.contains(name.toLowerCase(Locale.ROOT))
                    ? teams.findAllByNameIgnoreCase(name).stream().findFirst().orElse(null)
                    : null;
            if (team == null) {
                team = new Team();
                team.setName(name);
                team.setCountry(country);
                team.setCompetition(league);
                team.setHumanControlled(false);
                team.setReputation(reputationFor(tier));
                team.setBudget(2_000_000.0 + (6 - tier) * 1_500_000.0);
                // **Saved in one batch, not one at a time.** This was `teams.save(team)` per club, so the
                // static world issued 14,260 individual INSERTs, each with its own flush — one round trip
                // per club to build a pyramid. Same rows, one call.
                fresh.add(team);
            }
            made.add(team);
        }
        if (!fresh.isEmpty()) {
            // saveAll returns managed copies carrying the generated ids, so `made` is rebuilt from them by
            // name — a name is unique inside a division — to keep the original slot order. The standing
            // table below sorts by reputation and then by name, and every club in a static division has
            // the *same* reputation, so this order is the order the table is written in.
            List<Team> saved = teams.saveAll(fresh);
            Map<String, Team> savedByName = new HashMap<>();
            saved.forEach(team -> savedByName.put(team.getName(), team));
            List<Team> ordered = new ArrayList<>(made.size());
            for (Team team : made) {
                Team persisted = savedByName.get(team.getName());
                ordered.add(persisted != null ? persisted : team);
            }
            made = ordered;
        }

        seasons.ensureEntriesForSeasonCompetition(league, seasonYear);

        // The standing table, strongest first, with the position written onto each row. This is what the
        // international cup qualification reads, so it has to exist even though nothing was played.
        made.sort(Comparator.comparingDouble((Team t) -> t.getReputation() == null ? 0 : t.getReputation())
                .reversed()
                .thenComparing(Team::getName));

        // **One read instead of two per club.** This loop used to ask for the season competition and then
        // for the club's entry on every single club: two queries per club, ten per division, and the
        // season competition is the same row every time. Across the static world that is 48 × 31 × 10 =
        // 14,880 divisions-clubs, so ~30,000 queries to write 14,260 standing rows — and the whole
        // "Seed other nations" button took over an hour.
        //
        // Same rows written, same order, same values: the season competition once, its entries once,
        // indexed by team id, mutated in place and handed to a single saveAll.
        SeasonCompetition sc = seasonCompetitions.findByCompetitionAndSeasonYear(league, seasonYear)
                .orElseThrow();
        Map<Long, CompetitionEntry> entryByTeam = new HashMap<>();
        for (CompetitionEntry entry : entries.findBySeasonCompetition(sc)) {
            if (entry.getTeam() != null && entry.getTeam().getId() != null) {
                entryByTeam.putIfAbsent(entry.getTeam().getId(), entry);
            }
        }

        List<CompetitionEntry> standing = new ArrayList<>(made.size());
        for (int position = 1; position <= made.size(); position++) {
            CompetitionEntry entry = entryByTeam.get(made.get(position - 1).getId());
            if (entry == null) {
                // A club with no entry means ensureEntriesForSeasonCompetition did not create one, and
                // silently skipping it would leave a division whose table cannot be read for qualification.
                throw new IllegalStateException("No season entry for " + made.get(position - 1).getName()
                        + " in " + league.getName() + " season " + seasonYear);
            }
            entry.setPosition(position);
            entry.setWins(0);
            entry.setDraws(0);
            entry.setLosses(0);
            entry.setPoints(0);
            standing.add(entry);
        }
        entries.saveAll(standing);
        return made.size();
    }

    /**
     * A club's reputation from its tier's skill, on the economy's 0-100 scale.
     *
     * <p>The tiers have to stay ordered and stay apart, because with no players reputation is the whole
     * of a club's quality and a tier-5 club that out-rated a tier-1 one would be an upset in the draw
     * rather than in a match.
     */
    private double reputationFor(int tier) {
        int skill = tierSkill(tier);
        return 30.0 + skill * 3.0;
    }

    private int fillDivision(Competition league, Country country, int seasonYear,
                             Set<String> existingNames) {
        List<Team> made = new ArrayList<>();
        for (int index = 0; index < CLUBS_PER_DIVISION; index++) {
            String clubName = clubName(country, league, index);
            Team team = existingNames.contains(clubName.toLowerCase(Locale.ROOT))
                    ? teams.findAllByNameIgnoreCase(clubName).stream().findFirst().orElse(null)
                    : null;
            if (team == null) {
                team = new Team();
                team.setName(clubName);
                team.setCountry(country);
                team.setCompetition(league);
                team.setHumanControlled(false);
                // Reputation on the club scale the economy reads, inside the band the seeder uses. It
                // is not an Elo number and must not be confused with a country's rating — the two share
                // a column name and nothing else.
                // **Clubs within a division are given distinct strengths.**
                //
                // Every club in a division used to be created with one identical reputation, a function of
                // tier alone. So the standing table sorted by reputation and then **by name** - and
                // continental qualification read that table, which made entry alphabetical for 47 of 48
                // countries. The comparator at the sort below was over identical values, so the name
                // tiebreak *was* the result.
                //
                // The spread is by index within the division: stable across installs (the same club name
                // gets the same strength every time, unlike a hash of the name), and wide enough that the
                // top and bottom of a division are distinguishable. **It is a starting ordering, not a
                // claim about any real club** - the manager world is not a replica of the real one.
                double withinDivision = CLUBS_PER_DIVISION <= 1
                        ? 0.0
                        : (double) index / (CLUBS_PER_DIVISION - 1);
                team.setReputation(40.0 + league.getTier() * 8.0 + withinDivision * 7.0);
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
