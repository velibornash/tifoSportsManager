package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZoxTimelineEventDTO {
    private Integer minute;
    private String type;
    private String icon;
    private String teamName;
    private String title;
    private String detail;
}
