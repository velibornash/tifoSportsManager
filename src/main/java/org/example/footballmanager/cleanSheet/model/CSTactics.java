package org.example.footballmanager.cleanSheet.model;

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
public class CSTactics {
    @Builder.Default
    private String formation = "4-4-2";
    @Builder.Default
    private CSPlayStyle style = CSPlayStyle.BALANCED;
    @Builder.Default
    private List<Long> starterIds = new ArrayList<>();
}
