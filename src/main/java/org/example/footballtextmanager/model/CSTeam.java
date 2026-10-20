package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A club in the text mode.
 *
 * <p>There is deliberately no {@code formation} field. It used to be hardcoded to {@code "4-4-2"}
 * by the mapper for every club, which looked like a declared preference and was read as one —
 * {@code CSOpponentProfile} honoured a stated shape, so all seventeen clubs were locked to the
 * same one and the league played identically. Nothing ever wrote a real value, so the field only
 * ever created a second source of truth to disagree with the squad.
 *
 * <p>A club's shape is derived from the players it actually has. See {@code CSOpponentProfile}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSTeam {
    private Long id;
    private String name;
    private double budget;
    private double reputation;
    private String stadiumName;
    private int stadiumCapacity;
}
