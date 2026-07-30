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

    public static CountrySummaryDTO from(Country country) {
        CountrySummaryDTO dto = new CountrySummaryDTO();
        dto.setId(country.getId());
        dto.setName(country.getName());
        dto.setIsoCode(country.getIsoCode());
        dto.setFlagImagePath(country.getFlagImagePath());
        dto.setCurrencyCode(country.getCurrencyCode());
        dto.setReputation(country.getReputation());
        dto.setYouthRating(country.getYouthRating());
        dto.setSeniorNationalTeamId(country.getSeniorNationalTeam() != null ? country.getSeniorNationalTeam().getId() : null);
        dto.setU21NationalTeamId(country.getU21NationalTeam() != null ? country.getU21NationalTeam().getId() : null);
        return dto;
    }
}
