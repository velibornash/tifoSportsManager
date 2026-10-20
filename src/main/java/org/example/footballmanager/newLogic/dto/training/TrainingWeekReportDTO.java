package org.example.footballmanager.newLogic.dto.training;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TrainingWeekReportDTO {
    private Long teamId;
    private Integer seasonNumber;
    private Integer weekNumber;
    private List<PlayerTrainingReportDTO> players = new ArrayList<>();
}

