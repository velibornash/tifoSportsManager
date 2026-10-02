package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1: weekly fatigue recovery must read the tired players, not every player in the world.
 *
 * <p>{@code recoverFatigueForWeek} loaded {@code playerRepository.findAll()} and skipped anybody at
 * zero fatigue inside the loop — so once a week it materialised the whole player table, 370,000 rows at
 * the scale this project targets, to recover the tired few. It then called
 * {@code saveAll} on that <em>entire</em> list, changed or not, and with {@code IDENTITY} generation
 * across 70 of 71 entities there is no JDBC batching to make a loop of writes cheap.
 *
 * <p>This is the sibling of a fix the board already records: {@code findByLastPlayedAtIsNotNull} did
 * exactly this for the daily recovery job, with the same reasoning, and weekly recovery was missed.
 *
 * <h2>Why a static guard and not a query count</h2>
 *
 * <p><b>Two instruments were measured on this task and neither could see it</b>, and both are traps
 * this repository has already hit: Hibernate's entity-load counter reads zero inside a
 * {@code @Transactional} test because the rows are already in the persistence context, and a SQL
 * statement count is identical for a whole-table load and a narrow lookup because each issues one
 * query. A source scan asks the question directly and cannot be disarmed by a statistics switch.
 *
 * <p>It cannot be fooled by a mention either: comments are stripped before the scan, because the audit
 * that produced the original finding of this shape was retracted for counting a Javadoc.
 *
 * <p>The second test is the counterweight — a scan that passes because the method was deleted outright
 * must not be satisfied, so there is also a test that the query returns the tired players and only
 * those. A narrow query that returned nothing would satisfy the first test perfectly and quietly end
 * weekly recovery for the whole world.
 */
class WeeklyFatigueRecoveryScopeTest extends BaseTest {

    private static final Path SEASON_SERVICE =
            Path.of("src/main/java/org/example/footballmanager/newLogic/service/SeasonService.java");

    @Autowired private SeasonService seasons;
    @Autowired private PlayerRepository players;
    @Autowired private TeamRepository teams;

    @Test
    @DisplayName("weekly recovery no longer reads the whole player table")
    void weeklyRecoveryDoesNotReadTheWholePlayerTable() throws IOException {
        String code = stripComments(Files.readString(SEASON_SERVICE, StandardCharsets.UTF_8));
        String body = methodBody(code, "recoverFatigueForWeek");

        assertTrue(body != null, "recoverFatigueForWeek is not in SeasonService any more. If it was "
                + "renamed or moved this test is measuring nothing — check where weekly recovery lives "
                + "before removing this guard.");

        Matcher wholeTable = Pattern.compile("playerRepository\\s*\\.\\s*findAll\\s*\\(").matcher(body);
        assertTrue(!wholeTable.find(),
                "recoverFatigueForWeek calls playerRepository.findAll() again. Every week that loads every "
                        + "player in the world — 370,000 rows once the simulated countries are seeded — and "
                        + "then saves the whole list back, changed or not. The tired players are asked for by "
                        + "query: findBySkillsFatigueGreaterThan(0).");

        // Nothing here forbids the saveAll. It is deliberately kept, and an assertion that pushed a
        // future reader to remove it would be repeating dataFixSuggestions §1.1 in reverse: that entry
        // exists because "add a save()" was mistaken for a fix, and the danger of the explicit write
        // being *removed* on the strength of a belief that dirty checking covers for it is the same
        // mistake from the other side. Over a list of only the tired players it is cheap.
        assertTrue(body.contains("findBySkillsFatigueGreaterThan"),
                "recoverFatigueForWeek no longer asks for the tired players. Which query replaced the whole "
                        + "table matters more than that it did: anything that is not scoped to fatigue > 0 is "
                        + "the same defect wearing a different name.");
    }

    @Test
    @Transactional
    @DisplayName("the tired players query returns the tired players and nothing else")
    void theTiredPlayersQueryIsScopedCorrectly() {
        Team club = aTeam();
        Player tired = aPlayer(club, 40);
        Player exhausted = aPlayer(club, 100);
        Player rested = aPlayer(club, 0);

        List<Player> found = players.findBySkillsFatigueGreaterThan(0);
        List<Long> ids = found.stream().map(Player::getId).toList();

        assertTrue(ids.contains(tired.getId()), "a player with fatigue 40 is tired and must be recovered");
        assertTrue(ids.contains(exhausted.getId()), "a player with fatigue 100 is tired and must be recovered");
        assertTrue(!ids.contains(rested.getId()),
                "a player at zero fatigue was returned by a query for the tired. Recovery skips them in the "
                        + "loop, so including them costs a row loaded and a row written for no change.");
    }

    @Test
    @Transactional
    @DisplayName("recovery still works: a tired player recovers by age, a rested one is left alone")
    void recoveryStillWorks() {
        // The behavioural half, and it drives the real method. Its job is to pin the arithmetic that the
        // narrowing must not disturb, so that the guard above can be trusted to be the only thing that
        // changed — a query scoped too tightly would satisfy "never calls findAll" perfectly while
        // quietly ending weekly recovery for the whole world.
        Team club = aTeam();
        Player young = aPlayer(club, 30);
        Player older = aPlayer(club, 30);
        older.setAge(34);
        Player rested = aPlayer(club, 0);

        seasons.recoverFatigueForWeek();

        assertEquals(8, young.getSkills().getFatigue(),
                "a 20-year-old at fatigue 30 recovers the full base amount of 22");
        assertEquals(19, older.getSkills().getFatigue(),
                "a 34-year-old recovers at the 0.5 age factor, so 11 rather than 22 — recovery scaling "
                        + "with age is the reason this method exists at all");
        assertEquals(0, rested.getSkills().getFatigue(),
                "a rested player has nothing to recover and must come back unchanged");
    }

    // --- helpers ---

    private Team aTeam() {
        Team team = new Team();
        team.setName("ZZ Fatigue club " + UUID.randomUUID());
        return teams.save(team);
    }

    private Player aPlayer(Team club, int fatigue) {
        Player player = new Player();
        player.setName("ZZ Fatigue player " + UUID.randomUUID());
        player.setTeam(club);
        player.setAge(20);
        Skills skills = new Skills();
        skills.setFatigue(fatigue);
        player.setSkills(skills);
        return players.save(player);
    }

    /**
     * The text of one method's body, braces balanced, or null when there is no such method.
     *
     * <p><b>Matched on the declaration, not on the name.</b> The first version searched for the bare
     * name and found the <em>call site</em> in {@code applyWeekMaintenance}, then balanced braces from
     * whatever {@code &#123;} came next — so it read a block belonging to another method and passed
     * against the very code it was written to catch. A call is followed by {@code ;}; a declaration is
     * followed by <code>&#123;</code>, and that is the whole difference.
     */
    private String methodBody(String code, String method) {
        Matcher declaration = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(\\s*\\)\\s*\\{").matcher(code);
        if (!declaration.find()) {
            return null;
        }
        int open = code.indexOf('{', declaration.start());
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i);
                }
            }
        }
        return null;
    }

    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
