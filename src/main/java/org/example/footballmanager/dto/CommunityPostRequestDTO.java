package org.example.footballmanager.dto;

import lombok.Data;

@Data
public class CommunityPostRequestDTO {
    private String message;
    private Long recipientUserId;
}