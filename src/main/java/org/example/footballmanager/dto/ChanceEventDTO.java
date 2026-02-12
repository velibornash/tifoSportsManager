package org.example.footballmanager.dto;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ChanceEventDTO extends MatchEventDTO
{
    private String playerName;
    private String type;
    private String teamName;
    private boolean dangerous;
}
