package org.example.footballmanager.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ThrowInEventDTO extends MatchEventDTO {
    private String takerName;
}
