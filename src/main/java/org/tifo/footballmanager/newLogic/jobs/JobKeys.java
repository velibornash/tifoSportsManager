package org.tifo.footballmanager.newLogic.jobs;

/**
 * The job keys that another job has to name — matchday pairs and the two table reconciliations.
 *
 * <p><b>Why a class for a handful of strings.</b> A publish job declares a dependency on the prepare
 * job that belongs to it, and such a dependency is only correct while both ends build the key the
 * same way. They were built in two places and the phase was dropped from the second one: every
 * national publish waited on {@code matchday-prepare-<day>} — a key no job has — so the six national
 * publishes of the season sat in {@code WAITING_FOR_DEPENDENCY} for ever, silently. One builder
 * cannot drift from itself.
 *
 * <p>{@code phase} is {@code null} for the club calendar and {@code "qualifier"} / {@code "tournament"}
 * for the national pairs, which are the only day where two pairs of the same day exist.
 */
public final class JobKeys {

    /** The reconciliation of the day-3 league matchday, which runs on day 4. */
    public static final String RECONCILE_AFTER_FIRST_LEAGUE_DAY = "league-table-reconcile-a";

    /** The reconciliation of the day-7 league matchday, which runs late on day 7 itself. */
    public static final String RECONCILE_AFTER_SECOND_LEAGUE_DAY = "league-table-reconcile-b";

    private JobKeys() {
    }

    /** {@code matchday-prepare-3} or {@code matchday-prepare-qualifier-2}. */
    public static String prepare(int day, String phase) {
        return "matchday-prepare-" + (phase == null ? "" : phase + "-") + day;
    }

    /** {@code matchday-publish-3} or {@code matchday-publish-qualifier-2}. */
    public static String publish(int day, String phase) {
        return "matchday-publish-" + (phase == null ? "" : phase + "-") + day;
    }

    /**
     * Which pair serves a day: the national one on the three days the club calendar has no kickoff,
     * the club one on the four it has.
     *
     * <p>Read from {@link org.tifo.footballmanager.newLogic.model.GameDay} rather than from a list of
     * day numbers, because the calendar is already the statement of which days kick off and which do
     * not. The qualification/tournament word is the only thing the week adds.
     */
    public static String phaseFor(int day, int week) {
        if (org.tifo.footballmanager.newLogic.model.GameDay.of(day).kickoffTime() != null) {
            // A club match day: the club pair is ANY_WEEK and prepares and publishes the whole day.
            return null;
        }
        return week == org.tifo.footballmanager.newLogic.model.NationalTournamentSchedule.QUALIFYING_WEEK
                ? "qualifier"
                : "tournament";
    }
}
