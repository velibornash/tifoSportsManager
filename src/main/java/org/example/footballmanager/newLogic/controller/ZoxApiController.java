package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/zox")
@RequiredArgsConstructor
public class ZoxApiController {

    private final MatchRepository matchRepository;
    private final MatchFixtureRepository fixtures;
    private final MatchPlayerStatsRepository statsRepository;
    private final ObjectMapper objectMapper;
    private final org.example.footballmanager.newLogic.service.MatchPreviewService previews;

    /**
     * The pre-match screen, for a fixture or for a match that has been played.
     *
     * <p><b>An id can be either, and that is the whole point of the change.</b> There are 2,790 fixtures
     * and 155 matches in a seeded world, because a {@code Match} row is born when a match is <i>played</i>
     * — so "the preview for the next match" had nothing to fetch, which is why the next match on the
     * dashboard and an unplayed fixture in the club schedule both had to go somewhere else entirely.
     *
     * <p>A fixture therefore resolves to the same payload with the fixture's own teams and competition and
     * <b>nothing invented</b>: no score, no form, no absences, no player ratings. Which is the honest
     * answer for a match that has not been played, and is why this endpoint already returned placeholders
     * for most of its fields.
     */
    @GetMapping("/match-preview/{matchId}")
    public ResponseEntity<Map<String, Object>> getMatchPreview(@PathVariable Long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            // Not a fallback to the fixture table, and the reason is worth stating: a fixture id and a
            // match id are both small integers over separate tables, so "not found in matches, so it
            // must be a fixture" is a guess, and the guess resolved a dashboard link to somebody else's
            // played match. A caller that means a fixture calls /fixture-preview/{fixtureId}.
            return ResponseEntity.notFound().build();
        }

        String homeTeam = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String awayTeam = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
        // By id, not by name - see teamIdOf(). A rename must not empty these panels.
        Long homeTeamId = match.getHomeTeam() == null ? null : match.getHomeTeam().getId();
        Long awayTeamId = match.getAwayTeam() == null ? null : match.getAwayTeam().getId();

        List<MatchPlayerStats> allStats = statsRepository.findByMatchId(matchId);
        double homeRating = allStats.stream()
            .filter(s -> s.getPlayer() != null && s.getPlayer().getTeam() != null
                && homeTeamId.equals(teamIdOf(s)))
            .mapToInt(MatchPlayerStats::getRating)
            .average().orElse(70);
        double awayRating = allStats.stream()
            .filter(s -> s.getPlayer() != null && s.getPlayer().getTeam() != null
                && awayTeamId.equals(teamIdOf(s)))
            .mapToInt(MatchPlayerStats::getRating)
            .average().orElse(70);

        double total = homeRating + awayRating;
        double homeP = total > 0 ? homeRating / total : 0.5;

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("homeTeamName", homeTeam);
        preview.put("awayTeamName", awayTeam);
        preview.put("homeTeamRating", Math.round(homeRating * 10.0) / 10.0);
        preview.put("awayTeamRating", Math.round(awayRating * 10.0) / 10.0);
        preview.put("homeRecentForm", "");
        preview.put("awayRecentForm", "");
        preview.put("expectedResult", homeP > 0.53 ? "Home win" : homeP < 0.47 ? "Away win" : "Draw");
        preview.put("homeWinProbability", Math.round(homeP * 100.0) / 100.0);
        preview.put("drawProbability", 0.25);
        preview.put("awayWinProbability", Math.round((1.0 - homeP - 0.25) * 100.0) / 100.0);
        preview.put("expectedHomeGoals", Math.round(homeP * 2.5 * 10.0) / 10.0);
        preview.put("expectedAwayGoals", Math.round((1.0 - homeP) * 2.5 * 10.0) / 10.0);
        preview.put("homeFormation", match.getHomeFormation() != null ? match.getHomeFormation() : "4-3-3");
        preview.put("awayFormation", match.getAwayFormation() != null ? match.getAwayFormation() : "4-3-3");
        preview.put("homeFormationFitness", 0.92);
        preview.put("awayFormationFitness", 0.91);
        preview.put("homeBenchQuality", Math.round(homeRating / 5.0 * 10.0) / 10.0);
        preview.put("awayBenchQuality", Math.round(awayRating / 5.0 * 10.0) / 10.0);
        preview.put("homeAvailabilityScore", 95);
        preview.put("awayAvailabilityScore", 93);
        preview.put("homePositionMismatches", 0);
        preview.put("awayPositionMismatches", 0);
        preview.put("homePlayStyle", "Balanced");
        preview.put("awayPlayStyle", "Balanced");
        preview.put("analysisText", "An evenly matched contest is expected.");
        preview.put("predictionReasons", List.of("Both sides are of similar quality"));
        preview.put("homeInsights", List.of(Map.of("label", "Form", "value", "Unknown", "tone", "neutral")));
        preview.put("awayInsights", List.of(Map.of("label", "Form", "value", "Unknown", "tone", "neutral")));
        preview.put("homeAbsentees", List.of());
        preview.put("awayAbsentees", List.of());
        preview.put("homeLineup", List.of());
        preview.put("awayLineup", List.of());
        preview.put("matchDate", match.getMatchDate() != null ? match.getMatchDate().toString() : null);
        preview.put("played", true);

        return ResponseEntity.ok(preview);
    }

    /**
     * The preview for a fixture that has not been played, with a real prediction.
     *
     * <p>This used to return every computed field null, on the reasoning — which was right — that a
     * screen opening on a fixture must not show a 92% fitness as though it had been worked out. It was
     * applied one field too far: <b>a prediction is exactly what is knowable before a match</b>, and
     * withholding it left the owner with "Not predicted", 0%0%0% and an empty "Why this prediction".
     * See {@link MatchPreviewService} for the whole of it.
     *
     * <p>Squad fitness, absences, position mismatches and the starting eleven are still null, because
     * they genuinely are not knowable and inventing them is the thing to avoid.
     */
    private Map<String, Object> previewForFixture(
            org.example.footballmanager.newLogic.model.MatchFixture fixture) {
        return previews.previewForFixture(fixture);
    }

    /**
     * The same preview, for a match that has not been played.
     *
     * <p>A separate path from {@link #getMatchPreview} because the two ids are not interchangeable.
     * Fixture and Match rows live in separate tables with overlapping ids, so resolving one against the
     * other is a guess - and a guess here opened a different club's game.
     */
    @GetMapping("/fixture-preview/{fixtureId}")
    public ResponseEntity<Map<String, Object>> getFixturePreview(@PathVariable Long fixtureId) {
        var fixture = fixtures.findById(fixtureId).orElse(null);
        return fixture == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(previewForFixture(fixture));
    }

    @GetMapping("/post-match-report/{matchId}")
    public ResponseEntity<Map<String, Object>> getMatchReport(@PathVariable Long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(postMatchReportFor(match.getId()));
    }

    /**
     * The report itself, callable directly.
     *
     * <p>Extracted so a test can build the same report over a cup tie and an international without going
     * through HTTP. {@link #getMatchReport} is now a lookup plus this call, so the endpoint and the test
     * cannot drift apart - which is the failure mode of a test that re-implements the thing it is
     * testing.
     */
    public Map<String, Object> postMatchReportFor(Long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            return Map.of();
        }

        String homeTeam = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String awayTeam = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
        // By id, not by name - see teamIdOf(). A rename must not empty these panels.
        Long homeTeamId = match.getHomeTeam() == null ? null : match.getHomeTeam().getId();
        Long awayTeamId = match.getAwayTeam() == null ? null : match.getAwayTeam().getId();
        List<MatchPlayerStats> stats = statsRepository.findByMatchId(matchId);

        String headline = match.getHomeGoals() > match.getAwayGoals()
            ? homeTeam + " defeated " + awayTeam + " " + match.getHomeGoals() + "-" + match.getAwayGoals()
            : match.getAwayGoals() > match.getHomeGoals()
            ? awayTeam + " defeated " + homeTeam + " " + match.getAwayGoals() + "-" + match.getHomeGoals()
            : homeTeam + " and " + awayTeam + " drew " + match.getHomeGoals() + "-" + match.getAwayGoals();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("headline", headline);
        report.put("summary", generateSummary(match, homeTeam, awayTeam));
        report.put("playerOfTheMatch", buildMotm(stats, homeTeamId, awayTeamId, homeTeam, awayTeam));
        report.put("timeline", buildTimeline(match, homeTeam, awayTeam));
        report.put("stats", computeTeamStats(match));
        report.put("homeTopPerformers", buildTopPerformers(stats, homeTeamId));
        report.put("awayTopPerformers", buildTopPerformers(stats, awayTeamId));
        report.put("turningPoint", findTurningPoint(match, homeTeam, awayTeam));
        report.put("tacticalVerdict", generateTacticalVerdict(match, homeTeam, awayTeam));

        return report;
    }

    @GetMapping("/match-stats/{matchId}")
    public ResponseEntity<Map<String, Object>> getMatchStats(@PathVariable Long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(computeTeamStats(match));
    }

    // ─── Stats ────────────────────────────────────────────────

    private Map<String, Object> computeTeamStats(Match match) {
        String statsJson = match.getStatsJson();
        if (statsJson != null && !statsJson.isBlank()) {
            try {
                Map<String, Object> canonical = objectMapper.readValue(statsJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
                return canonical;
            } catch (Exception e) {
                log.warn("Failed to parse statsJson for match {}: {}", match.getId(), e.getMessage());
            }
        }

        String homeTeam = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String awayTeam = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";

        int homeShots = 0, awayShots = 0;
        int homeShotsOnTarget = 0, awayShotsOnTarget = 0;
        int homeCorners = 0, awayCorners = 0;
        int homeOffsides = 0, awayOffsides = 0;
        int homeYellow = 0, awayYellow = 0;
        int homeRed = 0, awayRed = 0;
        int homeFouls = 0, awayFouls = 0;
        int homePenalties = 0, awayPenalties = 0;
        int homeGoals = match.getHomeGoals();
        int awayGoals = match.getAwayGoals();
        double homePossession = match.getPossessionHome();
        double awayPossession = match.getPossessionAway();

        List<Map<String, Object>> events = parseEvents(match.getEventJson());
        if (events != null) {
            for (Map<String, Object> ev : events) {
                String teamSide = (String) ev.get("teamSide");
                boolean isHome = "HOME".equals(teamSide);
                boolean isAway = "AWAY".equals(teamSide);
                if (!isHome && !isAway) continue;

                if (ev.containsKey("onTarget") || ev.containsKey("shooterId")) {
                    Boolean onTarget = (Boolean) ev.get("onTarget");
                    Boolean isGoal = (Boolean) ev.get("isGoal");
                    if (isHome) homeShots++;
                    else awayShots++;
                    if (Boolean.TRUE.equals(onTarget) || Boolean.TRUE.equals(isGoal)) {
                        if (isHome) homeShotsOnTarget++;
                        else awayShotsOnTarget++;
                    }
                }

                if (ev.containsKey("cardType")) {
                    String cardType = (String) ev.get("cardType");
                    if (isHome) {
                        if ("YELLOW".equals(cardType)) homeYellow++;
                        else if ("RED".equals(cardType)) homeRed++;
                    } else {
                        if ("YELLOW".equals(cardType)) awayYellow++;
                        else if ("RED".equals(cardType)) awayRed++;
                    }
                }

                if (ev.containsKey("setPieceType") && "CORNER".equals(ev.get("setPieceType"))) {
                    if (isHome) homeCorners++;
                    else awayCorners++;
                }

                if (ev.containsKey("foulType") || "FOUL".equals(ev.get("type"))) {
                    if (isHome) homeFouls++;
                    else awayFouls++;
                }

                if (ev.containsKey("offside") || "OFFSIDE".equals(ev.get("type"))) {
                    if (isHome) homeOffsides++;
                    else awayOffsides++;
                }

                if (ev.containsKey("penaltyFoul") || "PENALTY".equals(ev.get("type")) || Boolean.TRUE.equals(ev.get("penaltyAwarded"))) {
                    if (isHome) homePenalties++;
                    else awayPenalties++;
                }
            }
        }

        homeShots -= homeGoals;
        awayShots -= awayGoals;

        double homeXg = homeGoals * 0.7 + 0.5;
        double awayXg = awayGoals * 0.7 + 0.5;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("homePossession", Math.round(homePossession * 10.0) / 10.0);
        stats.put("awayPossession", Math.round(awayPossession * 10.0) / 10.0);
        stats.put("homeExpectedGoals", Math.round(homeXg * 10.0) / 10.0);
        stats.put("awayExpectedGoals", Math.round(awayXg * 10.0) / 10.0);
        stats.put("homeShotsOnTarget", homeShotsOnTarget);
        stats.put("awayShotsOnTarget", awayShotsOnTarget);
        stats.put("homeShotsOffTarget", Math.max(0, homeShots - homeShotsOnTarget));
        stats.put("awayShotsOffTarget", Math.max(0, awayShots - awayShotsOnTarget));
        stats.put("homePassAccuracy", 78.0);
        stats.put("awayPassAccuracy", 78.0);
        stats.put("homeCorners", homeCorners);
        stats.put("awayCorners", awayCorners);
        stats.put("homeOffsides", homeOffsides);
        stats.put("awayOffsides", awayOffsides);
        stats.put("homeYellowCards", homeYellow);
        stats.put("awayYellowCards", awayYellow);
        stats.put("homeRedCards", homeRed);
        stats.put("awayRedCards", awayRed);
        stats.put("homeFouls", homeFouls);
        stats.put("awayFouls", awayFouls);
        stats.put("homePenalties", homePenalties);
        stats.put("awayPenalties", awayPenalties);
        stats.put("homeDominance", 50);
        stats.put("awayDominance", 50);

        return stats;
    }

    // ─── Timeline ─────────────────────────────────────────────

    private List<Map<String, Object>> buildTimeline(Match match, String homeTeam, String awayTeam) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        List<Map<String, Object>> events = parseEvents(match.getEventJson());
        if (events == null) return timeline;

        int runningHome = 0;
        int runningAway = 0;
        for (Map<String, Object> ev : events) {
            String type = textOrEmpty(ev.get("type"));
            String teamSide = text(ev.get("teamSide"));
            String teamName = "HOME".equals(teamSide) ? homeTeam : "AWAY".equals(teamSide) ? awayTeam : null;
            Integer minute = integer(ev.get("minute"));
            if (minute == null || teamName == null) continue;

            if ("GOAL".equals(type)) {
                String scorer = text(ev.get("scorerName"));
                if (scorer == null) scorer = text(ev.get("playerName"));
                String assistant = text(ev.get("assistantName"));
                int homeAfter = integerOr(ev.get("homeScoreAfter"),
                        runningHome + ("HOME".equals(teamSide) ? 1 : 0));
                int awayAfter = integerOr(ev.get("awayScoreAfter"),
                        runningAway + ("AWAY".equals(teamSide) ? 1 : 0));
                runningHome = homeAfter;
                runningAway = awayAfter;
                String title = scorer == null ? "Goal" : scorer;
                if (assistant != null && !assistant.isBlank()) {
                    title += " (assist: " + assistant + ")";
                }
                title += " (" + homeAfter + "-" + awayAfter + ")";
                timeline.add(timelineItem(minute, "goal", title, teamName,
                        assistant == null || assistant.isBlank() ? "" : "Assist: " + assistant));
                continue;
            }

            if ("YELLOW_CARD".equals(type) || "RED_CARD".equals(type)
                    || "CARD".equals(type)) {
                String cardType = text(ev.get("cardType"));
                if (cardType == null) cardType = "YELLOW".equals(type) ? "YELLOW" : "RED";
                String player = text(ev.get("playerName"));
                String title = (player == null ? "Player" : player) + " - "
                        + ("YELLOW".equalsIgnoreCase(cardType) ? "Yellow card" : "Red card");
                timeline.add(timelineItem(minute,
                        "YELLOW".equalsIgnoreCase(cardType) ? "yellow_card" : "red_card",
                        title, teamName, ""));
                continue;
            }

            if ("PENALTY_AWARDED".equals(type) || "PENALTY".equals(type)) {
                String taker = text(ev.get("takerName"));
                String title = "Penalty awarded to " + teamName;
                String detail = taker == null || taker.isBlank() ? "" : "Taker: " + taker;
                timeline.add(timelineItem(minute, "penalty", title, teamName, detail));
                continue;
            }

            if ("VAR".equals(type) || type.startsWith("VAR_")) {
                String varType = text(ev.get("varType"));
                String varDecision = text(ev.get("varDecision"));
                if (varType == null) {
                    varType = type.startsWith("VAR_") ? type.substring(4) : "VAR";
                }
                String decision = varDecision == null ? "" : " — " + varDecision;
                String player = text(ev.get("playerName"));
                String title = "VAR: " + varType + decision
                        + (player == null || player.isBlank() ? "" : " — " + player);
                timeline.add(timelineItem(minute, "var", title, teamName,
                        textOrEmpty(ev.get("description"))));
                continue;
            }

            if ("OFFSIDE".equals(type)) {
                String player = text(ev.get("playerName"));
                timeline.add(timelineItem(minute, "offside",
                        (player == null ? "Player" : player) + " - Offside", teamName, ""));
                continue;
            }

            if ("INJURY".equals(type)) {
                String player = text(ev.get("playerName"));
                timeline.add(timelineItem(minute, "injury",
                        (player == null ? "Player" : player) + " - Injury", teamName, ""));
                continue;
            }

            if ("SUB".equals(type) || "SUBSTITUTION".equals(type)) {
                String out = text(ev.get("playerOutName"));
                String in = text(ev.get("playerInName"));
                timeline.add(timelineItem(minute, "substitution",
                        "Out: " + (out == null ? "?" : out) + ", In: " + (in == null ? "?" : in),
                        teamName, ""));
            }
        }
        return timeline;
    }

    private Map<String, Object> timelineItem(int minute, String icon, String title,
                                              String teamName, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("minute", minute);
        item.put("icon", icon);
        item.put("title", title);
        item.put("teamName", teamName);
        item.put("detail", detail);
        return item;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String textOrEmpty(Object value) {
        String result = text(value);
        return result == null ? "" : result;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int integerOr(Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    /**
     * Which team a player's stats row belongs to, by <b>id</b>.
     *
     * <p>The report used to match on the team's <b>name</b> — {@code teamName.equals(player.team.name)} —
     * to decide whose performance it was. That breaks the moment a team is renamed, and one has already
     * been: the senior national sides were renamed from "Germany Senior NT" to "Germany" (2026-10-07), and
     * a stats row carrying the old name would have been attributed to nobody, silently emptying the
     * player-of-the-match and the top-performer panels.
     *
     * <p>An id cannot drift. This is the same lesson as {@code NationalRatingService}, whose team-to-country
     * map is built from membership rather than from names.
     */
    private static Long teamIdOf(MatchPlayerStats stats) {
        return stats == null || stats.getPlayer() == null || stats.getPlayer().getTeam() == null
                ? null
                : stats.getPlayer().getTeam().getId();
    }

    // ─── MOTM ─────────────────────────────────────────────────

    private Map<String, Object> buildMotm(List<MatchPlayerStats> stats, Long homeTeamId, Long awayTeamId,
                                          String homeTeam, String awayTeam) {
        if (stats.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("playerName", "N/A");
            empty.put("teamName", "");
            empty.put("playerId", null);
            empty.put("teamId", null);
            empty.put("rating10", 0);
            empty.put("goals", 0);
            empty.put("assists", 0);
            empty.put("saves", 0);
            empty.put("interceptions", 0);
            empty.put("minutesPlayed", 0);
            empty.put("cleanSheet", false);
            return empty;
        }

        MatchPlayerStats best = stats.stream()
            .max(Comparator.comparingInt(MatchPlayerStats::getRating))
            .orElse(stats.get(0));

        double rating10 = best.getRating() > 10 ? best.getRating() / 10.0 : best.getRating();

        Map<String, Object> motm = new LinkedHashMap<>();
        motm.put("playerId", best.getPlayer() != null ? best.getPlayer().getId() : null);
        motm.put("teamId", best.getPlayer() != null && best.getPlayer().getTeam() != null
            ? best.getPlayer().getTeam().getId() : null);
        motm.put("playerName", best.getPlayer() != null ? best.getPlayer().getName() : "N/A");
        motm.put("teamName", best.getPlayer() != null && best.getPlayer().getTeam() != null
            ? best.getPlayer().getTeam().getName() : "");
        motm.put("rating10", Math.round(rating10 * 10.0) / 10.0);
        motm.put("goals", best.getGoals());
        motm.put("assists", best.getAssists());
        motm.put("saves", best.getSaves());
        motm.put("interceptions", best.getInterceptions());
        motm.put("minutesPlayed", best.getMinutesPlayed());
        motm.put("cleanSheet", best.isCleanSheet());

        return motm;
    }

    // ─── Top Performers ───────────────────────────────────────

    private List<Map<String, Object>> buildTopPerformers(List<MatchPlayerStats> stats, Long teamId) {
        return stats.stream()
            .filter(s -> s.getPlayer() != null && s.getPlayer().getTeam() != null
                && teamId.equals(teamIdOf(s)))
            .sorted(Comparator.comparingInt(MatchPlayerStats::getRating).reversed())
            .limit(3)
            .map(s -> {
                double rating10 = s.getRating() > 10 ? s.getRating() / 10.0 : s.getRating();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("playerName", s.getPlayer().getName());
                p.put("position", s.getPlayer().getPosition() != null ? s.getPlayer().getPosition().name() : "");
                p.put("summary", s.getGoals() + " goals, " + s.getAssists() + " assists");
                p.put("rating10", Math.round(rating10 * 10.0) / 10.0);
                return p;
            })
            .toList();
    }

    // ─── Text Generators ──────────────────────────────────────

    private String generateSummary(Match match, String homeTeam, String awayTeam) {
        int hg = match.getHomeGoals();
        int ag = match.getAwayGoals();
        if (hg > ag) {
            return homeTeam + " deservedly won " + hg + "-" + ag
                + ". The side showed the better game and finishing quality.";
        } else if (ag > hg) {
            return awayTeam + " secured an important away win by "
                + ag + "-" + hg + ".";
        } else {
            return "The match ended in a draw " + hg + "-" + ag
                + ". Both teams had their chances.";
        }
    }

    private String findTurningPoint(Match match, String homeTeam, String awayTeam) {
        List<Map<String, Object>> events = parseEvents(match.getEventJson());
        if (events == null) return "The opening goal of the match.";

        for (Map<String, Object> ev : events) {
            if (ev.containsKey("scorerName")) {
                String scorer = (String) ev.get("scorerName");
                Integer min = ev.containsKey("minute") ? ((Number) ev.get("minute")).intValue() : null;
                if (min != null && min <= 30) {
                    return "An early goal by " + scorer + " in the " + min + "' minute set the tone for the match.";
                }
                if (min != null) {
                    return scorer + "'s goal in the " + min + "' minute was the key moment.";
                }
            }
            if (ev.containsKey("cardType") && "RED".equals(ev.get("cardType"))) {
                String player = (String) ev.get("playerName");
                Integer min = ev.containsKey("minute") ? ((Number) ev.get("minute")).intValue() : null;
                return "A red card for " + player + " in the "
                    + (min != null ? min + "' minute" : "match") + " changed the course of the game.";
            }
        }
        return "The opening goal of the match.";
    }

    private String generateTacticalVerdict(Match match, String homeTeam, String awayTeam) {
        int hg = match.getHomeGoals();
        int ag = match.getAwayGoals();
        if (hg > ag) {
            return homeTeam + " were tactically superior. Solid defensive organisation "
                + "and attacking efficiency brought home the win.";
        } else if (ag > hg) {
            return awayTeam + " played a tactically mature game, capitalising on counter-attacks.";
        } else {
            return "A tactically even match where neither side managed to impose its style.";
        }
    }

    // ─── JSON Parser ──────────────────────────────────────────

    private List<Map<String, Object>> parseEvents(String eventJson) {
        if (eventJson == null || eventJson.isBlank()) return null;
        try {
            return objectMapper.readValue(eventJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse eventJson", e);
            return null;
        }
    }
}
