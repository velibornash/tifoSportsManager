package org.example.footballmanager.newLogic.dto;

import lombok.Data;

@Data
public class CommunityPostRequestDTO {
    private String message;
    private Long recipientUserId;
}