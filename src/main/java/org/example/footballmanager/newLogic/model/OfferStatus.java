package org.example.footballmanager.newLogic.model;

/** Where an offer is in its life. */
public enum OfferStatus {

    /** On the table, waiting for a response. */
    OPEN,

    /** The seller has answered with different terms. */
    COUNTERED,

    /** Agreed by both sides. */
    ACCEPTED,

    /** Refused outright. */
    REJECTED,

    /** Withdrawn by the buyer. */
    WITHDRAWN,

    /** Ran out of time. */
    EXPIRED;

    public boolean isLive() {
        return this == OPEN || this == COUNTERED;
    }

    public boolean isClosed() {
        return !isLive();
    }
}
