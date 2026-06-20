package org.example.commonmanager.dto;

import lombok.Data;

@Data
public class JwtResponseDTO {
    private String token;

    public JwtResponseDTO(String token) {
        this.token = token;
    }

}