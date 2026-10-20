package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.dto.scouting.ScoutAssignmentDTO;
import org.example.footballmanager.newLogic.dto.scouting.ScoutingNetworkDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.service.ScoutingService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The scouting network (Sprint 5, S5.1).
 *
 * <p>Mounted at {@code /scouting} rather than folded into {@code /juniors}. The two are separate
 * concerns that happen to share a sport: the academy is what a club produces, scouting is what it
 * looks at. Merging them would also have implied that a scouted prospect joins the academy, which is
 * exactly the thing the owner ruled out.
 */
@RestController
@RequestMapping("/scouting")
@RequiredArgsConstructor
public class ScoutingController {

    private final ScoutingService scoutingService;
    private final SeasonService seasonService;

    @GetMapping("/team/{teamId}")
    public ScoutingNetworkDTO getNetwork(@PathVariable Long teamId) {
        return scoutingService.networkFor(teamId, currentSeason());
    }

    @PostMapping("/team/{teamId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public ScoutAssignmentDTO assign(@PathVariable Long teamId,
                                     @RequestBody Map<String, Long> body) {
        Long scoutId = body.get("scoutId");
        Long countryId = body.get("countryId");
        if (scoutId == null || countryId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCOUTING_REQUEST_INCOMPLETE",
                    "Both scoutId and countryId are required to post a scout.");
        }
        return scoutingService.assignScout(teamId, scoutId, countryId, currentSeason());
    }

    @DeleteMapping("/team/{teamId}/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recall(@PathVariable Long teamId, @PathVariable Long assignmentId) {
        scoutingService.recallScout(teamId, assignmentId);
    }

    private int currentSeason() {
        return seasonService.getOrCreateClock().getCurrentSeason();
    }
}
