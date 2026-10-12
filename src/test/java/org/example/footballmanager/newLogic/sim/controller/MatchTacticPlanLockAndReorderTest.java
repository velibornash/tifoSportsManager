package org.example.footballmanager.newLogic.sim.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.TacticLibraryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Choosing three tactics for a match (owner, 2026-10-11).
 *
 * <p>The owner: *"imaš 10 taktika i 3 biram za meč, bilo koja koju promeniš"* — ten tactics, three of
 * them for the match, and any tactic may be moved into any slot.
 *
 * <p>The model was already there and so was the engine's reading of it: {@code MatchTacticAssignment}
 * carries a priority of 1 to 3, and {@code MatchTacticPlan.inForce} takes the lowest priority whose
 * condition holds, so a plain assignment ({@code ALWAYS} from minute 0) means the match starts on slot 1.
 *
 * <p>Two things were missing, and the first is the same defect the squad page and the orders page both
 * had: the plan controller refused a <em>played</em> fixture, and **a prepared fixture is not played**. So
 * for the hour the owner closed, the tactic plan could still be rewritten.
 *
 * <p>The second is that "any into any slot" was three separate one-slot writes. Reordering 1 and 3 by
 * hand means an intermediate state in which two slots hold the same tactic, and a failure halfway leaves
 * a mangled plan. Reorder takes the whole order at once.
 */
@Import(org.example.footballmanager.newLogic.controller.ControllerAuthFixture.class)
class MatchTacticPlanLockAndReorderTest extends BaseTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired private org.example.footballmanager.newLogic.controller.ControllerAuthFixture auth;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchRepository matches;
    @Autowired private TacticLibraryService library;

    private Team club;
    private MatchFixture fixture;
    private List<Long> tacticIds;

    @BeforeEach
    void aMatchWithThreeTacticsToChooseFrom() {
        Competition competition = new Competition();
        competition.setName("ZZ Plan " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        club = aTeam("ZZ Plan Home " + UUID.randomUUID());

        fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(club);
        fixture.setAwayTeam(aTeam("ZZ Plan Away " + UUID.randomUUID()));
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        fixture = fixtures.save(fixture);

        tacticIds = new ArrayList<>();
        for (String name : new String[]{"First", "Second", "Third"}) {
            Tactic tactic = library.save(club.getId(), name, "4-4-2", "Balanced",
                    "[{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                            + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_2_1\"}]",
                    null, "First".equals(name));
            tacticIds.add(tactic.getId());
        }
    }

    @Test
    @DisplayName("three tactics can be placed, and the order is the order they were given")
    void threeCanBePlacedInOrder() throws Exception {
        place(1, tacticIds.get(0));
        place(2, tacticIds.get(1));
        place(3, tacticIds.get(2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/sim/fixtures/{id}/tactic-plan", fixture.getId())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(3))
                .andExpect(jsonPath("$.assignments[0].priority").value(1))
                .andExpect(jsonPath("$.assignments[0].tacticName").value("First"))
                .andExpect(jsonPath("$.assignments[2].priority").value(3))
                .andExpect(jsonPath("$.assignments[2].tacticName").value("Third"));
    }

    @Test
    @DisplayName("reorder moves any tactic into any slot and keeps the rest in order")
    void reorderMovesAnyTacticIntoAnySlot() throws Exception {
        place(1, tacticIds.get(0));
        place(2, tacticIds.get(1));
        place(3, tacticIds.get(2));

        // Sokker's behaviour: put the third tactic into slot 1 and the first into slot 3. The one in the
        // middle stays in the middle - it is not shifted along.
        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"order\":[" + tacticIds.get(2) + ","
                                + tacticIds.get(1) + "," + tacticIds.get(0) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].tacticName").value("Third"))
                .andExpect(jsonPath("$.assignments[1].tacticName").value("Second"))
                .andExpect(jsonPath("$.assignments[2].tacticName").value("First"));
    }

    @Test
    @DisplayName("reorder refuses a duplicate, a stranger's tactic, and a fourth entry")
    void reorderRefusesWhatItCannotDo() throws Exception {
        place(1, tacticIds.get(0));
        place(2, tacticIds.get(1));
        place(3, tacticIds.get(2));

        String order = "\"order\":[";

        // The same tactic twice - two slots holding one tactic is not "three of the ten".
        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{" + order + tacticIds.get(0) + "," + tacticIds.get(0) + "]}"))
                .andExpect(status().isBadRequest());

        // A fourth entry: the owner said three.
        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{" + order + tacticIds.get(0) + "," + tacticIds.get(1) + ","
                                + tacticIds.get(2) + ",9999]}"))
                .andExpect(status().isBadRequest());

        // Somebody else's tactic: club 1's tactic 1 and club 2's tactic 1 are both 1.
        Team other = aTeam("ZZ Plan Other " + UUID.randomUUID());
        Long theirs = library.save(other.getId(), "Theirs", "4-4-2", "Balanced", null, null, true).getId();
        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{" + order + theirs + "," + tacticIds.get(1) + "," + tacticIds.get(0) + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a refused reorder leaves the manager's three tactics exactly where they were")
    void aRefusedReorderChangesNothing() throws Exception {
        place(1, tacticIds.get(0));
        place(2, tacticIds.get(1));
        place(3, tacticIds.get(2));

        // A fourth entry: the owner said three.
        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"order\":[" + tacticIds.get(0) + "," + tacticIds.get(1) + ","
                                + tacticIds.get(2) + ",9999]}"))
                .andExpect(status().isBadRequest());

        // Reorder clears the side and rewrites it, so the order of those two steps is the whole question:
        // clear first and a refusal leaves the manager with nothing.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/sim/fixtures/{id}/tactic-plan", fixture.getId())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(3))
                .andExpect(jsonPath("$.assignments[0].tacticName").value("First"))
                .andExpect(jsonPath("$.assignments[2].tacticName").value("Third"));
    }

    @Test
    @DisplayName("reordering down to two drops the third rather than leaving it behind")
    void reorderingDownDropsTheLeftover() throws Exception {
        place(1, tacticIds.get(0));
        place(2, tacticIds.get(1));
        place(3, tacticIds.get(2));

        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"order\":[" + tacticIds.get(1) + "," + tacticIds.get(0) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(2))
                .andExpect(jsonPath("$.assignments[0].tacticName").value("Second"))
                .andExpect(jsonPath("$.assignments[1].tacticName").value("First"));
    }

    @Test
    @DisplayName("a computed match refuses its plan, as the squad page and the orders page already do")
    void aPreparedFixtureRefusesThePlan() throws Exception {
        prepare();

        mockMvc.perform(post("/api/sim/fixtures/{id}/tactic-plan/reorder", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"order\":[" + tacticIds.get(0) + "," + tacticIds.get(1) + ","
                                + tacticIds.get(2) + "]}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("PLAN_CLOSED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already been decided")));
    }

    private void place(int priority, Long tacticId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/sim/fixtures/{id}/tactic-plan", fixture.getId())
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"tacticId\":" + tacticId + ",\"priority\":" + priority + "}"))
                .andExpect(status().isOk());
    }

    private String bearer() {
        return auth.bearerManaging(UserRole.OWNER, club);
    }

    private void prepare() {
        Match match = new Match();
        match.setHomeTeam(fixture.getHomeTeam());
        match.setAwayTeam(fixture.getAwayTeam());
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setPlayed(true);
        match = matches.save(match);

        fixture.setPreparedMatchId(match.getId());
        fixture.setPreparedAt(java.time.LocalDateTime.now());
        fixture.setPlayed(false);
        fixtures.save(fixture);
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}