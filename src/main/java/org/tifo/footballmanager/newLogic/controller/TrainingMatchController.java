package org.tifo.footballmanager.newLogic.controller;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.service.TrainingMatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The training match: play one, and say whether another may be played today.
 *
 * <p>Two endpoints and no state of their own — the limit lives in
 * {@link org.tifo.footballmanager.newLogic.service.TrainingMatchLimitCache} and the match itself is
 * never written anywhere. The account comes from the session, never from a parameter, so a manager
 * cannot play his daily match on somebody else's behalf.
 */
@RestController
@RequestMapping("/api/training-match")
@RequiredArgsConstructor
public class TrainingMatchController {

    private final TrainingMatchService trainingMatches;

    /** Whether this account may play now, and the band of strengths on offer. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trainingMatches.status(user));
    }

    /**
     * Plays ninety minutes against an opponent of the chosen strength.
     *
     * <p>{@code strength} defaults to 12 — the standard the world's top divisions sit around — so a
     * caller that sends nothing still gets a match rather than a 400.
     */
    @PostMapping("/play")
    public ResponseEntity<Map<String, Object>> play(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "12") int strength,
            @RequestParam(required = false) Long tacticId) {
        return ResponseEntity.ok(trainingMatches.play(user, strength, tacticId));
    }
}
