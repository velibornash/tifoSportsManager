package org.example.footballmanager.newLogic.model;

/**
 * What a notification is about — the reason it exists, and the branch its payload takes.
 *
 * <p>Owner decision, 2026-10-05: a notification store did not exist and had to be built. The dashboard
 * ticker was recomputed from eight live endpoints on every render and thrown away, so "you have a
 * message" was a calculation rather than a fact.
 *
 * <p>The enum is the contract between whoever writes a notification and whoever reads one. A new kind
 * needs a renderer; a renderer for an unhandled kind shows the fallback text rather than nothing, which
 * is the only way this goes wrong quietly.
 */
public enum NotificationKind {

    /** Somebody replied in a forum topic. Payload: topic id, topic title, the replier's name. */
    FORUM_REPLY,

    /** A new direct message arrived. Payload: thread id, the sender's name, the subject. */
    PM_RECEIVED,

    /** A moderator banned this manager from the forum. Payload: days, the reason. */
    FORUM_BANNED,

    /** A registration request was approved or rejected. Payload: the decision, the club. */
    REGISTRATION_DECIDED,

    /**
     * Somebody offered one of our players out on loan, or offered us one of theirs.
     *
     * <p>Payload: the loan id, the player's name, the other club's name, and the season the loan runs.
     */
    LOAN_PROPOSED,

    /**
     * A loan moved on: agreed, taken in, asked to end, or ended.
     *
     * <p>Payload: the loan id, the player's name, the other club's name, and what happened. The owner's
     * point, 2026-10-08: *"loan bi trebao izmedju ostalog da stize u notifications"* — a loan that starts
     * and a player who arrives are things a manager should be told about rather than discover by opening
     * the loans screen.
     */
    LOAN_MOVED
}