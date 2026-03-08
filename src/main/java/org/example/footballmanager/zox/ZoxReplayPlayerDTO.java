package org.example.footballmanager.zox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoxReplayPlayerDTO {
    private Long playerId;
    private String name;
    private String shortName;
    private Integer squadNumber;
    private String position;
    private String teamSide;
    private boolean starter;

    @JsonProperty("id")
    public Long getIdAlias() {
        return playerId;
    }

    @JsonProperty("shirt_number")
    public Integer getShirtNumberAlias() {
        return squadNumber;
    }

    @JsonProperty("last_name")
    public String getLastNameAlias() {
        if (name == null || name.isBlank()) {
            return name;
        }
        String[] parts = name.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    @JsonProperty("is_home")
    public boolean isHomeAlias() {
        return "HOME".equalsIgnoreCase(teamSide);
    }

    @JsonProperty("is_starter")
    public boolean isStarterAlias() {
        return starter;
    }
}