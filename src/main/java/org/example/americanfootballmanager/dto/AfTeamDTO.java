package org.example.americanfootballmanager.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfTeamDTO {
    private Long id;
    private String name;
    private String shortName;
    private String city;
    private String stadium;
    private Integer stadiumCapacity;
    private String color;
    private Double averageOverall;
    private List<AfPlayerDTO> players;
}
