package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSMatchEvent {
    private int minute;
    private CSEventType eventType;
    private CSGoalType goalType;
    private String playerName;
    private String assistName;
    private String teamName;
    private String description;
    private String playerOutName;
    private String playerInName;
    private String scoreAfterGoal;
    private boolean penaltyScored;

    /**
     * Id igrača koji je izazvao događaj, i id asistenta za gol.
     *
     * <p>Oba su bila izostavljena, pa se svaki događaj pripisivao igraču <b>po imenu</b>. Imena
     * nisu jedinstvena: {@code pickFirstName() + " " + pickLastName()} daje 30 × 27 = 810 kombinacija
     * za ~240 igrača u 16 klubova, pa se duplikati praktično sigurno pojavljuju između klubova.
     * Uz to, brojanje u {@code assignRatings} nije proveravalo <i>koji je tim</i> u događaju —
     * igrač sa istim imenom u protivničkom klubu je dobijao tuđe golove, i to na obe strane
     * meča. Oba problema nestaju ako se pripisivanje radi po id-ju.
     *
     * <p>{@code playerName} je zadržan jer ga frontend koristi za prikaz.
     */
    private Long playerId;
    private Long assistPlayerId;
    private Long playerOutId;
    private Long playerInId;
    
    // Shot statistics
    private Double xG;
    private Integer distance;
}
