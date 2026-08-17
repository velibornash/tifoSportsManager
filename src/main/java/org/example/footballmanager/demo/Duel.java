package org.example.footballmanager.demo;

/** Jedan aktivan prostorni duel; resolution i posledice vode odvojeni slojevi. */
public final class Duel {
    private final Player attacker;
    private final Player defender;
    private final Position contestPosition;
    private final DuelType type;

    public Duel(Player attacker, Player defender, Position contestPosition, DuelType type) {
        this.attacker = attacker;
        this.defender = defender;
        this.contestPosition = contestPosition;
        this.type = type;
    }

    public Player getAttacker() { return attacker; }
    public Player getDefender() { return defender; }
    public Position getContestPosition() { return contestPosition; }
    public DuelType getType() { return type; }
}
