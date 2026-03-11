package org.example.footballmanager.dto;

import java.time.LocalDateTime;

public record CommunityChatMessageDTO(
        Long id,
        String author,
        String message,
        String type,
        LocalDateTime date,
        Long teamId,
        String teamName,
        Long registrationRequestId,
        String registrationStatus,
        String requestedUsername,
        String requestedEmail,
        Long requestedTeamId,
        String requestedTeamName,
        String reviewerUsername,
        String reviewNote,
        boolean canApprove,
        boolean canReject
) {
}