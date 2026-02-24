package org.example.footballmanager.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class DummyDataService {

    // --- FORMATIONS ---
    public List<FormationDto> getFormations() {
        List<FormationDto> list = new ArrayList<>();
        list.add(new FormationDto("4-4-2", "Classic two-striker formation with balanced midfield"));
        list.add(new FormationDto("4-3-3", "Attacking wingers and central midfield control"));
        list.add(new FormationDto("3-5-2", "Three at the back with strong midfield presence"));
        return list;
    }

    // --- COACHES ---
    public List<CoachDto> getCoaches() {
        List<CoachDto> list = new ArrayList<>();
        list.add(new CoachDto("John Smith", "Head Coach", 85));
        list.add(new CoachDto("Peter Johnson", "Assistant Coach", 78));
        list.add(new CoachDto("Alice Brown", "Fitness Coach", 80));
        return list;
    }

    // --- TRAINING REPORTS ---
    public List<TrainingReportDto> getTrainingReports() {
        List<TrainingReportDto> list = new ArrayList<>();
        list.add(new TrainingReportDto("Darko Živanov", "Great work on technique", "+2"));
        list.add(new TrainingReportDto("Žika Veljković", "Improved stamina", "+1"));
        list.add(new TrainingReportDto("Borislav Negovanović", "Good passing exercises", "+1"));
        return list;
    }

    // --- TEAM PROFILE ---
    public TeamProfileDto getTeamProfile() {
        return new TeamProfileDto("Omladinac FC", 1954, "Omladinac Stadium", 1200000, "High");
    }

    // --- LEAGUE TABLE ---
    public List<LeagueTableDto> getLeagueTable() {
        List<LeagueTableDto> list = new ArrayList<>();
        list.add(new LeagueTableDto("Omladinac FC", 45, 20));
        list.add(new LeagueTableDto("Rivals United", 42, 18));
        list.add(new LeagueTableDto("City Stars", 39, 12));
        list.add(new LeagueTableDto("Town FC", 36, 8));
        return list;
    }

    // --- FORUM ---
    public List<ForumPostDto> getForumPosts() {
        List<ForumPostDto> list = new ArrayList<>();
        list.add(new ForumPostDto("Admin", "Welcome to the forum!", LocalDate.now().minusDays(2)));
        list.add(new ForumPostDto("Fan123", "Matchday discussion", LocalDate.now().minusDays(1)));
        return list;
    }

    // --- EVENTS ---
    public List<EventDto> getEvents() {
        List<EventDto> list = new ArrayList<>();
        list.add(new EventDto("Charity Match", LocalDate.now().plusDays(5)));
        list.add(new EventDto("Fan Meetup", LocalDate.now().plusDays(10)));
        return list;
    }

    // --- ANALYTICS ---
    public AnalyticsDto getAnalytics() {
        return new AnalyticsDto(1.75, 1.10, 82, 4.5);
    }

    // --- TOP SCORERS ---
    public List<TopScorerDto> getTopScorers() {
        List<TopScorerDto> list = new ArrayList<>();
        list.add(new TopScorerDto("Darko Živanov", 12));
        list.add(new TopScorerDto("Žika Veljković", 10));
        list.add(new TopScorerDto("Borislav Negovanović", 8));
        return list;
    }

    // ==========================
    // DTO CLASSES
    // ==========================

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class FormationDto {
        private final String name;
        private final String description;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class CoachDto {
        private final String name;
        private final String role;
        private final int rating;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class TrainingReportDto {
        private final String playerName;
        private final String note;
        private final String improvement;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class TeamProfileDto {
        private final String name;
        private final int founded;
        private final String stadium;
        private final int budget;
        private final String reputation;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class LeagueTableDto {
        private final String name;
        private final int points;
        private final int goalDifference;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class ForumPostDto {
        private final String author;
        private final String title;
        private final LocalDate date;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class EventDto {
        private final String title;
        private final LocalDate date;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class AnalyticsDto {
        private final double xg;
        private final double xga;
        private final double pressing;
        private final double form;
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    public static class TopScorerDto {
        private final String name;
        private final int goals;
    }

}
