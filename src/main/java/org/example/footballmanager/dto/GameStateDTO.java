package org.example.footballmanager.dto;

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
