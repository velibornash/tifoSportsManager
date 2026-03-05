package org.example.footballmanager.dto.training;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TrainingWeekSummaryDTO {
    private Integer seasonNumber;
    private Integer weekNumber;
    private LocalDateTime createdAt;
}

