package org.example.footballmanager.newLogic.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiErrorResponseDTO {
    private int status;
    private String code;
    private String message;
    private String path;
    private LocalDateTime timestamp;

    public ApiErrorResponseDTO(int status, String code, String message, String path, LocalDateTime timestamp) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.path = path;
        this.timestamp = timestamp;
    }
}