package org.example.commonmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiErrorResponseDTO {
    private int status;
    private String code;
    private String message;
    private String path;
    private LocalDateTime timestamp;
}
