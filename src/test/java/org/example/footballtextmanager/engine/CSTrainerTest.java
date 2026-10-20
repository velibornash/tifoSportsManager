package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSTrainingFocus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Squad development must be slow, age-aware and unable to break the skill scale.
 *
 * <p>Before this existed, no code path ever raised a skill: squads were frozen at whatever the
 * seeder rolled on the first boot, so the Golden Boot was the only progression in the game. The
 * obvious failure mode for a fix like this is a developer slider that is either invisible (gains
 * that round to zero) or ruinous (every player at the ceiling within a season), so both ends are
 * pinned here.
 */
class CSTrainerTest {

    private static final int MANY_ROUNDS = 400;

    @Test
    @DisplayName("Skills never exceed the 1-20 scale, however long a squad trains")
    void skillsStayInsideTheScale() {
        CSTrainer trainer = new CSTrainer(new Random(7));
        List<CSPlayer> squad = List.of(player(1L, "ATT", 19), player(2L, "DEF", 20), player(3L, "GK", 18));

        for (int round = 0; round < MANY_ROUNDS; round++) {
            trainer.applyRound(squad, CSTrainingFocus.ATTACK);
        }

        for (CSPlayer p : squad) {
            for (int skill : new int[]{p.getPace(), p.getPassing(), p.getShooting(), p.getTechnique(),
                    p.getPlaymaker(), p.getDefending(), p.getStamina(), p.getGoalkeeper()}) {
                assertTrue(skill >= 1 && skill <= 20,
                        p.getName() + " escaped the 1-20 scale after " + MANY_ROUNDS + " rounds: " + skill);
            }
            assertTrue(p.getRating() >= 1 && p.getRating() <= 10,
                    "Rating escaped 1-10: " + p.getRating());
        }
    }

    @Test
    @DisplayName("A focus actually raises something — otherwise the whole feature is invisible")
    void aFocusMovesAttributes() {
        CSTrainer trainer = new CSTrainer(new Random(11));
        List<CSPlayer> squad = new ArrayList<>(List.of(player(1L, "ATT", 19)));
        int paceBefore = squad.getFirst().getPace();

        for (int round = 0; round < 30; round++) {
            trainer.applyRound(squad, CSTrainingFocus.ATTACK);
        }

        assertTrue(squad.getFirst().getPace() > paceBefore,
                "30 rounds of attack focus left pace at " + paceBefore
                        + " — the development step rounds every gain away and the page is decoration");
    }

    @Test
    @DisplayName("A 34-year-old learns nothing; a 19-year-old does")
    void ageDecidesHowMuchIsLeftToLearn() {
        CSTrainer trainer = new CSTrainer(new Random(3));
        List<CSPlayer> young = new ArrayList<>(List.of(player(1L, "ATT", 19)));
        List<CSPlayer> veteran = new ArrayList<>(List.of(player(2L, "ATT", 34)));

        int youngBefore = young.getFirst().getPace();
        int veteranBefore = veteran.getFirst().getPace();

        for (int round = 0; round < 40; round++) {
            trainer.applyRound(young, CSTrainingFocus.ATTACK);
            trainer.applyRound(veteran, CSTrainingFocus.ATTACK);
        }

        assertEquals(veteranBefore, veteran.getFirst().getPace(),
                "A 34-year-old gained pace. Past the learning age a squad must stop improving, "
                        + "or age stops meaning anything in this mode");

        assertTrue(young.getFirst().getPace() > youngBefore, "The young player gained nothing");
    }

    @Test
    @DisplayName("The focus respects position: an attack session barely helps a centre-back")
    void focusIsWeightedByRole() {
        CSTrainer trainer = new CSTrainer(new Random(5));
        List<CSPlayer> striker = new ArrayList<>(List.of(player(1L, "ATT", 20)));
        List<CSPlayer> centreBack = new ArrayList<>(List.of(player(2L, "DEF", 20)));

        for (int round = 0; round < 25; round++) {
            trainer.applyRound(striker, CSTrainingFocus.ATTACK);
            trainer.applyRound(centreBack, CSTrainingFocus.ATTACK);
        }

        int strikerGain = striker.getFirst().getPace() - 10;
        int defenderGain = centreBack.getFirst().getPace() - 10;

        assertTrue(strikerGain > defenderGain,
                "Attack focus lifted a centre-back's pace (" + defenderGain
                        + ") at least as much as a striker's (" + strikerGain + ")");
    }

    @Test
    @DisplayName("Rating follows the skills rather than drifting")
    void ratingIsRecomputedFromSkills() {
        CSTrainer trainer = new CSTrainer(new Random(13));
        List<CSPlayer> squad = List.of(player(1L, "ATT", 19));

        trainer.applyRound(squad, CSTrainingFocus.CREATION);

        assertEquals(squad.getFirst().calculateRating(), squad.getFirst().getRating(),
                "Rating must be re-derived from skills after training, otherwise the two drift "
                        + "and the scouting report stops matching the simulation");
    }

    @Test
    @DisplayName("An unknown focus name falls back to Balanced instead of throwing")
    void unknownFocusIsNotAnError() {
        assertEquals(CSTrainingFocus.BALANCED, CSTrainingFocus.from("nonsense"));
        assertEquals(CSTrainingFocus.BALANCED, CSTrainingFocus.from(null));
        assertEquals(CSTrainingFocus.BALANCED, CSTrainingFocus.from("  "));
        assertEquals(CSTrainingFocus.DEFENCE, CSTrainingFocus.from("defence"));
        // Case-insensitive and whitespace-tolerant, because the value travels through a query string.
        assertEquals(CSTrainingFocus.ATTACK, CSTrainingFocus.from("  attack  "));
    }

    private static CSPlayer player(long id, String position, int age) {
        return CSPlayer.builder()
                .id(id)
                .name("Player " + id)
                .position(position)
                .age(age)
                .rating(1)
                .form(6.0)
                .fatigue(0.0)
                .stamina(10)
                .goalkeeper(10)
                .defending(10)
                .pace(10)
                .technique(10)
                .playmaker(10)
                .passing(10)
                .shooting(10)
                .value(1000.0)
                .earnings(100.0)
                .height(180)
                .weight(75)
                .build();
    }
}