package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.*;
import org.example.footballtextmanager.state.CleanSheetGameState;

import java.util.*;

/**
 * Generates rich, varied inbox messages for different game events.
 * Creates immersive text-based manager experience with press conferences,
 * board meetings, fan reactions, scout reports, and more.
 */
public class CSInboxGenerator {

    private final Random rnd = new Random();

    // ==================== PRESS CONFERENCE ====================
    
    public String generatePreMatchPressConference(CleanSheetGameState state, CSFixture nextMatch) {
        CSTeam userTeam = state.getUserTeam();
        boolean isHome = Objects.equals(nextMatch.getHomeTeamId(), userTeam.getId());
        String opponent = isHome ? nextMatch.getAwayTeamName() : nextMatch.getHomeTeamName();
        Long opponentId = isHome ? nextMatch.getAwayTeamId() : nextMatch.getHomeTeamId();
        
        CSTableEntry opponentEntry = findTableEntry(state, opponentId);
        CSTableEntry userEntry = findTableEntry(state, userTeam.getId());
        
        StringBuilder sb = new StringBuilder();
        sb.append("PRE-MATCH PRESS CONFERENCE\n");
        sb.append("Round ").append(nextMatch.getRound()).append(" preview: ").append(userTeam.getName())
          .append(" vs ").append(opponent).append("\n\n");
        
        // Opening question about the opponent
        sb.append("Q: \"What do you make of ").append(opponent).append(" ahead of this fixture?\"\n");
        sb.append(generateOpponentAssessment(opponent, opponentEntry, userEntry)).append("\n\n");
        
        // Question about form/confidence
        sb.append("Q: \"How is the mood in the camp?\"\n");
        sb.append(generateMoodResponse(state)).append("\n\n");
        
        // Tactical question
        sb.append("Q: \"Any changes to the approach for this one?\"\n");
        sb.append(generateTacticalResponse(state, isHome)).append("\n\n");
        
        // Closing statement
        sb.append("Q: \"Final message for the supporters?\"\n");
        sb.append(generateFanMessage(state, isHome));
        
        return sb.toString();
    }
    
    public String generatePostMatchPressConference(CleanSheetGameState state, CSMatchResult result) {
        CSTeam userTeam = state.getUserTeam();
        boolean userHome = Objects.equals(result.getHomeTeamId(), userTeam.getId());
        int goalsFor = userHome ? result.getHomeGoals() : result.getAwayGoals();
        int goalsAgainst = userHome ? result.getAwayGoals() : result.getHomeGoals();
        String opponent = userHome ? result.getAwayTeamName() : result.getHomeTeamName();
        
        String resultType = goalsFor > goalsAgainst ? "WIN" : goalsFor < goalsAgainst ? "LOSS" : "DRAW";
        
        StringBuilder sb = new StringBuilder();
        sb.append("POST-MATCH PRESS CONFERENCE\n");
        sb.append(result.getSummary()).append("\n\n");
        
        // Opening reaction
        sb.append("Q: \"Your immediate reaction to that result?\"\n");
        sb.append(generateResultReaction(resultType, goalsFor, goalsAgainst, opponent)).append("\n\n");
        
        // Performance question
        sb.append("Q: \"How would you assess the performance?\"\n");
        sb.append(generatePerformanceAssessment(result, userTeam.getName(), resultType)).append("\n\n");
        
        // CPlayer question
        CSPlayerMatchStats motm = findManOfTheMatch(result);
        if (motm != null) {
            sb.append("Q: \"").append(motm.getPlayerName()).append(" caught the eye today?\"\n");
            sb.append(generatePlayerPraise(motm, resultType)).append("\n\n");
        }
        
        // Looking ahead
        sb.append("Q: \"What now for the team?\"\n");
        sb.append(generateLookingAhead(state, resultType));
        
        return sb.toString();
    }
    
    // ==================== BOARD MESSAGES ====================
    public String generateBoardMeeting(CleanSheetGameState state, String context) {
        CSTeam userTeam = state.getUserTeam();
        String teamName = userTeam != null ? userTeam.getName() : "The club";

        int totalTeams = state.getLeagueTable() == null ? 0 : state.getLeagueTable().size();
        int normalizedTotalTeams = Math.max(totalTeams, 1);
        CSTableEntry entry = userTeam == null ? null : findTableEntry(state, userTeam.getId());
        int position = entry == null ? normalizedTotalTeams : state.getLeagueTable().indexOf(entry) + 1;

        StringBuilder sb = new StringBuilder();
        sb.append("BOARD UPDATE\n");
        sb.append(teamName).append("\n\n");
        sb.append("Board confidence: ").append(getBoardConfidencePhrase(state)).append("\n");
        if (totalTeams > 0) {
            sb.append("League position: ").append(position).append("/").append(totalTeams).append("\n");
        }
        sb.append("Expectation: ")
                .append(getBoardExpectation(position, normalizedTotalTeams))
                .append("\n\n");
        // Add overall club mood if available
        CSClubMood mood = state.getClubMood();
        if (mood != null && mood.getMoodLabel() != null) {
            sb.append("Club overall: ").append(mood.getMoodLabel()).append("\n");
        }

        switch (context == null ? "general" : context) {
            case "mid_season" -> sb.append(generateMidSeasonAssessment(position, normalizedTotalTeams, entry));
            case "season_end" -> sb.append(generateSeasonEndAssessment(position, normalizedTotalTeams, entry, userTeam));
            case "good_run" -> sb.append(pick(
                    "The board is encouraged by the recent momentum. Keep standards high.",
                    "Recent results have strengthened belief in the project. Maintain this run.",
                    "The board appreciates the positive streak, but expects consistency to continue."
            ));
            case "poor_run" -> sb.append(pick(
                    "Recent form has raised concerns. An immediate response is required.",
                    "The board expects performances to improve quickly after this poor run.",
                    "Results have slipped below expectations. Stabilise the situation without delay."
            ));
            default -> sb.append("The board will continue to monitor progress against season objectives.");
        }

        return sb.toString();
    }

    
    // ==================== FAN REACTIONS ====================
    
    public String generateFanReaction(CleanSheetGameState state, CSMatchResult result, String resultType) {
        CSTeam userTeam = state.getUserTeam();
        
        StringBuilder sb = new StringBuilder();
        sb.append("FAN PULSE\n");
        sb.append("Supporter reactions from around the ground and social media\n\n");
        
        switch (resultType) {
            case "WIN" -> {
                int margin = Math.abs(result.getHomeGoals() - result.getAwayGoals());
                if (margin >= 3) {
                    sb.append(pick(
                        "🎉 \"Absolutely brilliant! This is what we came to see!\"",
                        "🔥 \"Demolition job! The lads were on fire today!\"",
                        "💪 \"Statement win. Let the league take notice!\""
                    )).append("\n\n");
                    sb.append("Atmosphere: Electric. Fans stayed long after the whistle.\n");
                    sb.append("Chant of the day: \"").append(generateWinChant(userTeam.getName())).append("\"");
                } else {
                    sb.append(pick(
                        "👏 \"Good result. Keep it going!\"",
                        "✅ \"Three points in the bag. Job done.\"",
                        "😊 \"Happy with that. Solid performance.\""
                    )).append("\n\n");
                    sb.append("Atmosphere: Positive. Fans left satisfied.\n");
                    sb.append("Chant of the day: \"").append(generateWinChant(userTeam.getName())).append("\"");
                }
            }
            case "DRAW" -> {
                sb.append(pick(
                    "😐 \"A point is a point, but we should have won that.\"",
                    "🤷 \"Mixed feelings. Could have been worse, could have been better.\"",
                    "😤 \"Frustrating. We had chances to win it.\""
                )).append("\n\n");
                sb.append("Atmosphere: Subdued. Some applause, some grumbles.\n");
                sb.append("Overheard: \"").append(pick(
                    "We need to be more clinical.",
                    "The ref didn't help us today.",
                    "At least we didn't lose."
                )).append("\"");
            }
            case "LOSS" -> {
                int margin = Math.abs(result.getHomeGoals() - result.getAwayGoals());
                if (margin >= 3) {
                    sb.append(pick(
                        "😡 \"Embarrassing. Absolutely embarrassing.\"",
                        "💔 \"I want my money back. That was painful.\"",
                        "😤 \"The manager needs to answer for this.\""
                    )).append("\n\n");
                    sb.append("Atmosphere: Hostile. Boos rang out at full-time.\n");
                    sb.append("Social media: Trending hashtag #").append(userTeam.getName().replace(" ", "")).append("Out");
                } else {
                    sb.append(pick(
                        "😞 \"Disappointing. We deserved more.\"",
                        "😔 \"Tough one to take. Heads up for next week.\"",
                        "😤 \"We can't keep dropping points like this.\""
                    )).append("\n\n");
                    sb.append("Atmosphere: Deflated. Quiet exit from the stands.\n");
                    sb.append("Fan forum: \"").append(pick(
                        "We need reinforcements.",
                        "The tactics aren't working.",
                        "Keep the faith, we'll bounce back."
                    )).append("\"");
                }
            }
        }
        
        return sb.toString();
    }
    
    // ==================== SCOUT REPORTS ====================
    
    public String generateScoutReport(CleanSheetGameState state) {
        // Pick a random player from another team
        List<CSPlayer> allPlayers = new ArrayList<>();
        for (Map.Entry<Long, List<CSPlayer>> entry : state.getAllTeamRosters().entrySet()) {
            if (!entry.getKey().equals(state.getUserTeam().getId())) {
                allPlayers.addAll(entry.getValue());
            }
        }
        
        if (allPlayers.isEmpty()) return null;
        
        CSPlayer target = allPlayers.get(rnd.nextInt(allPlayers.size()));
        String teamName = findTeamNameForPlayer(state, target.getId());
        
        StringBuilder sb = new StringBuilder();
        sb.append("SCOUT REPORT\n");
        sb.append("Confidential assessment\n\n");
        
        sb.append("CPlayer: ").append(target.getName()).append("\n");
        sb.append("Club: ").append(teamName).append("\n");
        sb.append("CSPosition: ").append(target.getPosition()).append(" | Age: ").append(target.getAge()).append("\n");
        sb.append("Rating: ").append(target.getRating()).append(" | Value: €").append(formatMoney(target.getValue())).append("\n\n");
        
        sb.append("Assessment:\n");
        sb.append(generatePlayerAssessment(target)).append("\n\n");
        
        sb.append("Key attributes:\n");
        sb.append(generateKeyAttributes(target)).append("\n\n");
        
        sb.append("Scout recommendation: ").append(generateScoutRecommendation(target, state.getUserTeam()));
        
        return sb.toString();
    }
    
    // ==================== YOUTH ACADEMY ====================
    
    public String generateYouthAcademyUpdate(CleanSheetGameState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("YOUTH ACADEMY REPORT\n");
        sb.append("Monthly update from the academy director\n\n");
        
        String[] prospects = {"Marko Petrović", "Luka Jovanović", "Stefan Nikolić", "Nemanja Ilić", "Vuk Pavlović"};
        String[] positions = {"GK", "DEF", "MID", "WNG", "ATT"};
        
        String prospect = prospects[rnd.nextInt(prospects.length)];
        String position = positions[rnd.nextInt(positions.length)];
        int age = 16 + rnd.nextInt(3);
        int potential = 65 + rnd.nextInt(25);
        
        sb.append("Prospect spotlight: ").append(prospect).append("\n");
        sb.append("CSPosition: ").append(position).append(" | Age: ").append(age).append("\n");
        sb.append("Potential rating: ").append(potential).append("\n\n");
        
        sb.append("Academy notes:\n");
        sb.append(pick(
            "\"" + prospect + " has been turning heads in training. Real talent.\"",
            "\"The U19s are developing well. " + prospect + " leads the way.\"",
            "\"We're excited about " + prospect + ". Could be ready for first team soon.\""
        )).append("\n\n");
        
        sb.append("Development focus: ").append(pick(
            "Technical skills and decision-making",
            "Physical conditioning and tactical awareness",
            "Mental strength and leadership qualities"
        )).append("\n");
        
        sb.append("Recommendation: ").append(pick(
            "Continue development in academy for now.",
            "Consider loan move for first-team experience.",
            "Ready for occasional first-team involvement."
        ));
        
        return sb.toString();
    }
    
    // ==================== INJURY UPDATES ====================
    
    public String generateInjuryUpdate(CSPlayer player, String severity) {
        StringBuilder sb = new StringBuilder();
        sb.append("MEDICAL DEPARTMENT UPDATE\n\n");
        
        sb.append("CPlayer: ").append(player.getName()).append("\n");
        sb.append("CSPosition: ").append(player.getPosition()).append("\n\n");
        
        switch (severity) {
            case "minor" -> {
                sb.append("Diagnosis: Minor knock\n");
                sb.append("Expected return: 1-2 matches\n\n");
                sb.append("Medical notes: \"").append(pick(
                    "Precautionary rest advised. Should be fine for the weekend.",
                    "Nothing serious. Light training for a few days.",
                    "Minor strain. We're being careful not to aggravate it."
                )).append("\"");
            }
            case "moderate" -> {
                sb.append("Diagnosis: Muscle injury\n");
                sb.append("Expected return: 3-5 matches\n\n");
                sb.append("Medical notes: \"").append(pick(
                    "Will need careful rehabilitation. No shortcuts.",
                    "Frustrating timing but we need to be patient.",
                    "The player is disappointed but focused on recovery."
                )).append("\"");
            }
            case "serious" -> {
                sb.append("Diagnosis: Significant injury\n");
                sb.append("Expected return: 8-12 matches\n\n");
                sb.append("Medical notes: \"").append(pick(
                    "A real blow for the squad. Long road ahead.",
                    "Surgery may be required. We're assessing options.",
                    "The player will need extensive rehabilitation."
                )).append("\"");
            }
        }
        
        return sb.toString();
    }
    
    // ==================== MEDIA HEADLINES ====================
    
    public String generateMediaHeadlines(CleanSheetGameState state, CSMatchResult result, String resultType) {
        CSTeam userTeam = state.getUserTeam();
        CSTableEntry entry = findTableEntry(state, userTeam.getId());
        int position = entry != null ? state.getLeagueTable().indexOf(entry) + 1 : 0;
        
        StringBuilder sb = new StringBuilder();
        sb.append("MEDIA ROUND-UP\n");
        sb.append("What the papers are saying\n\n");
        
        String[] papers = {"Sport Daily", "Football Weekly", "The Tribune", "Match Report", "Goal News"};
        
        for (int i = 0; i < 3; i++) {
            String paper = papers[rnd.nextInt(papers.length)];
            sb.append("📰 ").append(paper).append(":\n");
            sb.append("\"").append(generateHeadline(userTeam.getName(), result, resultType, position)).append("\"\n\n");
        }
        
        sb.append("Expert opinion:\n");
        sb.append(generateExpertOpinion(userTeam.getName(), resultType, position));
        
        return sb.toString();
    }
    
    // ==================== HELPER METHODS ====================
    
    private String generateOpponentAssessment(String opponent, CSTableEntry opponentEntry, CSTableEntry userEntry) {
        if (opponentEntry == null) {
            return pick(
                "\"They're a competitive side. We'll prepare properly and focus on ourselves.\"",
                "\"Every team in this league can hurt you. We respect them but fear no one.\"",
                "\"We've done our homework. We know what to expect.\""
            );
        }
        
        int opponentPos = opponentEntry.getPosition();
        int userPos = userEntry != null ? userEntry.getPosition() : 10;
        
        if (opponentPos <= 3) {
            return pick(
                "\"They're up there for a reason. Quality side. But we fancy our chances.\"",
                "\"Top of the table clash. These are the games you want to be involved in.\"",
                "\"They've been impressive, but we've got our own ambitions.\""
            );
        } else if (opponentPos > userPos + 5) {
            return pick(
                "\"We won't underestimate anyone. They'll be fighting for every point.\"",
                "\"Form goes out the window in these games. We need to be professional.\"",
                "\"Dangerous opponents. They've got nothing to lose.\""
            );
        } else {
            return pick(
                "\"Evenly matched on paper. It'll come down to who wants it more.\"",
                "\"Good side. Should be a competitive game.\"",
                "\"We know them well. It'll be tight.\""
            );
        }
    }
    
    private String generateMoodResponse(CleanSheetGameState state) {
        CSSeasonStats stats = state.getSeasonStats();
        if (stats == null) {
            return pick(
                "\"The lads are focused. Good energy in training.\"",
                "\"Positive atmosphere. Everyone's ready to go.\"",
                "\"We're in a good place mentally. Confident but not complacent.\""
            );
        }
        
        if (stats.getCurrentWinStreak() >= 3) {
            return pick(
                "\"Confidence is high. The boys are flying right now.\"",
                "\"Winning becomes a habit. We want to keep this run going.\"",
                "\"Brilliant atmosphere. Everyone believes.\""
            );
        } else if (stats.getCurrentLossStreak() >= 2) {
            return pick(
                "\"We're hurting, but we're together. Time to respond.\"",
                "\"Difficult period, but character will see us through.\"",
                "\"The players are determined to turn this around.\""
            );
        }
        
        return pick(
            "\"Steady. We're taking it one game at a time.\"",
            "\"Good focus in the group. Ready for the challenge.\"",
            "\"Professional mindset. We know what we need to do.\""
        );
    }
    
    private String generateTacticalResponse(CleanSheetGameState state, boolean isHome) {
        CSTactics tactics = state.getTactics();
        String formation = tactics != null ? tactics.getFormation() : "4-4-2";
        
        if (isHome) {
            return pick(
                "\"Home games, we want to impose ourselves. Expect us to be on the front foot.\"",
                "\"Our fans deserve to see us attack. We'll be positive.\"",
                "\"We've got a plan. The formation suits what we want to do.\""
            );
        } else {
            return pick(
                "\"Away from home, you need to be solid first. Then take your chances.\"",
                "\"We'll be organized and hit them on the break.\"",
                "\"Discipline will be key. We know how to get results on the road.\""
            );
        }
    }
    
    private String generateFanMessage(CleanSheetGameState state, boolean isHome) {
        if (isHome) {
            return pick(
                "\"Get behind us. When this place is rocking, we're hard to beat.\"",
                "\"We need the 12th man. Make some noise and we'll give you something to cheer about.\"",
                "\"The supporters have been brilliant. Let's give them a performance to remember.\""
            );
        } else {
            return pick(
                "\"To those making the trip - we appreciate every one of you. We'll fight for you.\"",
                "\"The away fans are always incredible. We won't let them down.\"",
                "\"Long journey for the supporters. We owe them a result.\""
            );
        }
    }
    
    private String generateResultReaction(String resultType, int goalsFor, int goalsAgainst, String opponent) {
        return switch (resultType) {
            case "WIN" -> {
                if (goalsFor - goalsAgainst >= 3) {
                    yield pick(
                        "\"Delighted. That's as good as we've played all season.\"",
                        "\"Comprehensive. The players executed the plan perfectly.\"",
                        "\"Outstanding performance. Days like this are why you do this job.\""
                    );
                }
                yield pick(
                    "\"Pleased with the three points. Hard-fought win.\"",
                    "\"Good result. The lads showed great character.\"",
                    "\"Happy. We did what we needed to do.\""
                );
            }
            case "DRAW" -> pick(
                "\"Mixed emotions. We had chances to win it.\"",
                "\"A point away from home, you take that. At home, it's frustrating.\"",
                "\"Fair result on balance. We'll take the positives.\""
            );
            case "LOSS" -> {
                if (goalsAgainst - goalsFor >= 3) {
                    yield pick(
                        "\"Difficult to explain. We were second best in every department.\"",
                        "\"Painful. I take full responsibility. Not good enough.\"",
                        "\"Humbling. We need to look at ourselves honestly.\""
                    );
                }
                yield pick(
                    "\"Disappointed. We didn't deserve to lose that.\"",
                    "\"Margins are fine at this level. Today they went against us.\"",
                    "\"Frustrating. We'll learn from it and move on.\""
                );
            }
            default -> "\"We'll analyze the game and prepare for the next one.\"";
        };
    }
    
    private String generatePerformanceAssessment(CSMatchResult result, String teamName, String resultType) {
        long shots = result.getEvents().stream()
            .filter(e -> e.getEventType() == CSEventType.SHOT_ON_TARGET || e.getEventType() == CSEventType.SHOT_OFF_TARGET)
            .filter(e -> teamName.equals(e.getTeamName()))
            .count();
        
        if (shots >= 15) {
            return pick(
                "\"We created plenty. The attacking play was excellent.\"",
                "\"Dominated in terms of chances. That's what we want to see.\"",
                "\"Positive football. We were on the front foot.\""
            );
        } else if (shots <= 5) {
            return pick(
                "\"We struggled to create. Need to be better in the final third.\"",
                "\"Not enough quality going forward. Work to do.\"",
                "\"Disappointing in attack. We'll address it in training.\""
            );
        }
        
        return pick(
            "\"Solid overall. Some good moments, some to improve.\"",
            "\"Competitive performance. We matched them in most areas.\"",
            "\"Professional display. Did what was needed.\""
        );
    }
    
    private String generatePlayerPraise(CSPlayerMatchStats player, String resultType) {
        if (player.getGoals() >= 2) {
            return pick(
                "\"" + player.getPlayerName() + " was outstanding. Match-winner.\"",
                "\"What a performance. " + player.getPlayerName() + " took his chances brilliantly.\"",
                "\"" + player.getPlayerName() + " showed his quality today. Top class.\""
            );
        } else if (player.getGoals() == 1) {
            return pick(
                "\"Crucial goal from " + player.getPlayerName() + ". That's what he's there for.\"",
                "\"" + player.getPlayerName() + " delivered when it mattered.\"",
                "\"Important contribution from " + player.getPlayerName() + ".\""
            );
        }
        
        return pick(
            "\"" + player.getPlayerName() + " was excellent. Controlled the game.\"",
            "\"Really pleased with " + player.getPlayerName() + "'s performance.\"",
            "\"" + player.getPlayerName() + " led by example today.\""
        );
    }
    
    private String generateLookingAhead(CleanSheetGameState state, String resultType) {
        return switch (resultType) {
            case "WIN" -> pick(
                "\"Enjoy tonight, then focus on the next one. No time to rest.\"",
                "\"We'll recover and prepare. Every game is a new challenge.\"",
                "\"Keep the momentum going. That's the aim now.\""
            );
            case "DRAW" -> pick(
                "\"Back to work. We need to turn draws into wins.\"",
                "\"Analyze, improve, go again. Simple as that.\"",
                "\"Next game is an opportunity to put things right.\""
            );
            case "LOSS" -> pick(
                "\"We'll respond. This group has character.\"",
                "\"Time to show what we're made of. Bounce back.\"",
                "\"Difficult moment, but we'll come through it together.\""
            );
            default -> "\"We prepare for the next game as always.\"";
        };
    }
    
    private String generateWinChant(String teamName) {
        String shortName = teamName.split(" ")[0];
        return pick(
            shortName + ", " + shortName + ", " + shortName + "!",
            "We are the champions, we are the champions!",
            "Ole, ole, ole, ole!",
            "Can't stop us now!",
            shortName + " till I die!"
        );
    }
    
    private String generatePlayerAssessment(CSPlayer player) {
        int rating = player.getRating();
        if (rating >= 75) {
            return pick(
                "Exceptional talent. Would improve any squad in the league.",
                "Top-tier player. Consistent performer at the highest level.",
                "Outstanding quality. A real difference-maker."
            );
        } else if (rating >= 65) {
            return pick(
                "Solid professional. Reliable contributor.",
                "Good player with room to develop further.",
                "Dependable. Would strengthen our options."
            );
        } else {
            return pick(
                "Decent squad player. Useful depth option.",
                "Workmanlike. Does a job without being spectacular.",
                "Limited ceiling but honest worker."
            );
        }
    }
    
    private String generateKeyAttributes(CSPlayer player) {
        List<String> strengths = new ArrayList<>();
        
        if (player.getPace() >= 14) strengths.add("Excellent pace");
        if (player.getTechnique() >= 14) strengths.add("Technical quality");
        if (player.getShooting() >= 14) strengths.add("Clinical finisher");
        if (player.getPassing() >= 14) strengths.add("Vision and passing");
        if (player.getDefending() >= 14) strengths.add("Defensive solidity");
        if (player.getPlaymaker() >= 14) strengths.add("Creative spark");
        if (player.getGoalkeeper() >= 14) strengths.add("Shot-stopping ability");
        
        if (strengths.isEmpty()) {
            strengths.add("Balanced skill set");
            strengths.add("Good work rate");
        }
        
        return "• " + String.join("\n• ", strengths.subList(0, Math.min(3, strengths.size())));
    }
    
    private String generateScoutRecommendation(CSPlayer player, CSTeam userTeam) {
        int rating = player.getRating();
        double value = player.getValue();
        double budget = userTeam.getBudget();
        
        if (value > budget * 0.8) {
            return "PASS - Outside our budget range.";
        } else if (rating >= 70) {
            return "PURSUE - Would be a significant addition.";
        } else if (rating >= 60) {
            return "MONITOR - Worth keeping an eye on.";
        } else {
            return "FILE - Not a priority target at this time.";
        }
    }
    
    private String generateHeadline(String teamName, CSMatchResult result, String resultType, int position) {
        String shortName = teamName.split(" ")[0];
        
        return switch (resultType) {
            case "WIN" -> pick(
                shortName + " MARCH ON WITH CONVINCING VICTORY",
                "BRILLIANT " + shortName.toUpperCase() + " CLAIM THREE POINTS",
                shortName.toUpperCase() + " TOO STRONG AS WINNING RUN CONTINUES",
                "DOMINANT DISPLAY SEES " + shortName.toUpperCase() + " TRIUMPH"
            );
            case "DRAW" -> pick(
                shortName.toUpperCase() + " HELD IN FRUSTRATING STALEMATE",
                "POINTS SHARED AS " + shortName.toUpperCase() + " LACK CUTTING EDGE",
                shortName.toUpperCase() + " SETTLE FOR DRAW DESPITE CHANCES",
                "HONORS EVEN IN TIGHT CONTEST"
            );
            case "LOSS" -> pick(
                shortName.toUpperCase() + " SUFFER SETBACK IN DEFEAT",
                "PRESSURE MOUNTS AS " + shortName.toUpperCase() + " FALL SHORT",
                "DISAPPOINTING " + shortName.toUpperCase() + " LOSE GROUND",
                shortName.toUpperCase() + " CRISIS DEEPENS AFTER LOSS"
            );
            default -> shortName.toUpperCase() + " IN ACTION";
        };
    }
    
    private String generateExpertOpinion(String teamName, String resultType, int position) {
        String shortName = teamName.split(" ")[0];
        
        return switch (resultType) {
            case "WIN" -> pick(
                "\"" + shortName + " are building something. Watch this space.\" - Former international",
                "\"Impressive stuff. They're a well-organized unit.\" - TV pundit",
                "\"The manager deserves credit. Clear identity and belief.\" - Ex-pro"
            );
            case "DRAW" -> pick(
                "\"" + shortName + " need to find that killer instinct.\" - Former international",
                "\"Good team, but draws won't get you promoted.\" - TV pundit",
                "\"They're close, but close isn't enough.\" - Ex-pro"
            );
            case "LOSS" -> pick(
                "\"Questions need to be asked at " + shortName + ".\" - Former international",
                "\"Worrying signs. The pressure is building.\" - TV pundit",
                "\"They need to stop the rot quickly.\" - Ex-pro"
            );
            default -> "\"Interesting times at " + shortName + ".\" - Analyst";
        };
    }
    
    private String getBoardConfidencePhrase(CleanSheetGameState state) {
        CSClubMood mood = state.getClubMood();
        if (mood == null) return "Stable";
        return mood.getBoardConfidenceLabel();
    }
    
    private String getBoardExpectation(int position, int totalTeams) {
        if (position <= 2) return "Maintain title challenge";
        if (position <= totalTeams / 3) return "Push for promotion places";
        if (position <= totalTeams * 2 / 3) return "Consolidate mid-table";
        return "Avoid relegation battle";
    }
    
    private String generateMidSeasonAssessment(int position, int totalTeams, CSTableEntry entry) {
        if (position <= 2) {
            return pick(
                "\"Excellent first half of the season. Keep pushing for the title.\"",
                "\"The board is delighted with progress. Maintain this level.\"",
                "\"You've exceeded expectations. Now finish the job.\""
            );
        } else if (position <= totalTeams / 3) {
            return pick(
                "\"Solid campaign so far. The promotion places are within reach.\"",
                "\"Good position. A strong second half could see us challenge.\"",
                "\"Encouraging. Keep building momentum.\""
            );
        } else if (position <= totalTeams * 2 / 3) {
            return pick(
                "\"Acceptable, but we expected more. Push on in the second half.\"",
                "\"Mid-table is not where we want to be. Improvement needed.\"",
                "\"Steady, but the board wants to see ambition.\""
            );
        } else {
            return pick(
                "\"Concerning position. Survival is the priority now.\"",
                "\"The board is worried. Results must improve immediately.\"",
                "\"Unacceptable. Major improvement required or changes will be made.\""
            );
        }
    }
    
    private String generateSeasonEndAssessment(int position, int totalTeams, CSTableEntry entry, CSTeam team) {
        if (position == 1) {
            return "CHAMPIONS! The board congratulates you on an outstanding achievement. " +
                   "Contract extension discussions will follow. Budget increase approved for next season.";
        } else if (position == 2) {
            return "Promotion secured! Excellent season. The board is pleased and will back you " +
                   "in the transfer market for the step up.";
        } else if (position <= 4) {
            return "Playoff position achieved. A successful season overall. " +
                   "The board supports continued development of the project.";
        } else if (position <= totalTeams / 2) {
            return "Respectable finish. The board sees potential but expects improvement next season. " +
                   "Modest budget maintained.";
        } else if (position <= totalTeams - 2) {
            return "Disappointing season. The board expected better. " +
                   "Your position will be reviewed over the summer.";
        } else {
            return "Relegation. A disastrous campaign. The board will conduct a full review. " +
                   "Your future at the club is uncertain.";
        }
    }
    
    private CSTableEntry findTableEntry(CleanSheetGameState state, Long teamId) {
        return state.getLeagueTable().stream()
            .filter(e -> Objects.equals(e.getTeamId(), teamId))
            .findFirst()
            .orElse(null);
    }
    
    private CSPlayerMatchStats findManOfTheMatch(CSMatchResult result) {
        List<CSPlayerMatchStats> all = new ArrayList<>();
        if (result.getHomePlayerStats() != null) all.addAll(result.getHomePlayerStats());
        if (result.getAwayPlayerStats() != null) all.addAll(result.getAwayPlayerStats());
        
        return all.stream()
            .max(Comparator.comparingDouble(CSPlayerMatchStats::getRating)
                .thenComparingInt(CSPlayerMatchStats::getGoals)
                .thenComparingInt(CSPlayerMatchStats::getAssists))
            .orElse(null);
    }
    
    private String findTeamNameForPlayer(CleanSheetGameState state, Long playerId) {
        for (var entry : state.getAllTeamRosters().entrySet()) {
            for (CSPlayer p : entry.getValue()) {
                if (p.getId().equals(playerId)) {
                    return state.getAllTeams().stream()
                        .filter(t -> t.getId().equals(entry.getKey()))
                        .map(CSTeam::getName)
                        .findFirst().orElse("?");
                }
            }
        }
        return "?";
    }
    
    private String formatMoney(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }
    
    private String ordinal(int n) {
        if (n <= 0) return "?";
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
    
    private String pick(String... options) {
        if (options == null || options.length == 0) return "";
        return options[rnd.nextInt(options.length)];
    }

    /**
     * Generiše derby post-match fanfare.
     */
    public String generatePostMatchDerbyBanter(CSMatchResult result, String resultType) {
        StringBuilder sb = new StringBuilder();
        sb.append("DERBY AFTERMATH\n");
        sb.append("The city is still buzzing after that result!\n\n");
        
        if (resultType.equals("WIN")) {
            sb.append(pick(
                "The bragging rights are ours! Our fans are still singing in the streets.",
                "Best feeling ever! That will be remembered for years.",
                "They'll be hurting in their part of town tonight. Sweet!"
            ));
        } else if (resultType.equals("LOSS")) {
            sb.append(pick(
                "A dark day for the club. The derby curse continues.",
                "The usual problems: we always find a way to lose this one.",
                "The players will hear about this from the supporters for weeks."
            ));
        } else {
            sb.append("A draw was probably fair, but both sets of fans wanted more.");
        }
        
        sb.append("\n\nSocial media was on fire:");
        sb.append(pick(
            "\"We smashed them!\" – @ultras_Group",
            "VAR once again favoured them. Disgusting. – @TrueFan",
            "My voice is gone from shouting. Worth it! – @SeasonTicketHolder"
        ));
        
        return sb.toString();
    }
}
