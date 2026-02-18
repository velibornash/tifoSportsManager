package org.example.footballmanager.controller;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/demo")
public class DemoForUIController {

    // ==========================
    // TEAM
    // ==========================
    @GetMapping("/teams/1/formations")
    public List<FormationDto> getFormations() {
        List<FormationDto> list = new ArrayList<>();
        list.add(new FormationDto("4-4-2", "Classic two-striker formation with balanced midfield"));
        list.add(new FormationDto("4-3-3", "Attacking wingers and central midfield control"));
        list.add(new FormationDto("3-5-2", "Three at the back with strong midfield presence"));
        return list;
    }

    @GetMapping("/teams/1/coaches")
    public List<CoachDto> getCoaches() {
        List<CoachDto> list = new ArrayList<>();
        list.add(new CoachDto("John Smith", "Head Coach", 85));
        list.add(new CoachDto("Peter Johnson", "Assistant Coach", 78));
        list.add(new CoachDto("Alice Brown", "Fitness Coach", 80));
        return list;
    }

    @GetMapping("/teams/1/juniors")
    public List<JuniorDto> getJuniors() {
        List<JuniorDto> list = new ArrayList<>();
        list.add(new JuniorDto("John Smith", 85, 3.3));
        list.add(new JuniorDto("Peter Johnson",  78, 4.0));
        list.add(new JuniorDto("Alice Brown", 80, 3.8));
        return list;
    }

    @GetMapping("/trainings/1/reports")
    public List<TrainingReportDto> getTrainingReports() {
        List<TrainingReportDto> list = new ArrayList<>();
        list.add(new TrainingReportDto("Darko Živanov", "Great work on technique", "+2"));
        list.add(new TrainingReportDto("Žika Veljković", "Improved stamina", "+1"));
        list.add(new TrainingReportDto("Borislav Negovanović", "Good passing exercises", "+1"));
        return list;
    }

    @GetMapping("/teams/1/profile")
    public TeamProfileDto getTeamProfile() {
        return new TeamProfileDto("Omladinac FC", 1954, "Omladinac Stadium", 1200000, "High");
    }

    // ==========================
    // COMPETITIONS
    // ==========================
    @GetMapping("/leagues/1/table")
    public List<LeagueTableDto> getLeagueTable() {
        List<LeagueTableDto> list = new ArrayList<>();
        list.add(new LeagueTableDto("Omladinac FC", 45, 20));
        list.add(new LeagueTableDto("Rivals United", 42, 18));
        list.add(new LeagueTableDto("City Stars", 39, 12));
        list.add(new LeagueTableDto("Town FC", 36, 8));
        return list;
    }

    @GetMapping("/cups/1")
    public List<CupMatchDto> getCupMatches() {
        List<CupMatchDto> list = new ArrayList<>();
        list.add(new CupMatchDto("Omladinac FC", "Rivals United", 2, 1));
        list.add(new CupMatchDto("City Stars", "Town FC", 0, 0));
        return list;
    }

    @GetMapping("/internationals/1")
    public List<CupMatchDto> getInternationalMatches() {
        List<CupMatchDto> list = new ArrayList<>();
        list.add(new CupMatchDto("Omladinac FC", "International FC", 1, 1));
        return list;
    }

    @GetMapping("/matches/teams/1/upcoming")
    public List<CupMatchDto> getUpcoming() {
        List<CupMatchDto> list = new ArrayList<>();
        list.add(new CupMatchDto("Omladinac FC", "Partizan FC", 3, 2));
        return list;
    }

    @GetMapping("/matches/teams/1/fixtures")
    public List<CupMatchDto> getFixtures() {
        List<CupMatchDto> list = new ArrayList<>();
        list.add(new CupMatchDto("Omladinac FC", "Fixtures FC", 3, 2));
        return list;
    }

    @GetMapping("/matches/teams/1/friendlies")
    public List<CupMatchDto> getFriendlies() {
        List<CupMatchDto> list = new ArrayList<>();
        list.add(new CupMatchDto("Omladinac FC", "Friendly FC", 3, 2));
        return list;
    }

    // ==========================
    // COMMUNITY
    // ==========================
    @GetMapping("/forum/teams/1")
    public List<ForumPostDto> getForumPosts() {
        List<ForumPostDto> list = new ArrayList<>();
        list.add(new ForumPostDto("Admin", "Welcome to the forum!", LocalDate.now().minusDays(2)));
        list.add(new ForumPostDto("Fan123", "Matchday discussion", LocalDate.now().minusDays(1)));
        return list;
    }

    @GetMapping("/chat/teams/1")
    public List<ChatMessageDto> getChatMessages() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(new ChatMessageDto("Coach", "Training today at 18:00"));
        list.add(new ChatMessageDto("Darko Živanov", "Got it!"));
        return list;
    }

    @GetMapping("/events/teams/1")
    public List<EventDto> getEvents() {
        List<EventDto> list = new ArrayList<>();
        list.add(new EventDto("Charity Match", LocalDate.now().plusDays(5)));
        list.add(new EventDto("Fan Meetup", LocalDate.now().plusDays(10)));
        return list;
    }

    // ==========================
    // STATS
    // ==========================
    @GetMapping("/stats/teams/1")
    public TeamStatsDto getTeamStats() {
        return new TeamStatsDto(30, 12, 57, 15);
    }

    @GetMapping("/stats/leagues/1/topscorers")
    public List<TopScorerDto> getTopScorers() {
        List<TopScorerDto> list = new ArrayList<>();
        list.add(new TopScorerDto("Ljupče Ožegović", 11));
        list.add(new TopScorerDto("Žika Veljković", 10));
        list.add(new TopScorerDto("Borislav Negovanović", 8));
        return list;
    }

    @GetMapping("/stats/teams/1/players")
    public List<TopScorerDto> getPlayerStats() {
        List<TopScorerDto> list = new ArrayList<>();
        list.add(new TopScorerDto("Šumenki Dabić", 12));
        list.add(new TopScorerDto("Žika Veljković", 10));
        list.add(new TopScorerDto("Borislav Negovanović", 8));
        return list;
    }

    @GetMapping("/analytics/teams/1")
    public AnalyticsDto getAnalytics() {
        return new AnalyticsDto(1.75, 1.10, 82, 4.5);
    }

    // ==========================
    // DTO CLASSES
    // ==========================
    @Getter @Setter @RequiredArgsConstructor
    public static class FormationDto {
        private final String name;
        private final String description;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class CoachDto {
        private final String name;
        private final String role;
        private final int rating;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class TrainingReportDto {
        private final String playerName;
        private final String note;
        private final String improvement;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class TeamProfileDto {
        private final String name;
        private final int founded;
        private final String stadium;
        private final int budget;
        private final String reputation;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class LeagueTableDto {
        private final String name;
        private final int points;
        private final int goalDifference;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class CupMatchDto {
        private final String homeTeam;
        private final String awayTeam;
        private final int homeGoals;
        private final int awayGoals;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class ForumPostDto {
        private final String author;
        private final String title;
        private final LocalDate date;
    }

    @Getter @Setter 
    public static class ChatMessageDto {
        private final String user;
        private final String message;
        public ChatMessageDto(String user, String message) {
            this.user = user;
            this.message = message;
        }
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class EventDto {
        private final String title;
        private final LocalDate date;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class TeamStatsDto {
        private final int goals;
        private final int conceded;
        private final int possession;
        private final int shots;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class TopScorerDto {
        private final String name;
        private final int goals;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class AnalyticsDto {
        private final double xg;
        private final double xga;
        private final double pressing;
        private final double form;
    }

    @Getter @Setter @RequiredArgsConstructor
    public static class JuniorDto {
        private final String name;
        private final int age;
        private final double talent;
    }

}
