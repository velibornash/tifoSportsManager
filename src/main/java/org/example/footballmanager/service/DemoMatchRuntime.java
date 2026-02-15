package org.example.footballmanager.service;

import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;

import java.util.*;

public class DemoMatchRuntime {

    // --- STATE JEDNOG MEČA ---
    public PlayerPositionDTO currentCarrier;
    public BallPositionDTO ball;

    public int possessionTicks = 0;
    public int spacePassCooldown = 0;

    public boolean isShooting = false;
    public boolean isRebounding = false;

    public double targetBallX;
    public double targetBallY;

    public int shotTicks = 0;
    public int reboundTicks = 0;
    public int maxReboundTicks;
    public int maxShotTicks = 0;
    public boolean attacksRightDuringShot;

    public Map<Integer, Integer> offsideStreak = new HashMap<>();

    public List<PlayerPositionDTO> players = new ArrayList<>();

    // tick counter
    public int tick = 0;
}
