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
public class ZoxReplayBallPointDTO {
    private long timestampMs;
    private double x;
    private double y;
    private Integer carrierPlayerId;
    private boolean ballInTransit;
    private Integer pendingReceiverId;

    @JsonProperty("timestamp")
    public long getTimestampAlias() {
        return timestampMs;
    }

    @JsonProperty("position")
    public double[] getPositionAlias() {
        return new double[]{x, y, 0.0};
    }
}