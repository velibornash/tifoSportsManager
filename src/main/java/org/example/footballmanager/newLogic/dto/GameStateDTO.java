package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class GameStateDTO {
    private int second;
    private List<PlayerPositionDTO> players;
    private BallPositionDTO ball;
}
