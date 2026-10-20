package org.tifo.footballmanager.newLogic.dto;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.tifo.footballmanager.newLogic.model.Player;

@Data
@EqualsAndHashCode(callSuper = true)
public class OffsideEventDTO extends MatchEventDTO{
    private String playerName;
    private String teamName;
}
