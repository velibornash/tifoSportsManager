package org.example.footballmanager.cleanSheet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSTactics {
    @Builder.Default
    private String formation = "4-4-2";
    @Builder.Default
    private CSPlayStyle style = CSPlayStyle.BALANCED;
}
