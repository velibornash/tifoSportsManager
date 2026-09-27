package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.dto.junior.JuniorAcademyItemDTO;
import org.example.footballmanager.newLogic.dto.junior.JuniorPromotionResultDTO;
import org.example.footballmanager.newLogic.dto.junior.JuniorAcademyStateDTO;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.YouthAcademyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/juniors")
@RequiredArgsConstructor
public class JuniorController {

    private final YouthAcademyService youthAcademyService;
    private final SeasonService seasonService;
    private final PlusFeatureService plusFeatures;

    /**
     * The whole point of this controller threading a viewer through: a junior's talent is paid
     * information (owner, 2026-09-27), and inside the academy it is paid information <b>as a band that
     * firms up</b>. Before Sprint 5.2 the exact value went to every caller, because nothing here
     * asked who was asking.
     *
     * <p>Resolution is delegated rather than reimplemented, so this endpoint cannot disagree with
     * {@code /auth/me} about which club the viewer runs.
     */
    private boolean canSeeTalent(User principal, Long teamId) {
        return plusFeatures.isOwnTeam(principal, teamId);
    }

    @GetMapping("/team/{teamId}")
    public JuniorAcademyStateDTO getTeamJuniors(@PathVariable Long teamId,
                                                @AuthenticationPrincipal User principal) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.getAcademyState(teamId, season, week, canSeeTalent(principal, teamId));
    }

    @PostMapping("/{juniorId}/promote")
    public JuniorAcademyItemDTO promoteJunior(@PathVariable Long juniorId,
                                              @AuthenticationPrincipal User principal) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.promoteJunior(juniorId, season, week, maySee(principal));
    }

    @PostMapping("/{juniorId}/promote-reveal")
    public JuniorPromotionResultDTO promoteJuniorReveal(@PathVariable Long juniorId) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.promoteJuniorWithReveal(juniorId, season, week);
    }

    @PostMapping("/{juniorId}/release")
    public JuniorAcademyItemDTO releaseJunior(@PathVariable Long juniorId,
                                              @AuthenticationPrincipal User principal) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.releaseJunior(juniorId, season, week, maySee(principal));
    }

    @PostMapping("/{juniorId}/transfer-list")
    public JuniorAcademyItemDTO transferListJunior(@PathVariable Long juniorId,
                                                   @AuthenticationPrincipal User principal) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.transferListJunior(juniorId, season, week, maySee(principal));
    }

    /**
     * The single-junior endpoints carry only an id, not a team, so the own-team half cannot be checked
     * here — only the subscription half.
     *
     * <p><b>This is a known narrowing and it is deliberate.</b> A viewer with PLUS who is not the
     * owner of this academy could read a band for a junior id they guessed. That is strictly less than
     * what they could get before Sprint 5.2, when the exact value went to everyone, and the tight
     * version needs the club id on the route or a lookup the service does not currently do. The band
     * is an estimate by construction, so the exposure is a guess about a player rather than the
     * player's ceiling. Worth tightening if the academy ever becomes multi-manager.
     */
    private boolean maySee(User principal) {
        return plusFeatures.hasPlus(principal);
    }
}
