package org.example.footballmanager.newLogic.model.event;

import java.util.Set;

public sealed interface MatchEvent
    permits GoalEvent, ShotEvent, PassEvent, DuelEvent, FoulEvent, CardEvent,
            OffsideEvent, SetPieceEvent, PenaltyEvent, InjuryEvent, SubstitutionEvent,
            MatchStartEvent, MatchEndEvent,
            PossessionStartEvent, PossessionEndEvent,
            ReceiveEvent, PassInterceptedEvent, PassIncompleteEvent,
            DribbleEvent, DribbleLostEvent,
            TackleEvent, TackleFoulEvent,
            ShotSavedEvent, ShotBlockedEvent, ShotMissedEvent,
            CrossEvent, CrossClearedEvent, CrossHeaderEvent,
            ClearanceEvent,
            GkSaveEvent, GkCatchEvent, GkPunchEvent, GkDistributionEvent,
            ThroughBallEvent, LongBallEvent, VarReviewEvent, LooseBallEvent,
            BallCarrierDecisionEvent {

    int minute();
    int tick();
    MatchEventType type();


    enum MatchEventType {
        MATCH_START, MATCH_END,
        GOAL, SHOT_ON_TARGET, SHOT_OFF_TARGET, SHOT_SAVED, SHOT_BLOCKED, SHOT_MISSED,
        PASS, PASS_SHORT, PASS_LONG, PASS_INTERCEPTED, PASS_INCOMPLETE, INTERCEPTION,
        RECEIVE,
        THROUGH_BALL, LONG_BALL,
        CROSS, CROSS_CLEARED, CROSS_HEADER,
        DRIBBLE, DRIBBLE_LOST,
        TACKLE, TACKLE_FOUL,
        DUEL,
        FOUL, FREE_KICK, PENALTY,
        YELLOW_CARD, RED_CARD,
        OFFSIDE,
        CORNER, THROW_IN, GOAL_KICK,
        CLEARANCE,
        GK_SAVE, GK_CATCH, GK_PUNCH, GK_DISTRIBUTION,
        POSSESSION_START, POSSESSION_END, LOOSE_BALL, BALL_CARRIER_DECISION,
        INJURY, SUBSTITUTION,
        VAR_REVIEW;

        /**
         * The types that are a goal which counted, and therefore may be credited to a scorer.
         *
         * <p><b>One definition, on the enum that owns the vocabulary, because three call sites needed it
         * and each had worked it out for itself.</b> The scorer's answer was {@code contains("GOAL")},
         * which also matched {@code GOAL_DISALLOWED} and {@code VAR_GOAL_OVERTURNED} — so a player was
         * credited with a goal VAR threw out, on the league's top-scorers page, the top-assists page and
         * the club milestone list. Three copies of this predicate is how that stayed invisible.
         *
         * <p><b>The engine settles it, so this is not a matter of taste.</b> {@code BallResultHandler}
         * asks VAR <i>before</i> it calls {@code goalScored}, emitting {@code GOAL_DISALLOWED} and
         * {@code VAR_GOAL_OVERTURNED} on the way out. A disallowed goal is in neither the scoreline nor
         * the statistics, so crediting it made the scorer's total disagree with his team's.
         *
         * <p>{@code GOAL_KICK} is excluded because it is a restart that contains the substring by
         * accident. {@code VAR_GOAL_CONFIRMED} is included because VAR confirmed that goal and it stands.
         * {@code OWN_GOAL} and {@code PENALTY_GOAL} are included for the readers that already treat
         * them as goals; a goal the engine words differently must not silently stop counting.
         */
        private static final Set<String> COUNTED_GOALS = Set.of(
                "GOAL", "VAR_GOAL_CONFIRMED", "OWN_GOAL", "PENALTY_GOAL");

        /** A goal VAR ruled out: written for the audit trail, never credited to a scorer. */
        private static final Set<String> RULED_OUT_GOALS = Set.of(
                "GOAL_DISALLOWED", "VAR_GOAL_OVERTURNED");

        /** Whether this event type is a goal that counted, and so belongs in a scorer's total. */
        public static boolean countsAsGoal(String type) {
            return type != null && COUNTED_GOALS.contains(normalise(type));
        }

        /**
         * Whether this event type is a goal at all — counted or ruled out.
         *
         * <p>For a match report's key moments, where a VAR overturn <i>is</i> the key moment and belongs
         * on the page. For a scorer's total it does not, which is the difference between this and
         * {@link #countsAsGoal(String)}.
         */
        public static boolean isGoalRelated(String type) {
            if (type == null) {
                return false;
            }
            String normalised = normalise(type);
            return COUNTED_GOALS.contains(normalised) || RULED_OUT_GOALS.contains(normalised);
        }

        /** A VAR decision on a goal, either way — what a timeline needs to explain the score. */
        public static boolean isVarDecision(String type) {
            return type != null && (normalise(type).equals("VAR")
                    || normalise(type).startsWith("VAR_"));
        }

        /**
         * The same folding the readers do: case, dashes and spaces are all the same character as far as
         * a type is concerned, so the writer and the reader must agree on that before either compares a
         * type to anything.
         */
        private static String normalise(String type) {
            return type.trim().toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
        }
    }
}
