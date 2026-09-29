package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.util.PlayerRatingBackfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player's rating is one thing, derived from his skills (owner decision, 2026-09-29).
 *
 * <p>The {@code rating} column had three writers on three scales and the OVR formula read it three
 * times as if it were one of them. In a freshly seeded world 2 350 players read exactly 96
 * ({@code BASE_SKILL * 8}) and 5 250 read 0, and the formula only applied its bonus when the value
 * was positive — so two players of identical ability sat about six OVR points apart, decided by
 * which seeder created the row.
 *
 * <p>These pin the definition and the consequence. The consequence matters more than the definition:
 * a rating that is merely miscalibrated is a cosmetic number, and a rating that decides which
 * player's OVR is six points higher is a balance bug.
 */
class PlayerCareerRatingTest extends BaseTest {

    @Test
    @DisplayName("a better player has a better career rating, at every position")
    void ratingRisesWithAbility() {
        for (Position position : Position.values()) {
            int weak = playerWithSkills(10, position).careerRating();
            int average = playerWithSkills(14, position).careerRating();
            int strong = playerWithSkills(18, position).careerRating();

            assertTrue(weak < average && average < strong,
                    () -> position + ": " + weak + " / " + average + " / " + strong
                            + " - a better player must read higher");
        }
    }

    @Test
    @DisplayName("rating is bounded, and a player with no skills is not zero")
    void ratingIsBounded() {
        assertEquals(1, new Player().careerRating(), "a player with no skills is one, not zero - "
                + "zero is what the OVR formula reads as 'never rated'");
        assertTrue(playerWithSkills(20, Position.ATT).careerRating() <= 100);
        assertTrue(playerWithSkills(1, Position.ATT).careerRating() >= 1);
    }

    @Test
    @DisplayName("the OVR gap between a bot and a human of the same ability is gone")
    void identicalAbilityGivesIdenticalOverall() {
        // This is the test the kanban row should have had. The old code wrote 96 for every bot, and
        // calculateOverall read that as a career value, so two players built the same way differed by
        // about six OVR points purely because of which seeder made the row.
        Player bot = playerWithSkills(12, Position.ATT);
        Player human = playerWithSkills(12, Position.ATT);

        // What the old unbounded term did with those two ratings, computed here rather than asserted
        // against, because the OVR formula has already been fixed and can no longer demonstrate it.
        double oldTermForBot = (96 - 62.0) / 5.5;
        double oldTermForHuman = 0.0;
        assertTrue(oldTermForBot - oldTermForHuman >= 6.0,
                "the defect this fixes: the old rating term handed a seeded bot "
                        + oldTermForBot + " of free OVR that an identical human never got");

        // The guarantee, which is what actually matters: whatever is in the column, two players of
        // identical ability read identically once the rating is derived for both.
        bot.setRating(bot.careerRating());
        human.setRating(human.careerRating());
        assertEquals(PlayerDTO.from(bot).getOverall(), PlayerDTO.from(human).getOverall());

        // And a stale column left behind by a pre-migration world cannot decide it either, which is
        // the case the backfill exists for. It is allowed to nudge - the bounded term exists to catch
        // a player whose skills have moved since he was rated - but only by the bound, not the six
        // points the unbounded term handed out.
        Player stale = playerWithSkills(12, Position.ATT);
        stale.setRating(96);
        int derived = PlayerDTO.from(bot).getOverall();
        int withStaleRating = PlayerDTO.from(stale).getOverall();
        assertTrue(Math.abs(withStaleRating - derived) <= 2,
                () -> "a leftover rating of 96 moved OVR by " + (withStaleRating - derived));
    }

    @Test
    @DisplayName("the rating cannot hand out unbounded free OVR")
    void theRatingTermIsBounded() {
        // Even with a stale, absurd rating, moving it must not move OVR by much. Before the change an
        // unbounded (rating - 62) / 5.5 turned 96 into a free +6.2 on top of the skill term, and a
        // defender or keeper collected the same bonus a second time inside roleContribution.
        Player player = playerWithSkills(13, Position.ATT);
        int baseline = PlayerDTO.from(player).getOverall();

        player.setRating(1000);
        int inflated = PlayerDTO.from(player).getOverall();
        assertTrue(Math.abs(inflated - baseline) <= 2,
                () -> "an absurd rating moved OVR by " + (inflated - baseline)
                        + ", so the term is not bounded");

        player.setRating(1);
        int deflated = PlayerDTO.from(player).getOverall();
        assertTrue(Math.abs(deflated - baseline) <= 2,
                () -> "a rating of 1 moved OVR by " + (deflated - baseline));
    }

    @Test
    @Transactional
    @DisplayName("the backfill moves a stale rating and leaves a correct one alone")
    void theBackfillConverges() {
        Player stale = playerRepository.save(playerWithSkills(11, Position.MID));
        Player correct = playerRepository.save(playerWithSkills(11, Position.MID));

        stale.setRating(96);
        correct.setRating(correct.careerRating());
        playerRepository.save(stale);
        playerRepository.save(correct);

        var result = playerRatingBackfill.backfill();

        assertEquals(1, result.get("changed"),
                "only the stale row moves, so a re-boot does no work");
        assertEquals(correct.careerRating(), playerRepository.findById(correct.getId()).orElseThrow().getRating(),
                "and a row already carrying the derived value is untouched");
    }

    @Test
    @Transactional
    @DisplayName("the backfill is idempotent")
    void theBackfillIsSafeToRunTwice() {
        playerRepository.save(playerWithSkills(9, Position.DEF));
        playerRatingBackfill.backfill();
        var second = playerRatingBackfill.backfill();
        assertEquals(0, second.get("changed"), "a settled world must not be rewritten on every boot");
    }

    // ---------- helpers ----------

    private Player playerWithSkills(int level, Position position) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, level);
        skills.setSkill(SkillName.STAMINA, level);
        skills.setSkill(SkillName.GOALKEEPER, level);
        skills.setSkill(SkillName.DEFENDER, level);
        skills.setSkill(SkillName.TECHNIQUE, level);
        skills.setSkill(SkillName.PLAYMAKER, level);
        skills.setSkill(SkillName.PASSING, level);
        skills.setSkill(SkillName.STRIKER, level);
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName(level + " " + position);
        p.setPosition(position);
        p.setSkills(skills);
        p.setAge(24);
        p.setForm(6.0);
        p.setRating(0);
        return p;
    }

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerRatingBackfill playerRatingBackfill;
}
