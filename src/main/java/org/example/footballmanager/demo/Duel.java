package org.example.footballmanager.demo;

/** Jedan aktivan prostorni duel; resolution i posledice vode odvojeni slojevi. */
public final class Duel {
    private final Player attacker;
    private final Player defender;
    private final Position contestPosition;
    private final DuelType type;
    private final String actionId;
    private final Action action; // Za pass quality u RECEIVE_PASS duelu

    public Duel(Player attacker, Player defender, Position contestPosition, DuelType type) {
        this(attacker, defender, contestPosition, type, null, null);
    }

    public Duel(Player attacker, Player defender, Position contestPosition,
                DuelType type, String actionId) {
        this(attacker, defender, contestPosition, type, actionId, null);
    }

    public Duel(Player attacker, Player defender, Position contestPosition,
                DuelType type, String actionId, Action action) {
        this.attacker = attacker;
        this.defender = defender;
        this.contestPosition = contestPosition;
        this.type = type;
        this.actionId = actionId;
        this.action = action;
    }

    public Player getAttacker() { return attacker; }
    public Player getDefender() { return defender; }
    public Position getContestPosition() { return contestPosition; }
    public DuelType getType() { return type; }
    public String getActionId() { return actionId; }
    public Action getAction() { return action; }
}
