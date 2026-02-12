package org.example.footballmanager.dto;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.example.footballmanager.model.Team;

@Data
@EqualsAndHashCode(callSuper = true)
public class CornerEventDTO extends MatchEventDTO {
    private String teamName;
    private String playerName;
}
