package org.example.footballmanager.simulator;


import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.service.DemoMatchRuntime;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Slf4j
@Component
public class PlayerDecisionActions {
    private final PlayerMovementService playerMovementService;

    public PlayerDecisionActions(PlayerMovementService playerMovementService) {
        this.playerMovementService = playerMovementService;
    }

    // =============================================
    // ODLUKA NOSIOCA LOPTE
    // =============================================
    public PlayerPositionDTO chooseNextAction(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, DemoMatchRuntime rt) {
        boolean attacksRight = carrier.getTeam().equals("HOME");

        // Izračunaj distancu do gola
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(carrier.getX() - goalX);


        // Zona šuta - povećana na 32 jedinica + verovatnoća raste kako se približava
        if (distToGoal <= 32) {
            double shotProbability;

            if (distToGoal <= 18) {           // unutar ~18m → vrlo velika šansa
                shotProbability = 0.88;
            } else if (distToGoal <= 22) {    // <22 m → dobra šansa
                shotProbability = 0.75;
            } else if (distToGoal <= 27) {    // 22-27m → prosečna šansa
                shotProbability = 0.45;
            } else {                          // >27m → mala šansa (dugi šut)
                shotProbability = 0.18;
            }

            if (random.nextDouble() < shotProbability) {

                initiateShot(carrier, players, random, attacksRight, rt);
                return carrier;  // nosilac ostaje isti dok se izvrši šut
            }
        }

        // Ostalo isto: pas u prostor ili običan pas
        if (random.nextDouble() < 0.48) {
            PlayerPositionDTO target = trySpacePassTarget(carrier, players, random, attacksRight);
            if (target != null) return target;
        }

        return findNearbyTeammate(carrier, players);
    }
    // Obrada kretanja lopte tokom šuta
    public void handleShotMovement(Random random, DemoMatchRuntime rt) {
        rt.shotTicks++;
        double progress = (double) rt.shotTicks / rt.maxShotTicks;
        if (progress >= 1) {
            progress = 1;
        }

        // Interpoliraj poziciju lopte ka cilju
        rt.ball.setX(playerMovementService.clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(playerMovementService.clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.shotTicks >= rt.maxShotTicks) {
            rt.isShooting = false;
            // Odluči ishod šuta
            double shotOutcome = random.nextDouble();
            if (shotOutcome < 0.20) {
                // Gol: lopta prelazi gol-liniju
                // System.out.println("GOL!");
                rt.ball.setX(rt.targetBallX);
                rt.ball.setY(rt.targetBallY);
                initiateRebound(random, rt);

            } else if (shotOutcome < 0.70) {
                // Odbrana: pokreni odbijanje lopte
                //System.out.println("Odbijena lopta!");
                initiateRebound(random, rt);
            } else {
                // Promasaj: lopta ide izvan gola
                //System.out.println("Promasaj!");
                double missX = rt.attacksRightDuringShot ? 100 + 5 : -5;  // Izvan terena
                double missY = rt.targetBallY + (random.nextDouble() - 0.5) * 30;
                rt.ball.setX(playerMovementService.clamp(missX));
                rt.ball.setY(playerMovementService.clamp(missY));
                initiateRebound(random, rt);

            }
        }
    }
    private PlayerPositionDTO trySpacePassTarget(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, boolean attacksRight) {
        double spaceX = attacksRight ? 85 + random.nextDouble() * 12 : 15 - random.nextDouble() * 12;
        double spaceY = 20 + random.nextDouble() * 60;

        return players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY)))
                .orElse(null);
    }
    public void handleReboundMovement(List<PlayerPositionDTO> players, Random random, DemoMatchRuntime rt) {
        rt.reboundTicks++;
        double progress = (double) rt.reboundTicks / rt.maxReboundTicks;
        if (progress >= 1) {
            progress = 1;
        }

        // Interpoliraj poziciju lopte ka cilju odbijanja
        rt.ball.setX(playerMovementService.clamp(rt.ball.getX() + (rt.targetBallX - rt.ball.getX()) * progress));
        rt.ball.setY(playerMovementService.clamp(rt.ball.getY() + (rt.targetBallY - rt.ball.getY()) * progress));

        if (rt.reboundTicks >= rt.maxReboundTicks) {
            rt.isRebounding = false;
            // Lopta je slobodna, najbliži igrač (bilo koji tim) je uzima
            rt.currentCarrier = players.stream()
                    .min(Comparator.comparingDouble(p -> distance(rt.ball, p)))
                    .orElse(rt.currentCarrier);
        }
    }
    private void initiateRebound(Random random, DemoMatchRuntime rt) {
        // Odbijanje ka sredini terena, random pozicija blizu igrača broj 6 ili slučajna
        rt.targetBallX = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po X
        rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Oko sredine po Y, sa varijacijom

        rt.isRebounding = true;
        rt.reboundTicks = 0;
    }
    public void trySpacePass(PlayerPositionDTO carrier, List<PlayerPositionDTO> players, Random random, DemoMatchRuntime rt) {
        boolean attacksRight = carrier.getTeam().equals("HOME");
        double spaceX = attacksRight ? 85 + random.nextDouble() * 12 : 15 - random.nextDouble() * 12;
        double spaceY = 20 + random.nextDouble() * 60;

        PlayerPositionDTO receiver = players.stream()
                .filter(p -> p.getTeam().equals(carrier.getTeam()) && p.getId() != carrier.getId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.getX() - spaceX, p.getY() - spaceY)))
                .orElse(null);

        if (receiver != null) {
            rt.currentCarrier = receiver;
            rt.ball.setX(playerMovementService.clamp(spaceX));
            rt. ball.setY(playerMovementService.clamp(spaceY));
        }
    }
    private double distance(PlayerPositionDTO a, PlayerPositionDTO b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }
    private double distance(BallPositionDTO ball, PlayerPositionDTO player) {
        return Math.hypot(ball.getX() - player.getX(), ball.getY() - player.getY());
    }
    private void initiateShot(PlayerPositionDTO shooter, List<PlayerPositionDTO> players, Random random, boolean attacksRight, DemoMatchRuntime rt) {
        rt.attacksRightDuringShot = attacksRight;
        double goalX = attacksRight ? 100 : 0;
        double distToGoal = Math.abs(shooter.getX() - goalX);
        //System.out.println("Šut sa distance: " + String.format("%.1f", distToGoal));

        // Cilj ka koordinatama golmana odbrane
        PlayerPositionDTO opponentGk = players.stream()
                .filter(p -> p.getTeam().equals(attacksRight ? "AWAY" : "HOME") && isGoalkeeper(p))
                .findFirst()
                .orElse(null);

        if (opponentGk != null) {
            rt.targetBallX = opponentGk.getX();
            rt.targetBallY = opponentGk.getY();
        } else {
            rt.targetBallX = goalX;
            rt.targetBallY = 50 + (random.nextDouble() - 0.5) * 20;  // Fallback na centar gola
        }

        rt.isShooting = true;
        rt.shotTicks = 0;
    }
    private PlayerPositionDTO findNearbyTeammate(PlayerPositionDTO carrier, List<PlayerPositionDTO> players) {
        List<PlayerPositionDTO> candidates = players.stream()
                .filter(p -> p.getId() != carrier.getId() && p.getTeam().equals(carrier.getTeam()))
                .sorted(Comparator.comparingDouble(p -> distance(carrier, p)))
                .limit(4)
                .toList();

        if (candidates.isEmpty()) return null;
        return candidates.get(new Random().nextInt(candidates.size()));
    }
    private boolean isGoalkeeper(PlayerPositionDTO p) {
        return p.getId() == 1 || p.getId() == 12;  // Pretpostavka da su golmani ID 1 i 12
    }
}
