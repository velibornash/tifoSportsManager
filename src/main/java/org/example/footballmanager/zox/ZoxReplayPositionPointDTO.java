package org.example.footballmanager.zox;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZoxReplayPositionPointDTO {
    private long timestampMs;
    private double x;
    private double y;
    private boolean visible;

    @JsonProperty("timestamp")
    public long getTimestampAlias() {
        return timestampMs;
    }

    @JsonProperty("position")
    public double[] getPositionAlias() {
        return new double[]{x, y, 0.0};
    }
}