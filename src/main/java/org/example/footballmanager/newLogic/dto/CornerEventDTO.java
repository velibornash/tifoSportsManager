package org.example.footballmanager.newLogic.dto;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.example.footballmanager.newLogic.model.Team;

@Data
@EqualsAndHashCode(callSuper = true)
public class CornerEventDTO extends MatchEventDTO {
    private String teamName;
    private String playerName;
    private String takerName;
}
