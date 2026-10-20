package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.FantasyTeam;
import org.tifo.footballmanager.newLogic.model.FantasyTeamScore;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyTeamScoreRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.SeasonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fantasy squad survives being saved, and a refused one changes nothing (owner, 2026-10-17).
 *
 * <p>Three claims, and the third is the one that would be missed. That the pool prices a real pyramid;
 * that a legal squad is stored and read back with its eleven, its captain and its bench intact; and that a
 * <b>refused</b> save leaves the squad that was already there alone. The last one is the shape of a save
 * that clears the fifteen and then validates — which loses a manager his team for getting one pick wrong,
 * and looks like a working save right up until somebody notices.
 */
class FantasySquadPersistenceTest extends BaseTest {

    @Autowired private FantasySquadService squads;
    @Autowired private FantasyScoringService scoring;
    @Autowired private SeasonService seasons;
    @Autowired private FantasyTeamRepository fantasyTeams;
    @Autowired private FantasyTeamScoreRepository teamScores;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private UserRepository users;

    @Test
    @Transactional
    @DisplayName("the pool prices a five-tier pyramid and says which countries can host a league")
    void thePoolPricesThePyramid() {
        World world = world();
        FantasyPool pool = squads.pool(world.countryId());

        assertTrue(pool.eligible(), "a five-tier pyramid is exactly what the rules need");
        assertEquals(5, pool.tierCount());
        assertEquals(60, pool.players().size(), "five tiers, three clubs, four positions each");

        // Every band has three players, so every band has three different prices — which is the whole
        // reason the nudge exists. A band with one price is a band where the budget cannot bite.
        Map<String, List<Double>> byBand = new HashMap<>();
        for (FantasyPoolPlayer player : pool.players()) {
            byBand.computeIfAbsent(player.tier() + "/" + player.fantasyPosition(), k -> new ArrayList<>())
                    .add(player.price());
        }
        for (Map.Entry<String, List<Double>> band : byBand.entrySet()) {
            assertEquals(3, band.getValue().size(), band.getKey() + " should hold three players");
            long distinct = band.getValue().stream().distinct().count();
            assertTrue(distinct > 1, band.getKey() + " came out at a single price, so nothing can be traded");
        }

        // And the owner's ordering survives at the base: the best tier-1 attacker costs more than the
        // worst tier-1 midfielder, and a tier-1 player costs more than the same position one tier down.
        double bestTier1Attacker = priceOf(pool, 1, Position.ATT, true);
        double worstTier1Midfielder = priceOf(pool, 1, Position.MID, false);
        assertTrue(bestTier1Attacker > worstTier1Midfielder,
                "an attacker must be dearer than a midfielder in the same tier");
    }

    @Test
    @Transactional
    @DisplayName("a legal squad is saved, read back, and priced")
    void aLegalSquadIsSavedAndReadBack() {
        World world = world();
        FantasyPool pool = squads.pool(world.countryId());
        List<FantasySquadValidator.Selection> selections = legalSelections(pool);

        FantasySquadService.SaveResult result = squads.save(world.userId(), world.countryId(), selections);
        assertTrue(result.ok(), "a legal squad must save: " + result.violations());

        FantasySquad stored = squads.squad(world.userId(), world.countryId()).orElseThrow();
        assertEquals(15, stored.picks().size());
        assertEquals(11, stored.picks().stream().filter(FantasySquad.FantasySquadPick::starter).count());
        assertEquals(1, stored.picks().stream().filter(FantasySquad.FantasySquadPick::captain).count());
        assertEquals(4, stored.picks().stream().filter(p -> !p.starter()).count(),
                "the bench is the reserve goalkeeper and three substitutes");

        assertTrue(stored.currentPrice() <= FantasyPricing.BUDGET,
                "a saved squad must be inside the budget: " + stored.currentPrice());
        assertTrue(stored.remaining() >= 0, "and it must have something left: " + stored.remaining());
        assertEquals(stored.currentPrice(), stored.purchasePrice(), 0.001,
                "nothing has changed price yet, so what was paid is what it costs");

        // The starters are read first, so the read is the shape the pitch will draw.
        List<FantasySquad.FantasySquadPick> picks = stored.picks();
        for (int i = 0; i < 11; i++) {
            assertTrue(picks.get(i).starter(), "pick " + i + " should be a starter");
        }
        assertFalse(picks.get(11).starter(), "the bench starts at the twelfth pick");
        assertEquals(Integer.valueOf(0), picks.get(11).benchOrder(),
                "the reserve goalkeeper is first on the bench");
    }

    @Test
    @Transactional
    @DisplayName("a re-save keeps what was already paid, and a refused save changes nothing")
    void aRefusedSaveLeavesTheSquadAlone() {
        World world = world();
        FantasyPool pool = squads.pool(world.countryId());
        List<FantasySquadValidator.Selection> selections = legalSelections(pool);
        assertTrue(squads.save(world.userId(), world.countryId(), selections).ok());

        FantasySquad first = squads.squad(world.userId(), world.countryId()).orElseThrow();
        double paid = first.purchasePrice();
        assertEquals(15, first.picks().size());

        // Fourteen players: refused, with a reason.
        FantasySquadService.SaveResult refused =
                squads.save(world.userId(), world.countryId(), selections.subList(0, 14));
        assertFalse(refused.ok(), "a squad of fourteen must be refused");
        assertTrue(refused.violations().stream()
                        .anyMatch(v -> v.code().equals(FantasySquadValidator.WRONG_SIZE)),
                "and it must say why: " + refused.violations());

        // The squad that was already there is untouched. This is the assertion that catches a save which
        // clears the picks before it validates.
        FantasySquad after = squads.squad(world.userId(), world.countryId()).orElseThrow();
        assertEquals(15, after.picks().size(), "a refused save must not empty the squad");
        assertEquals(paid, after.purchasePrice(), 0.001, "nor change what was paid");

        // And a legal re-save keeps the price paid for the players who stayed.
        assertTrue(squads.save(world.userId(), world.countryId(), selections).ok());
        FantasySquad again = squads.squad(world.userId(), world.countryId()).orElseThrow();
        assertEquals(paid, again.purchasePrice(), 0.001,
                "a player who survives a re-save keeps the price he was bought at");
        assertEquals(1, again.picks().stream().filter(FantasySquad.FantasySquadPick::captain).count());
    }

    @Test
    @Transactional
    @DisplayName("joining is free, one change a week is free, extras cost four, and an undo costs nothing")
    void changesAreCountedAgainstTheGameweekBaseline() {
        World world = world();
        FantasyPool pool = squads.pool(world.countryId());
        List<FantasySquadValidator.Selection> squad = legalSelections(pool);

        // **Joining is not fourteen transfers.** A new entry is its own baseline.
        FantasySquadService.SaveResult joined = squads.save(world.userId(), world.countryId(), squad);
        assertTrue(joined.ok(), "a legal squad must save: " + joined.violations());
        assertEquals(0, joined.squad().changes(), "picking your first squad is not a change");
        assertEquals(0, joined.squad().transferCost());

        // Saving the same fifteen again is still nothing.
        assertEquals(0, squads.save(world.userId(), world.countryId(), squad).squad().changes());

        // One change is the free one.
        List<FantasySquadValidator.Selection> one = replace(pool, squad, 1);
        FantasySquad afterOne = squads.save(world.userId(), world.countryId(), one).squad();
        assertEquals(1, afterOne.changes());
        assertEquals(0, afterOne.transferCost(), "the first change is free");

        // Three changes: two of them cost four points each.
        List<FantasySquadValidator.Selection> three = replace(pool, squad, 3);
        FantasySquad afterThree = squads.save(world.userId(), world.countryId(), three).squad();
        assertEquals(3, afterThree.changes());
        assertEquals(8, afterThree.transferCost(), "two extras at four points each");

        // **And undoing is free.** Measured against the gameweek's baseline and not against the last save,
        // which is what makes this zero: counting against the last save would charge him twice for
        // changing his mind.
        FantasySquad undone = squads.save(world.userId(), world.countryId(), squad).squad();
        assertEquals(0, undone.changes(), "back to the squad he started the week with");
        assertEquals(0, undone.transferCost(), "an undo is not a transfer");
    }

    @Test
    @Transactional
    @DisplayName("the transfer cost is taken off the gameweek it was charged for")
    void theCostIsDeductedFromTheGameweek() {
        World world = world();
        FantasyPool pool = squads.pool(world.countryId());
        List<FantasySquadValidator.Selection> squad = legalSelections(pool);
        assertTrue(squads.save(world.userId(), world.countryId(), squad).ok());

        List<FantasySquadValidator.Selection> three = replace(pool, squad, 3);
        assertEquals(8, squads.save(world.userId(), world.countryId(), three).squad().transferCost());

        int week = seasons.getCurrentWeek();
        int season = seasons.getActiveSeasonYear();
        scoring.scoreWeek(world.countryId(), season, week);

        FantasyTeam team = fantasyTeams
                .findByUserIdAndCountryIdAndSeasonYear(world.userId(), world.countryId(), season)
                .orElseThrow();
        List<FantasyTeamScore> scores = teamScores.findByFantasyTeamIdAndSeasonYear(team.getId(), season);
        assertEquals(1, scores.size(), "one row per gameweek");
        assertEquals(8, scores.get(0).getTransferCost(), "the cost is written down with the week");
        assertEquals(-8, scores.get(0).getPoints(),
                "no football was played in this fixture, so the cost is the whole total");
    }

    // ------------------------------------------------------------------ fixture

    private record World(Long countryId, Long userId) {
    }

    /**
     * A country with a five-tier pyramid, three clubs a tier and four positions a club.
     *
     * <p>Three clubs a tier because three players must come from each tier and no more than two from one
     * club, so a tier of two clubs would be 2+1 and every position would have to exist in both.
     */
    private World world() {
        Country country = new Country();
        country.setName("ZZ Fantasy " + UUID.randomUUID());
        country.setIsoCode("ZF");
        country.setReputation(1500);
        country.setYouthRating(1500);
        country = countries.save(country);

        for (int tier = 1; tier <= FantasyRules.TIERS_REQUIRED; tier++) {
            Competition competition = new Competition();
            competition.setName("ZZ Fantasy T" + tier + " " + UUID.randomUUID());
            competition.setType(CompetitionType.LEAGUE);
            competition.setTier(tier);
            competition.setTeamType(CompetitionTeamType.CLUB);
            competition.setCountry(country);
            competition = competitions.save(competition);

            for (int club = 0; club < 3; club++) {
                Team team = new Team();
                team.setName("ZZ Fantasy T" + tier + " C" + club + " " + UUID.randomUUID());
                team.setCompetition(competition);
                team.setCountry(country);
                team.setReputation(50.0);
                team.setHumanControlled(false);
                Team saved = teams.save(team);

                for (Position position : new Position[]{Position.GK, Position.DEF, Position.MID,
                        Position.ATT}) {
                    players.save(player(saved, position, tier, club));
                }
            }
        }

        User user = new User();
        user.setUsername("zz_fantasy_" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPassword("not-a-real-password");
        user = users.save(user);

        return new World(country.getId(), user.getId());
    }

    /**
     * A player whose rating is deliberately varied, so a band is a real ranking rather than a tie.
     *
     * <p>The rating is what the nudge ranks on, so a fixture where every player is identical would price a
     * whole band the same and quietly turn every budget assertion in this class into a tautology.
     */
    private static Player player(Team team, Position position, int tier, int club) {
        int skill = 6 + (tier - 1) * 2 + club * 3;
        Skills skills = new Skills();
        for (SkillName name : new SkillName[]{SkillName.PACE, SkillName.STAMINA, SkillName.GOALKEEPER,
                SkillName.DEFENDER, SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING,
                SkillName.STRIKER}) {
            skills.setSkill(name, skill);
        }
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName("ZZ " + tier + " " + club + " " + position + " " + UUID.randomUUID());
        p.setPosition(position);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return p;
    }

    /** The cheapest player in a tier and position band, or the dearest, for the ordering assertions. */
    private static double priceOf(FantasyPool pool, int tier, Position position, boolean dearest) {
        List<Double> prices = new ArrayList<>();
        for (FantasyPoolPlayer p : pool.players()) {
            if (p.tier() == tier && p.fantasyPosition() == FantasyRules.fantasyPositionOf(position)) {
                prices.add(p.price());
            }
        }
        assertFalse(prices.isEmpty(), "no " + position + " in tier " + tier);
        return dearest ? prices.stream().max(Comparator.naturalOrder()).orElseThrow()
                : prices.stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * A legal squad, chosen the way a manager would: for each slot, the cheapest player in that tier and
     * position whose club does not already have two men in this tier.
     *
     * <p>Greedy and cheap on purpose. It is the shape of the game — three from each tier, two from a club,
     * inside a budget — and it is what proves the budget is reachable rather than merely arithmetically
     * bounded.
     */
    private static List<FantasySquadValidator.Selection> legalSelections(FantasyPool pool) {
        List<Slot> slots = List.of(
                new Slot(1, Position.GK, true, false),
                new Slot(1, Position.DEF, false, false),
                new Slot(1, Position.ATT, true, true),
                new Slot(2, Position.DEF, true, false),
                new Slot(2, Position.MID, true, false),
                new Slot(2, Position.ATT, true, false),
                new Slot(3, Position.DEF, true, false),
                new Slot(3, Position.MID, true, false),
                new Slot(3, Position.MID, true, false),
                new Slot(4, Position.GK, false, false),
                new Slot(4, Position.DEF, false, false),
                new Slot(4, Position.MID, true, false),
                new Slot(5, Position.DEF, true, false),
                new Slot(5, Position.MID, false, false),
                new Slot(5, Position.ATT, true, false));

        Map<Integer, Map<Long, Integer>> perTierClubCount = new HashMap<>();
        List<FantasySquadValidator.Selection> out = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            Map<Long, Integer> clubs = perTierClubCount.computeIfAbsent(slot.tier(), k -> new HashMap<>());
            FantasyPoolPlayer chosen = null;
            for (FantasyPoolPlayer candidate : pool.players()) {
                if (candidate.tier() != slot.tier()
                        || candidate.fantasyPosition() != FantasyRules.fantasyPositionOf(slot.position())) {
                    continue;
                }
                if (clubs.getOrDefault(candidate.clubId(), 0) >= FantasyRules.MAX_PER_CLUB) {
                    continue;
                }
                if (chosen == null || candidate.price() < chosen.price()) {
                    chosen = candidate;
                }
            }
            assertNotNull(chosen, "no affordable " + slot.position() + " left in tier " + slot.tier());
            clubs.merge(chosen.clubId(), 1, Integer::sum);
            out.add(new FantasySquadValidator.Selection(chosen.playerId(), slot.starter(), slot.captain()));
        }
        return out;
    }

    private record Slot(int tier, Position position, boolean starter, boolean captain) {
    }

    /**
     * The same squad with the first {@code n} players swapped for different ones.
     *
     * <p>Each replacement comes from the same tier and the same fantasy position, so the shape and the
     * three-per-tier rule are untouched and the only thing that changes is who is in the squad — which is
     * exactly what a transfer is. The club limit and the budget are respected, so the result is a squad the
     * server will accept and the change count is the only variable under test.
     */
    private static List<FantasySquadValidator.Selection> replace(FantasyPool pool,
                                                                 List<FantasySquadValidator.Selection> squad,
                                                                 int n) {
        Map<Long, FantasyPoolPlayer> byId = new HashMap<>();
        pool.players().forEach(p -> byId.put(p.playerId(), p));

        List<FantasySquadValidator.Selection> out = new ArrayList<>(squad);
        Set<Long> used = new LinkedHashSet<>();
        squad.forEach(s -> used.add(s.playerId()));
        double total = squad.stream().mapToDouble(s -> byId.get(s.playerId()).price()).sum();

        for (int i = 0; i < n; i++) {
            FantasySquadValidator.Selection slot = out.get(i);
            FantasyPoolPlayer current = byId.get(slot.playerId());

            Map<Long, Integer> clubsInTier = new HashMap<>();
            for (FantasySquadValidator.Selection other : out) {
                FantasyPoolPlayer p = byId.get(other.playerId());
                if (p.tier() == current.tier() && p.playerId() != current.playerId()) {
                    clubsInTier.merge(p.clubId(), 1, Integer::sum);
                }
            }

            FantasyPoolPlayer replacement = null;
            for (FantasyPoolPlayer candidate : pool.players()) {
                if (candidate.tier() != current.tier()
                        || candidate.fantasyPosition() != current.fantasyPosition()
                        || used.contains(candidate.playerId())
                        || clubsInTier.getOrDefault(candidate.clubId(), 0) >= FantasyRules.MAX_PER_CLUB) {
                    continue;
                }
                if (total - current.price() + candidate.price() > FantasyPricing.BUDGET) {
                    continue;
                }
                replacement = candidate;
                break;
            }
            assertNotNull(replacement, "no legal replacement for slot " + i);
            used.remove(current.playerId());
            used.add(replacement.playerId());
            total += replacement.price() - current.price();
            out.set(i, new FantasySquadValidator.Selection(replacement.playerId(),
                    slot.starter(), slot.captain()));
        }
        return out;
    }
}
