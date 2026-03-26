package org.example.footballmanager.zox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ZoxReplayFrameDTO {
    private long timestampMs;
    private int tick;
    private int minute;
    private List<PlayerPositionDTO> players;
    private BallPositionDTO ball;
    private Integer carrierPlayerId;
    private boolean ballInTransit;
    private Integer pendingReceiverId;
}
