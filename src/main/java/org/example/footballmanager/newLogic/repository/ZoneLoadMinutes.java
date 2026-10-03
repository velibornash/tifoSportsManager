package org.example.footballmanager.newLogic.repository;

/**
 * One player's zone load, as the recovery aggregate needs it: the four numbers, not the row.
 *
 * <p>{@code effectiveMinutes} is the same arithmetic as
 * {@link org.example.footballmanager.newLogic.model.PlayerZoneLoad#effectiveMinutes()}, and it is
 * written out here rather than reached through an entity on purpose — the point of the projection is
 * that no entity is loaded at all. **That makes this the second copy of the rule**, which is normally
 * the thing to avoid, so the reasoning is worth stating: the first version loaded a full
 * {@code PlayerZoneLoad} per row to call a one-line method on it, and at a matchday's worth of rows
 * that is the dominant cost of the daily recovery job.
 *
 * <p>It is also a second copy in the sense that matters — the zone's {@code workRate} is read from the
 * same {@code Zone} enum the entity uses, so the two cannot drift on that side. Only the
 * {@code minutes × intensity} part is restated, and
 * {@link ZoneLoadServiceTest} asserts the two agree on a row built by the engine.
 */
public record ZoneLoadMinutes(Long playerId,
                              org.example.footballmanager.newLogic.model.Zone zone,
                              double minutes,
                              double intensity) {

    /** Identical to {@code PlayerZoneLoad.effectiveMinutes()}. */
    public double effectiveMinutes() {
        return minutes * intensity * (zone == null ? 1.0 : zone.workRate());
    }
}
