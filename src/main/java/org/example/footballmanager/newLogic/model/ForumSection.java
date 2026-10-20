package org.example.footballmanager.newLogic.model;

/**
 * The forum's two fixed sections (owner, 2026-10-05).
 *
 * <p>Two, and they are the owner's: **TIFO** and everything else. Not a category table, not
 * admin-editable. A forum whose sections can be created from the interface needs somebody to moderate the
 * sections before the forum needs its first argument, and a manager opening a topic has to know where to
 * put it without reading a list.
 *
 * <p>The name for the second one is a judgement call. {@code GENERAL} is what the UI shows as
 * "Non-TIFO" — a manager looking for "where do I talk about transfers" should not have to guess whether
 * "general" includes TIFO.
 */
public enum ForumSection {

    TIFO("TIFO", "Chants, visuals, choreography and matchday atmosphere."),

    GENERAL("Non-TIFO", "Everything else: tactics, transfers, the game itself, and the sport around it.");

    private final String label;
    private final String description;

    ForumSection(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}