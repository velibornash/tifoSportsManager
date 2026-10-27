package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.UniqueIsoCodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * A club with no players gets a squad when a match needs one, and not otherwise.
 *
 * <p>Both halves matter. Generating on seed is the 370,000-row problem the static world was built to
 * avoid; generating on every read would put a write in a country page, which is worse.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LazySquadGeneratorTest {

    @Autowired private LazySquadGenerator generator;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private CompetitionRepository competitions;
    @Autowired private org.tifo.footballmanager.newLogic.util.players.PlayerFactory playerFactory;
    @Autowired private CountryRepository countries;

    @Test
    @DisplayName("a bot club playing a human club gets a squad")
    void aHumanInvolvementGeneratesASquad() {
        Team bot = botClub("ZZL Bot", 1);
        Team human = humanClub("ZZL Human");
        assertTrue(players.findByTeamId(bot.getId()).isEmpty(), "the club was supposed to start empty");

        int made = generator.ensureSquadsForMatch(bot, human);

        assertTrue(made > 0, "a match with a human side produced no lineup at all");
        assertFalse(players.findByTeamId(bot.getId()).isEmpty(),
                "the squad was created and then not persisted, so the next match starts empty again");
    }

    @Test
    @DisplayName("a bot club playing another bot club gets nothing, because there is no lineup to show")
    void twoBotsGenerateNothing() {
        Team home = botClub("ZZL Home", 1);
        Team away = botClub("ZZL Away", 1);

        assertFalse(LazySquadGenerator.isHumanInvolved(home, away),
                "two bot clubs were treated as a match anyone will see");
        generator.ensureSquadsForMatch(home, away);

        assertTrue(players.findByTeamId(home.getId()).isEmpty(),
                "generating for two bots is how one fixture list becomes fourteen thousand squads");
        assertTrue(players.findByTeamId(away.getId()).isEmpty());
    }

    @Test
    @DisplayName("generating twice gives one squad, not two")
    void generatingTwiceIsIdempotent() {
        Team bot = botClub("ZZL Twice", 3);
        Team human = humanClub("ZZL Human Twice");

        generator.ensureSquadsForMatch(bot, human);
        int afterFirst = players.findByTeamId(bot.getId()).size();
        int second = generator.ensureSquadsForMatch(bot, human);

        assertEquals(0, second, "a club that already had a squad was given another one");
        assertEquals(afterFirst, players.findByTeamId(bot.getId()).size());
    }

    @Test
    @DisplayName("a generated squad is the club's own tier, not a default one")
    void theSquadMatchesTheTier() {
        Team topFlight = botClub("ZZL Elite", 1);
        Team bottomFlight = botClub("ZZL Municipal", 5);
        Team human = humanClub("ZZL Human Tiers");

        // Two separate human matches, not one bot-vs-bot: a bot-vs-bot match correctly generates
        // nothing at all, which is the rule the previous test pins.
        generator.ensureSquadsForMatch(topFlight, human);
        generator.ensureSquadsForMatch(bottomFlight, human);

        double elite = averageSkill(topFlight);
        double municipal = averageSkill(bottomFlight);

        assertTrue(elite > municipal,
                "a fifth-tier club turned up with the same players as a first-tier one: elite "
                        + elite + " vs municipal " + municipal);
        // 12 and 8 on a 1-20 scale, with spread. Loose bounds: this asserts the tier was read at all,
        // not the exact draw, which is random by design.
        assertTrue(elite > 9 && elite < 15, "tier 1 should sit near 12, was " + elite);
        assertTrue(municipal > 6 && municipal < 11, "tier 5 should sit near 8, was " + municipal);
    }

    /**
     * The training match's opponent is not a row in the {@code team} table, and it must not be given one.
     *
     * <p><b>Owner, 2026-10-27: the training match writes nothing to the database.</b> Its opponent is
     * built entirely in memory, so its {@code Team} has no id — and this is the one place on the match
     * path where a team without an id arrives at the generator. Without the null guard, {@code needsSquad}
     * reads "a bot club with no players" and {@code generate} hands a detached, id-less team to
     * {@code createRandomTeamPlayers} — which either cascades a phantom club into the {@code team} table
     * or inserts players against a null foreign key. Either way the feature's single promise is broken
     * here rather than by anything the match itself does, which is the reason the failure is invisible
     * unless something counts rows.
     */
    @Test
    @DisplayName("a team with no id cannot be given a squad, because there is no row to attach it to")
    void aTeamWithNoIdCannotBeGivenASquad() {
        Team human = humanClub("ZZL Human NoId");

        Team opponent = new Team();
        opponent.setName("Training XI (14)");
        opponent.setHumanControlled(false);
        assertTrue(opponent.getId() == null, "the fixture must start with no id, or nothing is tested");

        int made = generator.ensureSquadsForMatch(human, opponent);

        assertEquals(0, made,
                "the in-memory opponent was given a squad: that is a database write on the path the "
                        + "owner promised would write nothing");
        assertTrue(players.findByTeamId(human.getId()).isEmpty(),
                "the human side has no squad either — this path must generate nothing at all");
    }

    @Test
    @DisplayName("a club that already has players is left alone")
    void anExistingSquadIsUntouched() {
        Team human = humanClub("ZZL Human Existing");
        Team bot = botClub("ZZL WithSquad", 1);
        // A real squad, built by the real factory — the point is that the club is not empty, not that
        // it is empty in a particular way.
        playerFactory.createRandomTeamPlayers(bot.getName(), bot);

        int before = players.findByTeamId(bot.getId()).size();
        generator.ensureSquadsForMatch(bot, human);

        assertEquals(before, players.findByTeamId(bot.getId()).size(),
                "a club with one player in it was topped up to a full squad — this tops up an empty "
                        + "club, it is not a refresh");
    }

    /** The mean of the eight football skills, ignoring condition. */
    private double averageSkill(Team team) {
        List<Player> squad = players.findByTeamId(team.getId());
        assertFalse(squad.isEmpty(), "no squad was generated for " + team.getName());
        return squad.stream()
                .mapToDouble(p -> averageOfFootballSkills(p))
                .average()
                .orElseThrow();
    }

    /** Mean of the football skills, excluding condition — a tired player is not a weak one. */
    private double averageOfFootballSkills(Player player) {
        org.tifo.footballmanager.newLogic.model.Skills skills = player.skills();
        double sum = 0;
        int count = 0;
        for (org.tifo.footballmanager.newLogic.model.SkillName skill
                : org.tifo.footballmanager.newLogic.model.SkillName.values()) {
            if (skill == org.tifo.footballmanager.newLogic.model.SkillName.FATIGUE) {
                continue;
            }
            Integer value = skills.getSkills().get(skill);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private static int isoCounter = 0;

    private Team botClub(String name, int tier) {
        Country country = countries.save(country(name));
        Competition league = competitions.save(league(country, tier));
        Team team = new Team();
        team.setName(name + " FC");
        team.setCountry(country);
        team.setCompetition(league);
        team.setHumanControlled(false);
        return teams.save(team);
    }

    private Team humanClub(String name) {
        Country country = countries.save(country(name));
        Competition league = competitions.save(league(country, 1));
        Team team = new Team();
        team.setName(name + " FC");
        team.setCountry(country);
        team.setCompetition(league);
        team.setHumanControlled(true);
        return teams.save(team);
    }

    private Competition league(Country country, int tier) {
        Competition league = new Competition();
        league.setName(country.getIsoCode() + " T" + tier + " League");
        league.setType(CompetitionType.LEAGUE);
        league.setCountry(country);
        league.setTier(tier);
        league.setTeamType(org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB);
        return league;
    }

    /** A unique three-letter ISO per country, so two clubs never collide on the unique index. */
    private Country country(String label) {
        int n = ++isoCounter;
        Country country = new Country();
        country.setName("ZZ " + label + " " + n);
        country.setIsoCode(UniqueIsoCodes.next());
        country.setState(CountryState.SIMULATED);
        return country;
    }
}