package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.service.CanvasSimulationService;
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
