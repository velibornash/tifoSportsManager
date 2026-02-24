package org.example.footballmanager.old.oldController;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.old.oldService.CanvasSimulationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CanvasTestController {

    private final CanvasSimulationService simulationService;

    @GetMapping("/start-canvas-test")
    public String start() {
        simulationService.startCanvasTestSimulation();
        return "Canvas simulation started!";
    }
}
