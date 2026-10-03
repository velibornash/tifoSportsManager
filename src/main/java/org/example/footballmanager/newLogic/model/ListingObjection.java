package org.example.footballmanager.newLogic.model;

/**
 * Why a player will not accept being put on the transfer list.
 *
 * <p>Not a {@code TransferStatus}, deliberately. The {@code transfer} table carries a live Postgres
 * {@code CHECK} constraint on its four status values which {@code ddl-auto=update} will not
 * recreate, so a fifth constant would fail on insert until somebody dropped the constraint by hand.
 * This is a player's position on a listing, not a state of the listing itself: a listing can be
 * {@code LISTED} and objected to at the same time.
 *
 * <p>The three reasons are kept apart because they have three different managerial answers. A club
 * that pays the player more resolves a {@link #WAGE_DISPUTE} by giving him a contract. A club that
 * cannot afford to lose a {@link #DOES_NOT_WANT_TO_LEAVE} has to either pay compensation or keep him
 * and stop selling. Collapsing them into one boolean would leave the manager with a fee to pay and
 * no idea what it buys.
 */
public enum ListingObjection {

    /** No objection. */
    NONE(null),

    /** He is not unhappy, but he does not want to be treated as merchandise. */
    UNHAPPY_TO_BE_LISTED(
            "He is not unhappy to be here, but he does not want to be treated as merchandise."),

    /** He thinks he is underpaid, and wants a contract before he is sold. */
    WAGE_DISPUTE(
            "He believes he is underpaid and wants a new contract before he is put up for sale."),

    /** He wants to be wanted, and does not want to leave at all. */
    DOES_NOT_WANT_TO_LEAVE(
            "He does not want to leave. He wants to be told he is wanted here.");

    private final String label;

    ListingObjection(String label) {
        this.label = label;
    }

    /** What the player says, or null for {@link #NONE}. Shown to the manager as the reason. */
    public String label() {
        return label;
    }

    /** Whether this objection stops the club moving the player. */
    public boolean isBlocking() {
        return this != NONE;
    }

    /** Nothing to resolve. */
    public boolean isNone() {
        return this == NONE;
    }
}