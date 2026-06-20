package org.example.basketballmanager.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbTeamDTO {
    private Long id;
    private String name;
    private String shortName;
    private String city;
    private String hall;
    private Integer hallCapacity;
    private String color;
    private Double averageOverall;
    private List<BbPlayerDTO> players;
}
