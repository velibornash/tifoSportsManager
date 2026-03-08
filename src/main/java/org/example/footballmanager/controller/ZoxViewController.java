package org.example.footballmanager.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller za ZOX Analytics HTML rute
 */
@Controller
@RequestMapping("/zox")
public class ZoxViewController {

    /**
     * Prikaži ZOX match preview stranicu
     */
    @GetMapping
    public String zoxDashboard() {
        return "redirect:/zox-match-preview.html";
    }

    /**
     * Prikaži ZOX match preview sa matchId parametrom
     */
    @GetMapping("/match-preview")
    public String matchPreview() {
        return "forward:/zox-match-preview.html";
    }
}
