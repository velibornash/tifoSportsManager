package org.example.footballmanager.newLogic.dto;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data
@EqualsAndHashCode(callSuper = true)
public class FreeKickEventDTO extends MatchEventDTO {
    private String teamName;
    private String playerName;
    private String takerName;
}
