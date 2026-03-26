package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxPostMatchReportDTO {
    private Long matchId;
    private String headline;
    private String summary;
    private String turningPoint;
    private String tacticalVerdict;
    private ZoxTopPerformerDTO playerOfTheMatch;
    private List<ZoxTopPerformerDTO> homeTopPerformers;
    private List<ZoxTopPerformerDTO> awayTopPerformers;
    private List<ZoxTimelineEventDTO> timeline;
    private ZoxMatchStatsDTO stats;
}
