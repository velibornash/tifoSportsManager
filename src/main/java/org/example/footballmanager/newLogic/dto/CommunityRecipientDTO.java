package org.example.footballmanager.newLogic.dto;

public record CommunityRecipientDTO(
        Long userId,
        String username,
        Long teamId,
        String teamName,
        String role
) {
}