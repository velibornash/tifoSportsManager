package org.tifo.footballtextmanager.engine;

import org.tifo.footballtextmanager.model.*;
import org.tifo.footballtextmanager.state.CleanSheetGameState;

import java.util.*;

/**
 * Generates rich, varied inbox messages for different game events.
 * Creates immersive text-based manager experience with press conferences,
 * board meetings, fan reactions, scout reports, and more.
 */
public class CSInboxGenerator {

    private final Random rnd;

    /**
     * Seedable constructor.
     *
     * <p>This generator used to own a private {@code new Random()}, which made every word it wrote
     * untestable: a test could not assert that a specific question was asked for a specific result,
     * because the paper the reporter happened to come from was a coin toss. Injecting the source
     * means the tests can pin the seed and assert on the copy itself.
     */
    public CSInboxGenerator() {
        this(new Random());
    }

    public CSInboxGenerator(Random rnd) {
        this.rnd = rnd;
    }

    // ==================== PRESS CONFERENCE ====================
    
    /**
     * The pre-match press conference.
     *
     * <p>The useful part of this is the opposition question, and that is exactly the part that used
     * to be worthless: it picked a sentiment from a pool based on whether the opponent sat in the
     * top three, and said the same thing about every club in the same half of the table.
     *
     * <p>Now it reads what the opponent will actually do. {@link CSOpponentProfile} derives each
     * club's shape and style deterministically, so a manager who has watched them can check the
     * press against what they saw — and one who has not gets a genuine scouting brief for free.
     */
    public String generatePreMatchPressConference(CleanSheetGameState state, CSFixture nextMatch) {
        CSTeam userTeam = state.getUserTeam();
        boolean isHome = Objects.equals(nextMatch.getHomeTeamId(), userTeam.getId());
        String opponent = isHome ? nextMatch.getAwayTeamName() : nextMatch.getHomeTeamName();
        Long opponentId = isHome ? nextMatch.getAwayTeamId() : nextMatch.getHomeTeamId();

        CSTableEntry opponentEntry = findTableEntry(state, opponentId);
        CSTableEntry userEntry = findTableEntry(state, userTeam.getId());

        CSTeam opponentTeam = state.getAllTeams().stream()
                .filter(t -> t.getId().equals(opponentId))
                .findFirst()
                .orElse(null);
        List<CSPlayer> opponentRoster = state.getAllTeamRosters().getOrDefault(opponentId, List.of());
        String theirShape = opponentTeam == null ? "unknown" : CSOpponentProfile.describe(opponentTeam, opponentRoster);

        StringBuilder sb = new StringBuilder();
        sb.append("PRE-MATCH PRESS CONFERENCE\n");
        sb.append("Round ").append(nextMatch.getRound()).append(" preview: ").append(userTeam.getName())
                .append(isHome ? " vs " : " at ").append(opponent).append("\n");
        sb.append("Scouting: ").append(opponent).append(" play ").append(theirShape).append("\n\n");

        sb.append(q("What do you make of " + opponent + " this week?",
                opponentAssessment(factsAbout(state, opponentEntry, userEntry), opponent, opponentId)));

        sb.append(q("What have they been doing?", recordLine(opponentEntry)));

        sb.append(q("How is the mood in the camp?", generateMoodResponse(state)));

        sb.append(q("Any changes to the approach for this one?", generateTacticalResponse(state, isHome)));

        sb.append(q("Final message for the supporters?", generateFanMessage(state, isHome)));

        return sb.toString();
    }

    private String factsAbout(CleanSheetGameState state, CSTableEntry opponentEntry, CSTableEntry userEntry) {
        if (opponentEntry == null) return "unknown";
        return opponentEntry.getPosition() <= 3 ? "top"
                : opponentEntry.getPosition() <= 7 ? "upper" : "lower";
    }

    /**
     * Plain record, no adjectives. "Six points from six" tells a manager something; "they are a
     * competitive side" does not, and read identically to every club in the same band.
     */
    private String recordLine(CSTableEntry entry) {
        if (entry == null) return "\"There is no record on file for them, which is its own kind of information.\"";
        String q = entry.getPlayed() == 0 ? "yet to play" : entry.getPlayed() + " played";
        return "\"They are " + entry.getPosition() + ordinalSuffix(entry.getPosition()) + " on "
                + entry.getPoints() + " points from " + q
                + " — " + entry.getWins() + " won, " + entry.getDraws() + " drawn, "
                + entry.getLosses() + " lost, " + (entry.getGoalsScored() - entry.getGoalsConceded() >= 0 ? "+" : "")
                + (entry.getGoalsScored() - entry.getGoalsConceded()) + " on goal difference.\"";
    }

    private static String ordinalSuffix(int position) {
        int mod100 = position % 100;
        if (mod100 >= 11 && mod100 <= 13) return "th";
        return switch (position % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private String opponentAssessment(String band, String opponent, Long opponentId) {
        return switch (band) {
            case "top" -> "\"They are a genuinely strong side and this is the hardest fixture we could "
                    + "have been handed. They have been winning, so there is no argument about their "
                    + "quality. We go in with a plan and we go in together.\"";
            case "upper" -> "\"" + opponent + " are in the better half of this table. That is a test, and it "
                    + "is one we should be measuring ourselves against.\"";
            case "lower" -> "\"" + opponent + " have not set the league alight, but neither have we. I would "
                    + "rather have the game in front of us than be asked what it means.\"";
            default -> "\"" + opponent + " are difficult to read and I will not pretend otherwise. We will "
                    + "find out about them at half past seven.\"";
        };
    }

    /**
     * The press conference, written from what actually happened.
     *
     * <p>Three changes against the version this replaces, all of them about grounding:
     *
     * <ul>
     *   <li>the header states the scoreline, the scorers and the venue, so the piece can be
     *       recognised as this game and not as a mood;</li>
     *   <li>the questions are chosen by the <b>shape</b> of the result — you are asked about the
     *       collapse you threw away, or about the goal you had to chase, or about the clean sheet,
     *       and only sometimes the generic "how do you assess the performance";</li>
     *   <li>the answers quote real numbers (goals, shots, cards, the man of the match) rather than
     *       picking a sentiment at random.</li>
     * </ul>
     *
     * <p>A manager reading this should be able to tell which match it was about without the
     * header. That is the test applied to everything this generator writes.
     */
    public String generatePostMatchPressConference(CleanSheetGameState state, CSMatchResult result) {
        CSTeam userTeam = state.getUserTeam();
        CSMatchFacts facts = CSMatchFacts.from(result, userTeam.getName(), userTeam.getId());
        if (facts == null) return "POST-MATCH PRESS CONFERENCE\n\nNo report available.";

        CSMatchFacts.Shape shape = facts.shape();
        CSMatchFacts.Standout standout = facts.standout();

        StringBuilder sb = new StringBuilder();
        sb.append("POST-MATCH PRESS CONFERENCE\n");
        sb.append(facts.scoreLine()).append("\n");
        sb.append(userTeam.getName()).append(facts.isHome() ? " at home" : " away")
                .append(" · ").append(facts.scoredFor()).append('-').append(facts.scoredAgainst())
                .append(" · ").append(facts.shots(userTeam.getName())).append(" shots · ")
                .append(facts.yellowCards(userTeam.getName())).append(" yellow, ")
                .append(facts.redCards(userTeam.getName())).append(" red\n");
        sb.append("Goals — ").append(userTeam.getName()).append(": ").append(facts.scorerList(userTeam.getName()))
                .append(" | ").append(facts.opponent()).append(": ").append(facts.scorerList(facts.opponent()))
                .append("\n\n");

        sb.append(q("First reaction?", reactionFor(shape, facts)));
        sb.append(q("How did you see it?", assessmentFor(shape, facts)));

        // The question is chosen by what happened, so a collapse is asked about rather than
        // skipped over with a generic line about character.
        switch (shape) {
            case COLLAPSE -> sb.append(q("You were ahead on the scoreboard and lost it. What happened out there?",
                    collapseAnswer(facts)));
            case COMEBACK -> sb.append(q("You were behind and won it. What changed?",
                    comebackAnswer(facts)));
            case ROUT, THRASHING -> sb.append(q("How do you keep a team switched on for ninety minutes?",
                    managementAnswer(shape, facts)));
            case CLEAN_SHEET_WIN -> sb.append(q("How satisfying was that clean sheet to defend?",
                    "\"" + capitalise(safeShapeName(shape)) + ". The back four were excellent and I will "
                            + "not pretend otherwise. But a clean sheet is built from eleven people, not "
                            + "four.\""));
            case GOALLESS -> sb.append(q("Zero nil. Where did it go missing for you?",
                    facts.scoredAgainst() == 0
                            ? "\"Nowhere in particular, which is what worries me. We have to be harder to "
                            + "break down when we do not score.\""
                            : "\"The final third. We were good enough to keep asking questions and not good "
                            + "enough to answer one.\""));
            default -> sb.append(q("Is that the level you expect week in, week out?",
                    "\"" + capitalise(safeShapeName(shape)) + ". We know what we are, and the squad knows "
                            + "what it is supposed to be.\""));
        }

        if (standout != null) {
            // The standout can be an opponent's player. Congratulating yourself on the opposing
            // striker's performance is the kind of detail that marks copy as written, not said.
            CSPlayerMatchStats motm = standout.stats();
            if (standout.home() == facts.isHome()) {
                sb.append(q(motm.getPlayerName() + " was outstanding — your view?",
                        "\"" + capitalise(safeShapeName(shape)) + ", and " + motm.getPlayerName()
                                + " was the best player on that pitch by a distance. He gets my man of "
                                + "the match.\""));
            } else {
                sb.append(q("You have to concede " + motm.getPlayerName() + " was the best player on the pitch.",
                        "\"Of course he was. He scored " + motm.getGoals() + " and rated "
                                + (int) Math.round(motm.getRating()) + " out of ten, and we managed "
                                + facts.shotsFor() + " shots in ninety minutes. Those are the two numbers "
                                + "that matter from this afternoon.\""));
            }
        }

        sb.append(q("What now?", lookingAheadFor(shape, state)));

        return sb.toString();
    }

    /** A question line, so the formatting is identical wherever one appears. */
    private static String q(String question, String answer) {
        return "Q: \"" + question + "\"\n" + answer + "\n\n";
    }

    private String reactionFor(CSMatchFacts.Shape shape, CSMatchFacts facts) {
        return switch (shape) {
            case ROUT -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + " against "
                    + facts.opponent() + ". The scorers are on the sheet and nobody should need reminding. "
                    + "That is what this side is capable of when it is at its best.\"";
            case COMEBACK -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + ", and we had to work for every one. "
                    + "Getting something from a game you were losing is worth more than a comfortable win.\"";
            case COLLAPSE -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + ", and I will not dress that up. "
                    + "We were in a winning position and we handed it back. The players know it too.\"";
            case CLEAN_SHEET_WIN -> "\"" + facts.scoredFor() + "-0 " + facts.venuePhrase()
                    + ". A clean sheet is the part of this game that will be talked about, and rightly so.\"";
            case STALEMATE -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + ". A point is a point, "
                    + "but neither side will claim it was a good afternoon's entertainment.\"";
            case GOALLESS -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + ". No goals and therefore no "
                    + "headline, which is exactly how I felt watching it.\"";
            case THRASHING -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + " " + facts.venuePhrase()
                    + ". Nothing about that was acceptable. The scoreline does not need embellishing.\"";
            case NARROW_LOSS -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + ". I am not going to stand "
                    + "here and tell you it was a good game. One of these results has to be on the other side "
                    + "of the page.\"";
            default -> "\"" + facts.scoredFor() + "-" + facts.scoredAgainst() + ", which is three points. "
                    + "It was not comfortable and it was not pretty, but it is on the board.\"";
        };
    }

    private String assessmentFor(CSMatchFacts.Shape shape, CSMatchFacts facts) {
        int shotsFor = facts.shots(facts.opponent());
        return switch (shape) {
            case COLLAPSE -> "\"They scored through " + facts.scorerList(facts.opponent())
                    + ", and we had the lead before any of it. That is what we take from this: a winning "
                    + "position given away.\"";
            case THRASHING -> "\"They scored through " + facts.scorerList(facts.opponent())
                    + ", and we managed " + facts.shotsFor() + " shots in reply. There is no way to frame "
                    + "that as a game we were in.\"";
            case ROUT -> "\"" + facts.shots(facts.opponent()) + " shots to our " + facts.shotsFor()
                    + ", and the game was over long before the end. When it is like this, the honest "
                    + "assessment is that we were better in every department.\"";
            case COMEBACK -> "\"They scored through " + facts.scorerList(facts.opponent())
                    + ", and that is not a flattering line. It is also exactly why the comeback counts: "
                    + "we were second best for most of the game.\"";
            default -> "\"" + facts.shotsFor() + " shots to " + shotsFor + ". Not a lot of football either "
                    + "way, and the result tells you which half of that game we managed better.\"";
        };
    }

    private String collapseAnswer(CSMatchFacts facts) {
        return "\"We did not lose it on the pitch, we lost it in our heads between the seventy-fifth "
                + "minute and full time. Losing " + (facts.scoredAgainst() - facts.scoredFor())
                + " clear goals is a collective failure and I will not put it on the referee.\"";
    }

    private String comebackAnswer(CSMatchFacts facts) {
        return "\"Patience, mostly. We were behind and there is a temptation to rush everything. "
                + "The goals came from " + facts.scorerList(facts.opponent()) + ", so we kept the shape and "
                + "waited for the openings.\"";
    }

    private String managementAnswer(CSMatchFacts.Shape shape, CSMatchFacts facts) {
        boolean won = facts.margin() > 0;
        return "\"That is the next thing to solve. Holding " + safeShapeName(shape)
                + " together for ninety minutes is one problem; repeating it week in, week out is "
                + "another. The squad will not get "
                + (won ? "the credit for winning and we will not let them think that solves everything."
                       : "an excuse for this, and we will not let them treat it as normal either.")
                + "\"";
    }

    /** First letter upper-cased, so a noun phrase can start a sentence. */
    private static String capitalise(String phrase) {
        return phrase.isEmpty() ? phrase : Character.toUpperCase(phrase.charAt(0)) + phrase.substring(1);
    }

    /**
     * A noun phrase naming the result, not a sentence.
     *
     * <p>It was a sentence before, and one call site spliced it into the middle of another sentence,
     * which produced "That was a chastening afternoon. on the pitch is one thing". Each caller now
     * supplies its own ending.
     */
    private String safeShapeName(CSMatchFacts.Shape shape) {
        return switch (shape) {
            case ROUT -> "a complete performance";
            case NARROW_WIN -> "a tight game we found a way to win";
            case COMEBACK -> "a game we had to claw back";
            case COLLAPSE -> "a game we were winning and gave away";
            case CLEAN_SHEET_WIN -> "a satisfying afternoon";
            case STALEMATE -> "a fair draw";
            case NARROW_LOSS -> "a good game that went against us";
            case THRASHING -> "a chastening afternoon";
            case GOALLESS -> "a cagey game";
        };
    }

    private String lookingAheadFor(CSMatchFacts.Shape shape, CleanSheetGameState state) {
        CSTeam userTeam = state.getUserTeam();
        return switch (shape) {
            case COLLAPSE, THRASHING -> "\"We have a week to work on it and no excuses. The table does not "
                    + "care how we lost.\"";
            case ROUT, CLEAN_SHEET_WIN -> "\"Enjoy this one. It has been a long build and we have not taken "
                    + "our foot off yet.\"";
            case COMEBACK -> "\"Spirits are high, which is dangerous. The easiest thing in football is to "
                    + "believe last week is a habit.\"";
            case NARROW_LOSS -> "\"We go again, and we go again properly. One game does not define a season.\"";
            case STALEMATE, GOALLESS -> "\"On to the next one. " + userTeam.getName() + " have plenty to "
                    + "look forward to.\"";
            default -> "\"We take the points and build on them. Nothing about tonight was perfect, and "
                    + "nothing about it was a disaster.\"";
        };
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

    /**
     * A forward-looking note about something the club is one or two results away from.
     *
     * <p>The earlier version watched a single player approach the next multiple of five and said
     * so in three sentences, two of which were filler. It only ever looked at goals, so a club
     * streaking towards a record run, a clean sheet or a promotion place produced nothing at all.
     *
     * <p>Four tracks now — scoring, assists, form and the table — each with the real number behind
     * it, because "future marker on the horizon" tells a manager nothing they could not read off
     * the table themselves. Only one fires at a time, and only when genuinely close, so the desk
     * stays a signal rather than a newsletter.
     */
    public String generateMilestoneAnnouncement(CleanSheetGameState state) {
        if (state == null || state.getRoster() == null || state.getRoster().isEmpty()) return null;

        Long teamId = state.getUserTeam().getId();
        CSTableEntry entry = findTableEntry(state, teamId);
        int round = state.getCurrentRound();
        int totalRounds = state.getTotalRounds();

        String scoring = closestScoringMilestone(state, teamId);
        if (scoring != null) return milestoneNote("MILESTONE WATCH // " + clubName(state, teamId),
                scoring, round, totalRounds);

        String form = formMilestone(state, teamId, round);
        if (form != null) return milestoneNote("MILESTONE WATCH // " + clubName(state, teamId),
                form, round, totalRounds);

        String table = tableMilestone(state, entry, round);
        if (table != null) return milestoneNote("MILESTONE WATCH // " + clubName(state, teamId),
                table, round, totalRounds);

        return null;
    }

    private String clubName(CleanSheetGameState state, Long teamId) {
        return state.getUserTeam().getName();
    }

    private String milestoneNote(String title, String body, int round, int totalRounds) {
        return title + "\n"
                + "Round " + round + " of " + totalRounds + "\n\n"
                + body + "\n\n"
                + "Records desk: this note fires only when a marker is genuinely within reach. "
                + "Nothing is claimed until it is actually done.";
    }

    /** The best of: next 5/10/15 goal mark for the leading scorer, and the league's top scorers. */
    private String closestScoringMilestone(CleanSheetGameState state, Long teamId) {
        CSPlayer topScorer = state.getRoster().stream()
                .max(Comparator.comparingInt(CSPlayer::getGoals))
                .orElse(null);
        if (topScorer == null || topScorer.getGoals() <= 0) return null;

        int goals = topScorer.getGoals();
        int next = ((goals / 5) + 1) * 5;
        int needed = next - goals;
        if (needed <= 0 || needed > 2) return null;

        CSPlayer assistLeader = state.getRoster().stream()
                .max(Comparator.comparingInt(CSPlayer::getAssists))
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("SCORING\n");
        sb.append(topScorer.getName()).append(" (").append(topScorer.getPosition()).append(") is on ")
                .append(goals).append(" for the season and ").append(needed)
                .append(needed == 1 ? " goal" : " goals")
                .append(" short of ").append(next).append(".\n");

        if (assistLeader != null && assistLeader.getAssists() >= 2) {
            int assistMark = ((assistLeader.getAssists() / 3) + 1) * 3;
            int assistNeeded = assistMark - assistLeader.getAssists();
            if (assistNeeded > 0 && assistNeeded <= 2) {
                sb.append("At the other end, ").append(assistLeader.getName()).append(" needs ")
                        .append(assistNeeded).append(" more ")
                        .append(assistNeeded == 1 ? "assist" : "assists")
                        .append(" for ").append(assistMark).append(".\n");
            }
        }

        sb.append("\nAt this rate the mark is plausible rather than decorative: he needs one goal ")
                .append(needed == 1 ? "in" : "across the next ").append(needed)
                .append(needed == 1 ? " match" : " matches").append(".");
        return sb.toString();
    }

    /** A run of results, stated with the actual letters and the numbers behind it. */
    private String formMilestone(CleanSheetGameState state, Long teamId, int round) {
        CSSeasonStats stats = state.getSeasonStats();
        if (stats == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("FORM\n");

        int winStreak = stats.getCurrentWinStreak();
        int unbeatenStreak = stats.getCurrentUnbeatenStreak();
        int lossStreak = stats.getCurrentLossStreak();

        // One track only, and the most interesting one that actually applies.
        if (winStreak >= 3) {
            sb.append(winStreak).append(" wins in a row");
            sb.append(winStreak == stats.getLongestWinStreak()
                    ? " — already the best run of this campaign.\n"
                    : ", one short of the campaign-best run of "
                      + stats.getLongestWinStreak() + ".\n");
        } else if (unbeatenStreak >= 4) {
            sb.append(unbeatenStreak).append(" matches unbeaten");
            sb.append(unbeatenStreak == stats.getLongestUnbeatenStreak()
                    ? " — the longest run of the season so far.\n"
                    : ", chasing the season-best " + stats.getLongestUnbeatenStreak() + ".\n");
        } else if (lossStreak >= 3) {
            sb.append(lossStreak).append(" defeats without a result")
                    .append(lossStreak == stats.getLongestLossStreak()
                            ? " — a run that has defined this campaign.\n"
                            : ", one short of the worst run of " + stats.getLongestLossStreak() + ".\n");
        } else {
            return null;
        }

        // The counterweight. A run of wins that is built on conceding nine is not the same story
        // as one built on five clean sheets, and the manager should be able to see which.
        StringBuilder context = new StringBuilder();
        if (stats.getCleanSheets() > 0) {
            context.append(stats.getCleanSheets()).append(" clean sheet")
                    .append(stats.getCleanSheets() == 1 ? "" : "s").append(" this season");
            if (stats.getFailedToScore() > 0) {
                context.append(", ").append(stats.getFailedToScore()).append(" without scoring");
            }
            context.append(".");
        } else if (stats.getFailedToScore() > 0) {
            context.append(stats.getFailedToScore()).append(" matches without scoring — ")
                    .append("that, not this run, is the run that matters.");
        }

        if (stats.getComebacks() >= 2) {
            if (context.length() > 0) context.append(' ');
            context.append(stats.getComebacks()).append(" won after going behind");
            if (stats.getCollapsedLeads() > 0) {
                context.append(" against ").append(stats.getCollapsedLeads())
                        .append(" thrown away from a lead");
            }
            context.append(".");
        } else if (stats.getCollapsedLeads() >= 2) {
            if (context.length() > 0) context.append(' ');
            context.append(stats.getCollapsedLeads())
                    .append(" leads surrendered from a winning position — ")
                    .append("more costly than the results above it.");
        }

        if (stats.getLateGoalsScored() >= 3) {
            if (context.length() > 0) context.append(' ');
            context.append(stats.getLateGoalsScored())
                    .append(" goals after the seventy-fifth minute, and ")
                    .append(stats.getLateGoalsConceded())
                    .append(" conceded in the same window.");
        }

        if (context.length() == 0) return null;

        sb.append('\n').append(context).append('\n');
        sb.append("\nRecords desk: form is not a run of letters, it is whatever produced them. ")
                .append("This is the season's own accounting, not a summary of it.");
        return sb.toString();
    }

    /** Something the table is close to saying. */
    private String tableMilestone(CleanSheetGameState state, CSTableEntry entry, int round) {
        if (entry == null || entry.getPlayed() < 3) return null;

        List<CSTableEntry> table = state.getLeagueTable();
        int position = table.indexOf(entry) + 1;
        if (position <= 0) return null;

        // How close is the gap to the place above?
        if (position > 1) {
            CSTableEntry above = table.get(position - 2);
            int gap = above.getPoints() - entry.getPoints();
            if (gap >= 0 && gap <= 2) {
                return "TABLE\n"
                        + position + ordinalWords(position) + ", " + gap + " point"
                        + (gap == 1 ? "" : "s") + " behind " + above.getTeamName()
                        + " on " + above.getPoints() + " points.\n"
                        + "One win puts level, and level puts a match in hand. This is the part of the "
                        + "season where the table actually moves.\n\n"
                        + "Records desk: position after " + entry.getPlayed() + " matches.";
            }
        }

        int available = entry.getPlayed() + (Math.max(1, table.size()) - 1) * 2;
        int pointsBehindTop = table.getFirst().getPoints() - entry.getPoints();
        if (pointsBehindTop <= 3 && available > 0) {
            return "TABLE\n"
                    + position + ordinalWords(position) + ", only " + pointsBehindTop + " point"
                    + (pointsBehindTop == 1 ? "" : "s") + " off the top after "
                    + entry.getPlayed() + " matches.\n"
                    + "Nobody expected this so early and that is exactly why it is worth playing "
                    + "straight.\n\n"
                    + "Records desk: the gap is smaller than the fixtures played.";
        }

        return null;
    }

    private String ordinalWords(int position) {
        return switch (position) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /**
     * The rating a player needs to be projected for an international call-up.
     *
     * <p>This was {@code squadBest - 4}. Player rating runs 1-10 (see
     * {@link org.tifo.footballtextmanager.model.CSPlayer}), so on a real squad — best around 8,
     * the rest between 3 and 6 — "within four of the best" selected <b>every player in the club</b>.
     * The bulletin duly announced that fifteen of fifteen were internationals, which is not a
     * selection model, it is a tautology.
     *
     * <p>An absolute bar is the only thing that means anything here: 7 out of 10 is a player good
     * enough to be picked for a national side. A weak squad then correctly projects nobody, and the
     * message says exactly that instead of inventing a call-up.
     */
    public static final int CALL_UP_RATING = 7;

    /**
     * The players a national coach would plausibly select, best first.
     *
     * <p>Shared by the bulletin (what the window costs the club) and the call-up watch (who is
     * going), because two copies of the rule would be two answers to the same question.
     */
    public static List<CSPlayer> projectedCallUps(List<CSPlayer> roster) {
        if (roster == null) return List.of();
        return roster.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getRating() >= CALL_UP_RATING)
                .sorted(Comparator.comparingInt(CSPlayer::getRating).reversed())
                .toList();
    }

    /** The squad's best rating, or 0 when there is no squad. */
    public static int squadBestRating(List<CSPlayer> roster) {
        if (roster == null) return 0;
        return roster.stream().filter(Objects::nonNull)
                .mapToInt(CSPlayer::getRating).max().orElse(0);
    }

    /**
     * Call-up watch for the coming international window.
     *
     * <p>The old version shuffled the squad and announced one to three players at random. It had no
     * notion of who could plausibly be selected, it never said how many of your players that was,
     * and — the part that made it read as noise — it told you people were away but never when they
     * would be back or what it would cost the round after.
     *
     * <p>It is now derived from player rating against an absolute bar
     * ({@link #CALL_UP_RATING}), and it states the real consequence: which rounds you play short, and
     * by how much. A squad with nobody at the bar correctly produces a message saying so.
     */
    public String generateNationalTeamUpdate(CleanSheetGameState state) {
        if (state == null || state.getRoster() == null || state.getRoster().isEmpty()) return null;

        List<CSPlayer> roster = state.getRoster().stream().filter(Objects::nonNull).toList();
        int best = squadBestRating(roster);
        int threshold = CALL_UP_RATING;
        List<CSPlayer> claimable = projectedCallUps(roster);
        if (best == 0 || threshold == 0) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("INTERNATIONAL CALL-UP WATCH\n");
        sb.append("Window before league round ").append(state.getCurrentRound() + 1)
                .append(" of ").append(state.getTotalRounds()).append("\n\n");

        if (claimable.isEmpty()) {
            sb.append("No player in the squad is close to international standard (best rating ")
                    .append(best).append(", call-up threshold ").append(threshold)
                    .append("), so no domestic cover is required.");
            return sb.toString();
        }

        sb.append("Squad best rating ").append(best).append(". Call-up threshold ")
                .append(threshold).append(".\n");
        sb.append("Projected away: ").append(claimable.size()).append(" of ").append(roster.size())
                .append(" players.\n\n");

        for (CSPlayer p : claimable) {
            sb.append("  · ").append(p.getName()).append(" — ").append(p.getPosition())
                    .append(", rated ").append(p.getRating()).append(", age ").append(p.getAge())
                    .append(", ").append(p.getGoals()).append(p.getGoals() == 1 ? " goal" : " goals")
                    .append(p.getAssists() > 0
                            ? ", " + p.getAssists() + (p.getAssists() == 1 ? " assist" : " assists") : "")
                    .append("\n");
        }

        sb.append("\nDomestic cover needed for ").append(claimable.size())
                .append(claimable.size() == 1 ? " player" : " players").append(".\n");

        // The part that was missing and the reason a manager reads this message: when it bites.
        int outRound = state.getCurrentRound() + 1;
        sb.append("Returns: normally available from round ").append(outRound + 2)
                .append(", subject to travel and a fitness assessment on arrival.\n");
        sb.append("Fatigue: an international appearance carries roughly half a match's load. "
                + "Expect reduced sharpness, not a full absence.\n\n");
        sb.append("Plan for round ").append(outRound + 1)
                .append(" with the cover player, not with the player on international duty.");
        return sb.toString();
    }


    public String generateTransferMarketWatch(CleanSheetGameState state) {
        if (state == null || state.getTransferMarket() == null || state.getTransferMarket().isEmpty()) return null;
        List<CSTransferListing> market = state.getTransferMarket();
        int count = Math.min(3, market.size());
        StringBuilder sb = new StringBuilder();
        sb.append("TRANSFER MARKET WATCH\n");
        sb.append("Names currently on the market\n\n");
        for (int i = 0; i < count; i++) {
            CSTransferListing listing = market.get(i);
            sb.append(listing.getPlayerName()).append(" (").append(listing.getPosition())
                    .append(") - ").append(listing.getSellerTeamName())
                    .append(" - asking ").append(formatMoneySafe(listing.getAskingPrice())).append("\n");
        }
        sb.append("\nOpen the transfer desk before another club moves first.");
        return sb.toString();
    }

    private String formatMoneySafe(double value) {
        return "€" + String.format("%,.0f", value);
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
    
    /**
     * The press round-up, written from the match rather than about a mood.
     *
     * <p>The previous version drew three headlines from the same four-option pool per result type,
     * so a 1-0 win and a 4-0 produced interchangeable copy and the same sentence returned every
     * few rounds. Each headline now carries at least one fact from this match — the score, a
     * scorer, the margin, the table position — and the three angles are deliberately different
     * (result, key player, consequence) so they read as three papers rather than one sentence
     * written three times.
     */
    public String generateMediaHeadlines(CleanSheetGameState state, CSMatchResult result, String resultType) {
        CSTeam userTeam = state.getUserTeam();
        CSMatchFacts facts = CSMatchFacts.from(result, userTeam.getName(), userTeam.getId());
        if (facts == null) return "MEDIA ROUND-UP\n\nNo report available.";

        CSTableEntry entry = findTableEntry(state, userTeam.getId());
        int position = entry != null ? state.getLeagueTable().indexOf(entry) + 1 : 0;
        CSMatchFacts.Standout standout = facts.standout();
        CSPlayerMatchStats motm = standout == null ? null : standout.stats();

        String shortName = userTeam.getName().split(" ")[0];
        String score = facts.scoredFor() + "-" + facts.scoredAgainst();

        String[] papers = {"Sport Daily", "Football Weekly", "The Tribune", "Match Report", "Goal News"};

        // Angles, not synonyms.
        String resultAngle = switch (facts.shape()) {
            case ROUT -> shortName.toUpperCase() + " " + score + " " + facts.opponent()
                    + " — a performance that said everything";
            case COLLAPSE -> shortName.toUpperCase() + " LET " + score + " SLIP AWAY";
            case COMEBACK -> shortName.toUpperCase() + " DIG IN FOR " + score + " AT " + facts.opponent();
            case CLEAN_SHEET_WIN -> shortName.toUpperCase() + " SHUT THEM OUT, WIN " + score;
            case STALEMATE -> "NO WAY THROUGH IN A " + score + " STALEMATE";
            case GOALLESS -> "SCRAPPY, GOALLESS — " + score + " AND FEW CHANCES";
            case THRASHING -> shortName.toUpperCase() + " CRUMBLE, " + score + " AT " + facts.opponent();
            case NARROW_LOSS -> shortName.toUpperCase() + " BEATEN BY THE SMALLEST MARGIN";
            default -> shortName.toUpperCase() + " GRIND OUT " + score;
        };

        String playerAngle = motm == null
                ? shortName + " had no outstanding individual, which said plenty about the fixture"
                : (motm.getPlayerName() == null ? "Someone" : motm.getPlayerName()) + " "
                + (int) Math.round(motm.getRating()) + "/10 — " + (motm.getGoals() > 0
                ? (motm.getGoals() + " goal" + (motm.getGoals() == 1 ? "" : "s")
                + (motm.getAssists() > 0
                ? " and " + motm.getAssists() + " assist" + (motm.getAssists() == 1 ? "" : "s") : ""))
                : "the best player on a park that offered him little");

        String consequenceAngle = position > 0
                ? shortName + " now " + ordinalPhrase(position) + " on " + entry.getPoints()
                + " points after " + entry.getPlayed() + " matches"
                : shortName + " have " + entry.getPlayed()
                + " matches on the clock and the table is beginning to take shape";

        String[] angles = {resultAngle, playerAngle, consequenceAngle};

        StringBuilder sb = new StringBuilder();
        sb.append("MEDIA ROUND-UP\n");
        sb.append(facts.scoreLine()).append("\n\n");

        // Three papers, three angles, and never the same paper twice in one round-up.
        boolean[] used = new boolean[papers.length];
        for (int i = 0; i < angles.length; i++) {
            int pick;
            do {
                pick = rnd.nextInt(papers.length);
            } while (used[pick]);
            used[pick] = true;
            sb.append("📰 ").append(papers[pick]).append(":\n");
            sb.append("\"").append(angles[i]).append("\"\n\n");
        }

        sb.append("Expert opinion:\n");
        sb.append(expertOpinionFor(facts, motm, position));

        return sb.toString();
    }

    private String ordinalPhrase(int position) {
        if (position <= 0) return "unplaced";
        int mod100 = position % 100;
        String suffix;
        if (mod100 >= 11 && mod100 <= 13) {
            suffix = "th";
        } else {
            suffix = switch (position % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        }
        return position + suffix;
    }

    /**
     * Punditry that quotes the game. "Well-organised unit" was true of every side in every round,
     * which is why it read as noise.
     */
    private String expertOpinionFor(CSMatchFacts facts, CSPlayerMatchStats motm, int position) {
        String quote = "\"";
        String team = facts.opponent();

        return switch (facts.shape()) {
            case COLLAPSE, THRASHING -> quote + facts.scoredAgainst() + "-" + facts.scoredFor()
                    + " tells you everything about where this went wrong. You cannot coach a team that "
                    + "concedes once you have led." + quote + " - Former international";
            case COMEBACK -> quote + "They were the better side for fifty-five minutes and finished with "
                    + "nothing. That is a coaching problem, not a luck one." + quote + " - TV pundit";
            case ROUT -> quote + facts.scoredFor() + " goals against " + team + " is not a performance, it "
                    + "is a statement of intent." + quote + " - Ex-pro";
            case CLEAN_SHEET_WIN -> quote + "A clean sheet at " + team + " is worth three points "
                    + "and most of the credit." + quote + " - Former international";
            case GOALLESS -> quote + "Ninety minutes of nothing, then three points. Football is often "
                    + "unfair." + quote + " - TV pundit";
            case STALEMATE -> quote + "Draws like this are won in the dressing room, in the small "
                    + "margins." + quote + " - Ex-pro";
            default -> quote + (facts.isHome()
                    ? "Winning " + facts.scoredFor() + "-" + facts.scoredAgainst() + " at home is worth "
                      + "three points and no more than that."
                    : "Scoring " + facts.scoredFor() + " away to " + team + " is the harder half of the "
                      + "job and they did it.") + quote + " - Former international";
        };
    }

    // ==================== HELPER METHODS ====================
    
    
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
