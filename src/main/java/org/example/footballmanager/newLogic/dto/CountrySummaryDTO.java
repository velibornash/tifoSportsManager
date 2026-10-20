package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import org.example.footballmanager.newLogic.model.Country;

@Data
public class CountrySummaryDTO {
    private Long id;
    private String name;
    private String isoCode;
    private String flagImagePath;
    private String currencyCode;
    private Integer reputation;
    private Integer youthRating;
    private Long seniorNationalTeamId;
    private Long u21NationalTeamId;
    /** ACTIVE or SIMULATED. The world page decides what is clickable from this, not from a guess. */
    private String state;

    public static CountrySummaryDTO from(Country country) {
        CountrySummaryDTO dto = new CountrySummaryDTO();
        dto.setId(country.getId());
        dto.setName(country.getName());
        dto.setIsoCode(country.getIsoCode());
        dto.setFlagImagePath(country.getFlagImagePath());
        dto.setCurrencyCode(country.getCurrencyCode());
        dto.setState(country.getState() == null ? null : country.getState().name());
        dto.setReputation(country.getReputation());
        dto.setYouthRating(country.getYouthRating());
        dto.setSeniorNationalTeamId(country.getSeniorNationalTeam() != null ? country.getSeniorNationalTeam().getId() : null);
        dto.setU21NationalTeamId(country.getU21NationalTeam() != null ? country.getU21NationalTeam().getId() : null);
        return dto;
    }
}
