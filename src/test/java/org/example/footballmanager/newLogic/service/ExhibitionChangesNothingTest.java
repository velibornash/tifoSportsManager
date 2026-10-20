package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.engine.InjuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-8 — a match has a type, and an exhibition changes nothing.
 *
 * <p>Owner decisions, 2026-10-03: every match carries a type so results can be filtered by it; an
 * exhibition costs <b>fatigue exactly as any other match</b> but carries reduced injury risk; and it
 * does appear in match history, visibly typed.
 *
 * <p><b>What is guaranteed here, and why each one is a separate assertion:</b>
 *
 * <ul>
 *   <li><b>Every match records a type</b>, including historical rows that predate the column. A type
 *       that has to be inferred per read is a type that can be absent.</li>
 *   <li><b>An exhibition moves no table, no ratings, no career record, no morale and no form.</b>
 *       These were five different writes; asserting only the table would have passed against code that
 *       still inflated a striker's career goals.</li>
 *   <li><b>Fatigue is charged anyway</b>, because the players did play ninety minutes. An exhibition
 *       that cost nothing would make it strictly better than a league match.</li>
 *   <li><b>Injury risk is lower but not zero</b>, and it is restored afterwards — a static that leaks
 *       would silently halve the injury rate of the next competitive match in the world.</li>
 *   <li><b>The match is still there</b>, typed, and readable. "No consequence" must not mean
 *       "no record", or a manager cannot see the game he just played.</li>
 *   <li><b>No money moves and the clock does not tick</b> — the board's own exit criteria, asserted
 *       rather than assumed.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ExhibitionChangesNothingTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired MatchRepository matches;
    @Autowired CompetitionEntryRepository entries;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired ExhibitionMatchService exhibitions;
    @Autowired org.example.footballmanager.newLogic.sim.SimMatchService simMatchService;
    @Autowired LeagueTableReconciliationService reconciliation;
    @Autowired org.example.footballmanager.newLogic.repository.CompetitionRepository competitionRepository;
    @Autowired org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository seasonCompetitionRepository;

    @AfterEach
    void restoreInjuryRisk() {
        InjuryService.resetRiskForNextMatch();
    }

    private Team aClub(String name) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(20_000_000.0);
        t.setReputation(60.0);
        t.setHumanControlled(true);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(25_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private static final org.example.footballmanager.newLogic.model.Position[] LINE = {
            org.example.footballmanager.newLogic.model.Position.GK,
            org.example.footballmanager.newLogic.model.Position.DEF,
            org.example.footballmanager.newLogic.model.Position.DEF,
            org.example.footballmanager.newLogic.model.Position.DEF,
            org.example.footballmanager.newLogic.model.Position.DEF,
            org.example.footballmanager.newLogic.model.Position.MID,
            org.example.footballmanager.newLogic.model.Position.MID,
            org.example.footballmanager.newLogic.model.Position.MID,
            org.example.footballmanager.newLogic.model.Position.MID,
            org.example.footballmanager.newLogic.model.Position.ATT,
            org.example.footballmanager.newLogic.model.Position.ATT};

    /**
     * A club with a real eleven.
     *
     * <p><b>Eleven, not one.</b> A club with fewer than eleven cannot be fielded, so the engine falls
     * back to synthetic squads whose ids are not database ids, and then no DB player is written at
     * all — which reads as "fatigue was not charged" when in fact nothing was ever simulated for a real
     * player. That is the same "green because it did nothing" shape as everywhere else in this log.
     */
    private Player[] aSquad(Team team, String prefix) {
        Player[] out = new Player[LINE.length];
        for (int i = 0; i < LINE.length; i++) {
            out[i] = players.save(aPlayer(team, prefix + " P" + (i + 1), LINE[i], i == 9 ? 7 : 0));
        }
        return out;
    }

    private Player aPlayer(Team team, String name,
                           org.example.footballmanager.newLogic.model.Position position,
                           int careerGoals) {
        org.example.footballmanager.newLogic.model.Skills skills =
                new org.example.footballmanager.newLogic.model.Skills();
        for (org.example.footballmanager.newLogic.model.SkillName s :
                org.example.footballmanager.newLogic.model.SkillName.values()) {
            skills.setSkill(s, 13);
        }
        skills.initializeExactFromVisibleIfNeeded();

        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(24);
        p.setPosition(position);
        p.setSkills(skills);
        p.setPlayerValue(2_000_000);
        p.setEarnings(9_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        p.setTotalGoals(careerGoals);
        p.setTotalAssists(4);
        p.setRating(p.careerRating());
        return p;
    }

    private Match theMatch(Long id) {
        return matches.findById(id).orElseThrow();
    }

    private double careerGoals(Player p) {
        return players.findById(p.getId()).orElseThrow().getTotalGoals();
    }

    private double morale(Player p) {
        return players.findById(p.getId()).orElseThrow().getMorale();
    }

    private double fatigue(Player p) {
        Player fresh = players.findById(p.getId()).orElseThrow();
        return fresh.getSkills() == null ? 0 : fresh.getSkills().getFatigue();
    }

    /** The type is written on the row, not inferred per read. */
    @Test
    @DisplayName("an exhibition is recorded with its type, and it is readable")
    void theMatchIsRecordedAndTyped() {
        Team home = aClub("ExhibHome");
        Team away = aClub("ExhibAway");
        aSquad(home, "Home");
        aSquad(away, "Away");

        Long matchId = exhibitions.playExhibition(home.getId(), away.getId(), 1, 5);

        assertNotNull(matchId, "the exhibition must be saved, or a manager cannot see what he played");
        Match played = theMatch(matchId);
        assertEquals(MatchType.EXHIBITION, played.getMatchType(),
                "the type is a column so results can be filtered by it — not inferred per read");
        assertEquals(MatchType.EXHIBITION, played.resolvedMatchType());
        assertTrue(played.isPlayed());
        assertNotNull(played.getHomeGoals());
    }

    /** The board's own exit criteria, each asserted. */
    @Test
    @DisplayName("an exhibition moves no table, no ratings and no clock")
    void anExhibitionMovesNothing() {
        Team home = aClub("NoEffectHome");
        Team away = aClub("NoEffectAway");
        aSquad(home, "Home");
        aSquad(away, "Away");

        long entriesBefore = entries.count();
        double eloBefore = teams.findById(home.getId()).orElseThrow().getEloRating() == null
                ? 0 : teams.findById(home.getId()).orElseThrow().getEloRating();
        long ledgerBefore = ledger.count();

        exhibitions.playExhibition(home.getId(), away.getId(), 1, 5);

        assertEquals(entriesBefore, entries.count(),
                "no league table may move: the match is in no competition and its type excludes it");
        assertEquals(eloBefore, teams.findById(home.getId()).orElseThrow().getEloRating() == null
                        ? 0 : teams.findById(home.getId()).orElseThrow().getEloRating(), 0.001,
                "club Elo must not move");
        assertEquals(ledgerBefore, ledger.count(),
                "no money may move: an exhibition is not gate receipts");
    }

    /** The five writes that are not the table, asserted separately. */
    @Test
    @DisplayName("an exhibition touches no career record, no morale and no form")
    void anExhibitionTouchesNoPlayerRecord() {
        Team home = aClub("PlayerEffectHome");
        Team away = aClub("PlayerEffectAway");
        Player[] homeSquad = aSquad(home, "Home");
        aSquad(away, "Away");
        Player scorer = homeSquad[9];
        double goalsBefore = careerGoals(scorer);
        double moraleBefore = morale(scorer);
        double formBefore = players.findById(scorer.getId()).orElseThrow().getForm();

        exhibitions.playExhibition(home.getId(), away.getId(), 1, 5);

        assertEquals(goalsBefore, careerGoals(scorer), 0.001,
                "a practice match must not pad a striker's career record");
        assertEquals(moraleBefore, morale(scorer), 0.001,
                "nor move his morale — that is a consequence the owner did not grant");
        assertEquals(formBefore, players.findById(scorer.getId()).orElseThrow().getForm(), 0.001,
                "nor his form");
    }

    /** The owner's words: fatigue identical. */
    @Test
    @DisplayName("an exhibition still costs fatigue, because the players did play")
    void anExhibitionStillCostsFatigue() {
        Team home = aClub("TiredHome");
        Team away = aClub("TiredAway");
        Player[] homeSquad = aSquad(home, "Home");
        aSquad(away, "Away");
        Player worker = homeSquad[5];
        double before = fatigue(worker);

        exhibitions.playExhibition(home.getId(), away.getId(), 1, 5);

        assertTrue(fatigue(worker) > before,
                "ninety minutes is ninety minutes. An exhibition that cost nothing would be strictly "
                        + "better than a league match, and then nobody would play one. Before " + before
                        + ", after " + fatigue(worker));
    }

    /** Lower risk, but not zero — and never left set for the next competitive match. */
    @Test
    @DisplayName("injury risk is lower for an exhibition and restored afterwards")
    void injuryRiskIsLowerAndThenRestored() {
        assertEquals(0.35, MatchType.EXHIBITION.injuryRisk(), 0.001,
                "the owner asked for reduced risk, not none");
        assertTrue(MatchType.EXHIBITION.injuryRisk() < MatchType.FRIENDLY.injuryRisk());
        assertTrue(MatchType.FRIENDLY.injuryRisk() < MatchType.LEAGUE.injuryRisk());
        assertEquals(1.0, MatchType.LEAGUE.injuryRisk(), 0.001,
                "a competitive match is the baseline and must be untouched");
        assertEquals(1.0, MatchType.CUP.injuryRisk(), 0.001);

        // The multiplier is static, so a leak would quietly halve the injury rate of every match in
        // the world after one practice game.
        assertEquals(1.0, InjuryService.riskMultiplier(),
                "no exhibition has been played, so nothing may be scaled");
        Team home = aClub("RiskHome");
        Team away = aClub("RiskAway");
        aSquad(home, "Home");
        aSquad(away, "Away");
        exhibitions.playExhibition(home.getId(), away.getId(), 1, 5);
        assertEquals(1.0, InjuryService.riskMultiplier(), 0.001,
                "the multiplier must be reset after the match, or every later competitive match in the "
                        + "world is played at practice-match risk");
    }

    /** The type is one place, and it is filterable. */
    @Test
    @DisplayName("every type states what it counts for, and the exhibition counts for nothing")
    void theRulesLiveInTheType() {
        // A cup match is no longer a yes/no — it is a group match or not (P0-CUPS-1). Building the
        // match here is the point: the old no-argument form answered false for every cup, which is
        // right for a domestic cup and wrong for a Champions Cup group, and nothing in the signature
        // said so.
        Match league = new Match();
        assertTrue(MatchType.LEAGUE.countsForTable(league));
        assertFalse(MatchType.FRIENDLY.countsForTable(league));
        assertFalse(MatchType.EXHIBITION.countsForTable(league));

        Match groupMatch = new Match();
        groupMatch.setGroupCode("A");
        assertTrue(MatchType.CUP.countsForTable(groupMatch), "a cup group match decides its group's table");

        Match knockout = new Match();
        knockout.setRoundNumber(7);
        assertFalse(MatchType.CUP.countsForTable(knockout),
                "a cup tie with no group is a knockout tie and decides no table");
        assertFalse(MatchType.CUP.countsForTable(new Match()),
                "a cup match that was never put in a group decides no table");

        assertTrue(MatchType.LEAGUE.countsForRatings());
        assertTrue(MatchType.CUP.countsForRatings());
        assertFalse(MatchType.EXHIBITION.countsForRatings());

        assertTrue(MatchType.LEAGUE.countsForCareer());
        assertTrue(MatchType.INTERNATIONAL.countsForCareer());
        assertFalse(MatchType.EXHIBITION.countsForCareer());
        assertFalse(MatchType.FRIENDLY.countsForCareer());

        assertEquals("Exhibition", MatchType.EXHIBITION.label(),
                "a manager is going to read this on a match card");
    }

    /** A type in no competition is a friendly; an exhibition is only ever asked for. */
    @Test
    @DisplayName("a fixture's type comes from its competition unless it says otherwise")
    void theTypeFollowsTheFixture() {
        var fixture = new org.example.footballmanager.newLogic.model.MatchFixture();
        assertEquals(MatchType.FRIENDLY, fixture.resolvedMatchType(),
                "a fixture in no competition is a friendly — which is what makes the existing "
                        + "friendly fixtures a label rather than a blank");

        var league = new org.example.footballmanager.newLogic.model.Competition();
        league.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        fixture.setCompetition(league);
        assertEquals(MatchType.LEAGUE, fixture.resolvedMatchType());

        var cup = new org.example.footballmanager.newLogic.model.Competition();
        cup.setType(org.example.footballmanager.newLogic.model.CompetitionType.CUP);
        fixture.setCompetition(cup);
        assertEquals(MatchType.CUP, fixture.resolvedMatchType());

        fixture.setMatchType(MatchType.EXHIBITION);
        assertEquals(MatchType.EXHIBITION, fixture.resolvedMatchType(),
                "an explicit type wins over the competition");
    }

    /**
     * The repair pass must not count a practice match back in.
     *
     * <p>This is the assertion that made the others meaningful. An exhibition has no competition, so
     * updateLeagueTable's own "no competition, no table" guard already skips it — which means an
     * exhibition in a table could be caught by that guard alone, and my first version of this test
     * <b>passed with the type gate removed</b>. The dangerous case is a practice match recorded
     * <em>against</em> the competition, which the write path will not stop and only this filter can.
     *
     * <p>So the fixture is built with the league attached and the match typed as an exhibition, and
     * the table is then rebuilt from scratch by the reconciliation service. If the filter is gone, the
     * practice match is counted and this fails.
     */
    @Test
    @DisplayName("the table repair pass does not count an exhibition back in")
    void theRepairPassIgnoresAnExhibition() {
        Competition league = new Competition();
        league.setName("Reconcile League " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league = competitionRepository.save(league);

        Team home = aClub("ReconciledHome");
        Team away = aClub("ReconciledAway");
        aSquad(home, "Home");
        aSquad(away, "Away");
        home.setCompetition(league);
        away.setCompetition(league);
        teams.save(home);
        teams.save(away);

        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(league);
        sc.setSeasonYear(1);
        sc = seasonCompetitionRepository.save(sc);
        entries.save(entryFor(sc, home));
        entries.save(entryFor(sc, away));

        // Recorded against the competition on purpose: this is the case the write path cannot catch.
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setCompetition(league);
        fixture.setMatchType(MatchType.EXHIBITION);
        fixture.setSeasonYear(1);
        fixture.setMatchDate(java.time.LocalDateTime.now());
        fixture.setPlayed(false);

        var outcome = simMatchService.simulate(fixture, false);
        assertNotNull(outcome, "precondition: the match ran");
        Long matchId = simMatchService.persist(fixture, outcome.outcome(), -1L, outcome.snapshots());
        assertNotNull(matchId);
        assertEquals(MatchType.EXHIBITION, theMatch(matchId).resolvedMatchType(),
                "precondition: it really is recorded as an exhibition, against this competition");

        double pointsBefore = entryPoints(sc, home);

        reconciliation.reconcile(league, 1);

        assertEquals(pointsBefore, entryPoints(sc, home), 0.001,
                "the repair pass rebuilds the table from match rows and would count a practice match "
                        + "the write path refused to add. Before " + pointsBefore + ", after "
                        + entryPoints(sc, home));
    }

    private CompetitionEntry entryFor(SeasonCompetition sc, Team team) {
        CompetitionEntry e = new CompetitionEntry();
        e.setSeasonCompetition(sc);
        e.setTeam(team);
        e.setPoints(0);
        e.setGoalsScored(0);
        e.setGoalsConceded(0);
        return entries.save(e);
    }

    private double entryPoints(SeasonCompetition sc, Team team) {
        return entries.findBySeasonCompetition(sc).stream()
                .filter(e -> team.getId().equals(e.getTeam().getId()))
                .mapToDouble(e -> e.getPoints() == null ? 0 : e.getPoints())
                .sum();
    }

    /** A club cannot play itself. */
    @Test
    @DisplayName("a club cannot play an exhibition against itself")
    void aClubCannotPlayItself() {
        Team club = aClub("SoloClub");
        aSquad(club, "Solo");

        var refusal = org.junit.jupiter.api.Assertions.assertThrows(
                org.example.footballmanager.newLogic.exception.ApiException.class,
                () -> exhibitions.playExhibition(club.getId(), club.getId(), 1, 5));
        assertEquals("SAME_TEAM", refusal.getCode());
    }
}