package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSInternationalWindow {
    private int round;
    private int matchday;
    private String competitionName;
    @Builder.Default
    private List<CSMatchResult> results = new ArrayList<>();
    private String bulletin;
}
