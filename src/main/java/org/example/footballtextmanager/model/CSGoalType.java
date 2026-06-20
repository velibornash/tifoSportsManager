package org.example.footballtextmanager.model;

public enum CSGoalType {
    HEADER,      // headed from a cross or corner
    VOLLEY,      // first-time volley or half-volley
    TAP_IN,      // close-range finish after a save, cross or rebound
    LONG_RANGE,  // shot from outside the area (~25+ yards)
    ONE_ON_ONE,  // clean through-ball situation, keeper beaten 1v1
    SCREAMER,    // powerful long-distance rocket
    FREE_KICK,   // direct free kick
    POACHERS,    // opportunist finish at the back post or 6-yard box
    COUNTER      // scored at the end of a fast counter-attack
}
