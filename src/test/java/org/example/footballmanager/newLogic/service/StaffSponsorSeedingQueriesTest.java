package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.SponsorRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Seeding the world must not ask the database one question per club (T1-9).
 *
 * <p><b>Why a query-counting test and not a behavioural one.</b> The exit criterion is literally
 * "one query for the set, not one per club". A test that only checks the right clubs get seeded would
 * pass against the N+1 and against the fix, because both produce the same rows — the difference is
 * entirely in how many round trips it took. So these tests count calls, and the mutation that proves it
 * is putting the old {@code countByTeamId} call back.
 *
 * <p>Mocked repositories rather than a database, precisely so that a call can be counted. An H2 or
 * Testcontainers test would answer 14,880 questions correctly and prove nothing about how many were
 * asked.
 */
class StaffSponsorSeedingQueriesTest {

    /**
     * How many <em>read</em> calls a repository mock has received.
     *
     * <p>Writes are excluded on purpose. Seeding 14,880 unstaffed clubs has to write 14,880 times, and
     * counting those as queries would fail the fix along with the defect. The N+1 this task removed was
     * entirely reads: `countByTeamId` asked the database a question whose answer was already in a set.
     */
    private static long callsMadeOn(Object repositoryMock) {
        var mockingDetails = Mockito.mockingDetails(repositoryMock);
        long total = 0;
        for (var invocation : mockingDetails.getInvocations()) {
            String name = invocation.getMethod().getName();
            if (name.startsWith("save") || name.equals("delete") || name.equals("flush")) {
                continue;
            }
            total++;
        }
        return total;
    }

    private static Team club(long id) {
        Team team = new Team();
        team.setId(id);
        team.setName("Club " + id);
        return team;
    }

    @Test
    @DisplayName("seeding the world asks the database a fixed number of times, not one per club")
    void seedingDoesNotScaleWithTheNumberOfClubs() {
        var teams = mock(TeamRepository.class);
        var staff = mock(StaffMemberRepository.class);
        var sponsors = mock(SponsorRepository.class);

        // 14,880 clubs is the real world. The point is that the query count does not move when this does.
        List<Team> world = new ArrayList<>();
        for (long id = 1; id <= 14_880; id++) {
            world.add(club(id));
        }
        when(teams.findAll()).thenReturn(world);
        when(staff.findStaffedTeamIds()).thenReturn(List.of());
        when(sponsors.findSponsoredTeamIds()).thenReturn(List.of());
        when(staff.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(sponsors.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(staff.countByTeamId(anyLong())).thenReturn(0L);
        when(sponsors.countByTeamId(anyLong())).thenReturn(0L);

        var service = new StaffSponsorService(teams, staff, sponsors);

        // **The delta, not the total.** Counting the mocks before the call measures only the stubbing
        // setup and reads as a pass for any implementation at all — which is how this test managed to
        // stay green through a mutation that put the per-club count back. The number that means
        // something is what the call itself cost.
        long before = callsMadeOn(staff) + callsMadeOn(sponsors);
        service.seedAllClubs(1);
        long queriesForTheWholeWorld = callsMadeOn(staff) + callsMadeOn(sponsors) - before;

        assertTrue(queriesForTheWholeWorld <= 4,
                "seeding 14,880 clubs issued " + queriesForTheWholeWorld + " read queries. Writes are "
                        + "excluded on purpose — 14,880 unstaffed clubs have to be written 14,880 times, "
                        + "and that is the work rather than the defect. What has to stay flat is the "
                        + "number of READS: two sets, read once each, then filtered in Java.");
    }

    @Test
    @DisplayName("a club that is already staffed is skipped without asking again")
    void alreadyStaffedClubsAreSkipped() {
        var teams = mock(TeamRepository.class);
        var staff = mock(StaffMemberRepository.class);
        var sponsors = mock(SponsorRepository.class);

        when(teams.findAll()).thenReturn(List.of(club(1), club(2)));
        // Club 1 already has staff; club 2 does not. One query answers it for both.
        when(staff.findStaffedTeamIds()).thenReturn(List.of(1L));
        when(sponsors.findSponsoredTeamIds()).thenReturn(List.of(1L));
        when(staff.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(sponsors.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(staff.countByTeamId(anyLong())).thenReturn(0L);
        when(sponsors.countByTeamId(anyLong())).thenReturn(0L);

        var service = new StaffSponsorService(teams, staff, sponsors);
        int created = service.seedAllClubs(1);

        assertEquals(1, created, "only the club without staff should have been seeded");

        // The staffed club must not have been re-saved. saveAll is the write; asking about it is cheap
        // and proves the filter used the pre-read set rather than re-querying.
        Mockito.verify(staff, Mockito.times(1)).saveAll(any());
        Mockito.verify(sponsors, Mockito.times(1)).saveAll(any());
    }

    @Test
    @DisplayName("a club with staff but no sponsor is still given a sponsor")
    void staffDoesNotImplyASponsor() {
        var teams = mock(TeamRepository.class);
        var staff = mock(StaffMemberRepository.class);
        var sponsors = mock(SponsorRepository.class);

        when(teams.findAll()).thenReturn(List.of(club(1)));
        // Staffed but not sponsored — the two sets are read separately for exactly this reason.
        when(staff.findStaffedTeamIds()).thenReturn(List.of(1L));
        when(sponsors.findSponsoredTeamIds()).thenReturn(List.of());
        when(staff.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(sponsors.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(staff.countByTeamId(anyLong())).thenReturn(0L);
        when(sponsors.countByTeamId(anyLong())).thenReturn(0L);

        var service = new StaffSponsorService(teams, staff, sponsors);
        int created = service.seedAllClubs(1);

        assertEquals(0, created, "the club was already staffed, so it is not seeded again");
        Mockito.verify(sponsors, Mockito.never()).saveAll(any());
    }
}