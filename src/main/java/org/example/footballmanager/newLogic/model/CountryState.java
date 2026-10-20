package org.example.footballmanager.newLogic.model;

/**
 * Whether a country is played or merely represented (owner, 2026-09-29).
 *
 * <p>The owner chose this over seeding all 48 countries up front. Serbia is played: it has a full
 * five-tier pyramid, real clubs, real fixtures and a simulated season. The other 47 exist, have a
 * national side, and otherwise cost nothing.
 *
 * <p><b>Why a country without clubs is a problem worth solving properly.</b> A national team draws its
 * squad from the clubs in its country, so a country with no clubs produced an empty national side - a
 * row with a name and no players, which cannot be drawn against. That is why the internationals drew
 * nothing when they were built. A SIMULATED country therefore gets a bot squad, so it can be played
 * against, and activating it later replaces that squad with one built from real clubs.
 */
public enum CountryState {

    /**
     * Fully simulated: the tier pyramid, clubs, squads and a real season.
     *
     * <p>Only Serbia to begin with.
     */
    ACTIVE,

    /**
     * Represented but not played: a national side with a bot squad, no club pyramid.
     *
     * <p>Cheap, and enough for the national-team side of the game to work across the whole map.
     */
    SIMULATED
}
