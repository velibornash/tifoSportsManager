package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.junior.JuniorAcademyItemDTO;
import org.example.footballmanager.dto.junior.JuniorPromotionResultDTO;
import org.example.footballmanager.dto.junior.JuniorAcademyStateDTO;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.YouthAcademyService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/juniors")
@RequiredArgsConstructor
public class JuniorController {

    private final YouthAcademyService youthAcademyService;
    private final SeasonService seasonService;

    @GetMapping("/team/{teamId}")
    public JuniorAcademyStateDTO getTeamJuniors(@PathVariable Long teamId) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.getAcademyState(teamId, season, week);
    }

    @PostMapping("/{juniorId}/promote")
    public JuniorAcademyItemDTO promoteJunior(@PathVariable Long juniorId) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.promoteJunior(juniorId, season, week);
    }

    @PostMapping("/{juniorId}/promote-reveal")
    public JuniorPromotionResultDTO promoteJuniorReveal(@PathVariable Long juniorId) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.promoteJuniorWithReveal(juniorId, season, week);
    }

    @PostMapping("/{juniorId}/release")
    public JuniorAcademyItemDTO releaseJunior(@PathVariable Long juniorId) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.releaseJunior(juniorId, season, week);
    }

    @PostMapping("/{juniorId}/transfer-list")
    public JuniorAcademyItemDTO transferListJunior(@PathVariable Long juniorId) {
        int season = seasonService.getOrCreateClock().getCurrentSeason();
        int week = seasonService.getOrCreateClock().getCurrentWeek();
        return youthAcademyService.transferListJunior(juniorId, season, week);
    }
}
