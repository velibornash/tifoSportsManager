package org.example.footballmanager.dto;

import lombok.Data;
import org.example.footballmanager.model.Team;

@Data
public class TeamSummaryDTO {
    private Long id;
    private String name;
    private String type;
    private Long countryId;
    private String countryIsoCode;
    private Long competitionId;
    private String competitionName;
    private Double budget;
    private Double reputation;
    private boolean humanControlled;

    public static TeamSummaryDTO from(Team team) {
        TeamSummaryDTO dto = new TeamSummaryDTO();
        dto.setId(team.getId());
        dto.setName(team.getName());
        dto.setType(team.getType() != null ? team.getType().name() : null);
        dto.setCountryId(team.getCountry() != null ? team.getCountry().getId() : null);
        dto.setCountryIsoCode(team.getCountry() != null ? team.getCountry().getIsoCode() : null);
        dto.setCompetitionId(team.getCompetition() != null ? team.getCompetition().getId() : null);
        dto.setCompetitionName(team.getCompetition() != null ? team.getCompetition().getName() : null);
        dto.setBudget(team.getBudget());
        dto.setReputation(team.getReputation());
        dto.setHumanControlled(team.isHumanControlled());
        return dto;
    }
}
