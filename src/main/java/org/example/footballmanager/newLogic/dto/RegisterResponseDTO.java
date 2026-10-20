package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponseDTO {
    private String status;
    private String message;
    private Long reservedTeamId;
    private String reservedTeamName;
}