package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.dto.junior.JuniorSchoolStateDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.service.JuniorSchoolService;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The junior school as a purchase (Sprint 5.3a).
 *
 * <p>Separate from {@link JuniorController} because it is a different kind of screen: that one is a
 * report on players, this one is a budget decision with two week windows on it. Mounted under
 * {@code /juniors/school} so the academy and the thing that funds it stay in the same family without
 * pretending to be the same feature.
 */
@RestController
@RequestMapping("/juniors/school")
@RequiredArgsConstructor
public class JuniorSchoolController {

    private final JuniorSchoolService juniorSchoolService;
    private final SeasonService seasonService;
    private final PlusFeatureService plusFeatures;

    /**
     * A club may only run <b>its own</b> junior school.
     *
     * <p>Without this any authenticated user could open a school on a rival's club, taking a fee out
     * of a budget they do not control, and then close it in week 12 to dump that club's entire intake
     * onto the transfer list. The id is a path variable and therefore guessable, so this is the check
     * that stops it — the same reasoning as the own-staff check in {@code ScoutingService}.
     *
     * <p>Reading a state is not restricted: a manager may look at any club's academy, which is
     * information rather than an action.
     */
    private void requireOwnClub(User principal, Long teamId) {
        if (!plusFeatures.isOwnTeam(principal, teamId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_OWN_CLUB",
                    "That is not your club.");
        }
    }

    @GetMapping("/team/{teamId}")
    public JuniorSchoolStateDTO getState(@PathVariable Long teamId) {
        return juniorSchoolService.stateFor(teamId, season(), week());
    }

    @PostMapping("/team/{teamId}/open")
    public JuniorSchoolStateDTO open(@PathVariable Long teamId, @AuthenticationPrincipal User principal) {
        requireOwnClub(principal, teamId);
        return juniorSchoolService.open(teamId, season(), week());
    }

    @PostMapping("/team/{teamId}/close")
    public Map<String, Object> close(@PathVariable Long teamId, @AuthenticationPrincipal User principal) {
        requireOwnClub(principal, teamId);
        int released = juniorSchoolService.close(teamId, season(), week());
        return Map.of(
                "released", released,
                "state", juniorSchoolService.stateFor(teamId, season(), week()));
    }

    private int season() {
        return seasonService.getOrCreateClock().getCurrentSeason();
    }

    private int week() {
        return seasonService.getOrCreateClock().getCurrentWeek();
    }
}
