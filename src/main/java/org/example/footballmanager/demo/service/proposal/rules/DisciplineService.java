package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.engine.EngineInterfaces;
import org.example.footballmanager.demo.service.proposal.model.*;

/**
 * Discipline service — foul detection, card issuance, VAR integration.
 *
 * Modular design: each rule type (tackle foul, push, dangerous play,
 * second-yellow, straight-red, penalty-box foul) is a separate private
 * method so new rules can be added without touching existing ones.
 *
 * Placeholder — all methods return safe defaults.  Logic per backlog
 * (PROPOSAL_PROGRESS.md §8.6).
 */
public class DisciplineService implements EngineInterfaces.DisciplineService {

    private final MatchState state;
    private final VARService varService;

    public DisciplineService(MatchState state, VARService varService) {
        this.state = state;
        this.varService = varService;
    }

    @Override
    public DisciplineResult evaluateFoul(MatchState state) {
        // TODO per backlog (§8.6):
        // 1. Detect tackle foul (defender wins duel in tackle context)
        // 2. Check foul severity (normal / reckless / violent / professional)
        // 3. Issue yellow card if second-yellow candidate
        // 4. Issue straight red if last-man or violent conduct
        // 5. Award penalty if foul inside penalty area
        // 6. Award indirect free-kick if foul outside penalty area
        // 7. Trigger VAR review for penalty-area fouls and red cards
        // 8. Record foul + card stats per player/team
        return new DisciplineResult(false, false, false, false, false, "");
    }

    // --- Individual rule methods (add rules here) ---

    /** Tackle from behind — automatic yellow unless last-man (red). */
    private boolean isTackleFromBehind(Player defender, Player attacker) {
        // TODO: check relative positions, tackle direction
        return false;
    }

    /** Dangerous play without contact — caution (yellow). */
    private boolean isDangerousPlay(Player defender, Player attacker) {
        // TODO: e.g. high boot, studs-up, late challenge
        return false;
    }

    /** Professional foul — last-man stopping clear goal-scoring opportunity = red. */
    private boolean isProfessionalFoul(Player defender, Player attacker) {
        // TODO: check if attacker was through on goal
        return false;
    }

    /** Inside penalty area → penalty instead of direct FK. */
    private boolean isInsidePenaltyArea(Position foulPosition, boolean homeAttacking) {
        // TODO: use pitch geometry (HOME penalty area rows 1-1.5, cols 2-6)
        return false;
    }
}
