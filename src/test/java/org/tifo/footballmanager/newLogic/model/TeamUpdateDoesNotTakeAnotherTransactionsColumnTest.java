package org.tifo.footballmanager.newLogic.model;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A club's update must not take a column it did not change.
 *
 * <p><b>The defect this holds.</b> Owner, 2026-10-21: <i>"posle database inita nije napravljena
 * srpska piramida.. imam meceve na dashboardu ali nema lige"</i>. The pyramid was built — 32 leagues
 * and 406 clubs, and the owner's club was in the Superliga's fixtures — and the League page said
 * <b>"No league yet"</b>.
 *
 * <p><b>Why.</b> The Initialize DB job binds <b>one</b> {@code EntityManager} for the whole job, and
 * the pyramid is built by thirty-one divisions running in parallel, each in its own transaction. The
 * job's own thread read the owner's club <b>before</b> those transactions committed and wrote it
 * <b>after</b> — so its copy was stale. And Hibernate, without {@code @DynamicUpdate}, writes the
 * <em>whole row</em> when it writes any of it: the stale {@code competition_id} went back over the
 * division's.
 *
 * <p>Measured, with the fix reverted: the Superliga's division set the club's competition to the
 * league and committed it, and eleven seconds later the job's own thread wrote the row back with a
 * null competition. The club was in the league's fixtures and in no league at all.
 *
 * <p>The same loss took the club's budget and reputation — the division's economy profile — with it.
 */
class TeamUpdateDoesNotTakeAnotherTransactionsColumnTest extends BaseTest {

    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("a save that changes one column leaves another transaction's column alone")
    void aStaleSessionDoesNotTakeAColumnItDidNotChange() {
        Competition league = new Competition();
        league.setName("Stale Session League " + System.nanoTime());
        league.setTier(1);
        league = competitions.save(league);
        Long leagueId = league.getId();

        Team club = new Team();
        club.setName("Stale Session Club " + System.nanoTime());
        club = teams.save(club);
        Long clubId = club.getId();

        // Another transaction writes the club's competition - the pyramid's division does exactly
        // this, and commits it.
        jdbc.update("update team set competition_id = ? where id = ?", leagueId, clubId);

        // This session still holds the old copy, in which the club is in no league.
        assertNull(club.getCompetition(),
                "precondition: the copy in this session is the old one");

        // And now this session saves the club - for its logo, say.
        club.setLogoUrl("/images/omladinac.png");
        teams.save(club);
        entityManager.flush();

        Integer competition = jdbc.queryForObject(
                "select competition_id from team where id = ?", Integer.class, clubId);

        assertNotNull(competition,
                "the save took the league off the club. The session was holding a copy from before "
                        + "another transaction wrote it, and the write took the whole row with it. "
                        + "That is how the owner's club ended up in the league's fixtures and in no "
                        + "league at all.");
        assertEquals(leagueId.intValue(), competition.intValue());
    }
}
