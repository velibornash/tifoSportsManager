package org.example.footballmanager.cleanSheet.state;

import lombok.Data;
import org.example.footballmanager.cleanSheet.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class CleanSheetGameState {

    private Long userId;
    private int seasonYear;
    private int currentRound = 1;

    // Korisnikov tim
    private CSTeam userTeam;
    private List<CSPlayer> roster = new ArrayList<>();
    private CSTactics tactics = CSTactics.builder().build();

    // Svi timovi u ligi (ukljucujuci korisnikov)
    private List<CSTeam> allTeams = new ArrayList<>();
    // Igraci svih timova: teamId -> lista igraca
    private Map<Long, List<CSPlayer>> allTeamRosters = new HashMap<>();

    // Liga
    private List<CSTableEntry> leagueTable = new ArrayList<>();
    private List<CSFixture> schedule = new ArrayList<>();

    // Istorija
    private List<CSMatchResult> matchHistory = new ArrayList<>();
    private List<CSInboxMessage> inbox = new ArrayList<>();

    public void addInboxMessage(String type, String text) {
        inbox.add(CSInboxMessage.builder()
                .type(type)
                .text(text)
                .timestamp(java.time.LocalDateTime.now().toString())
                .build());
    }

    public int getTotalRounds() {
        return schedule.stream()
                .mapToInt(CSFixture::getRound)
                .max()
                .orElse(0);
    }

    public boolean isSeasonOver() {
        return currentRound > getTotalRounds();
    }
}
