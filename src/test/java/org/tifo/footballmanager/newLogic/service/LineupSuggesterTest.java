package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Suggest lineup" has to be an answer and not a shrug (owner, 2026-10-11).
 *
 * <p>The owner: *"squad isključi 11 i klupa"* — the squad page picks eleven and a bench. A manager
 * opening that page twenty minutes before kickoff does not want an empty form; they want the best
 * available eleven so they can change two players.
 *
 * <p><b>The test that matters is the one about the owner's own club.</b> OFK Omladinac has thirteen
 * players, which is deliberate and must stay valid: eleven to start and two on the bench is a legal
 * answer to a squad of thirteen. A suggestion that insists on a full bench of seven would refuse to
 * suggest anything at all for that club — a feature that returns nothing for the team the owner actually
 * manages.
 */
class LineupSuggesterTest extends BaseTest {

    @Autowired private LineupSuggester suggester;
    @Autowired private SquadRegistrationService squads;
    @Autowired private org.tifo.footballmanager.newLogic.repository.PlayerRepository players;
    @Autowired private TeamRepository teams;

    private Team club;

    @BeforeEach
    void setUp() {
        club = new Team();
        club.setName("Suggest Club " + java.util.UUID.randomUUID());
        club = teams.save(club);
    }

    @Test
    @DisplayName("eleven starters and a bench, chosen from the squad")
    void suggestsElevenAndABench() {
        givenSquadOf(20);

        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        assertThat(suggestion.starters()).hasSize(MatchLineupService.STARTERS);
        assertThat(suggestion.bench()).isNotEmpty();
        assertThat(suggestion.starters()).doesNotContainAnyElementsOf(suggestion.bench());
        assertThat(suggestion.warnings()).isEmpty();
    }

    @Test
    @DisplayName("the best players are suggested, not merely the first eleven")
    void suggestsTheBestNotTheFirst() {
        // Deliberately added worst-first, so a suggestion that took the first eleven it found would
        // return the eleven worst players in the squad and still look like an answer.
        for (int i = 0; i < 6; i++) {
            add(club, "Rated " + i, Position.GK, 40 + i);
        }
        for (int i = 0; i < 6; i++) {
            add(club, "Defender " + i, Position.DEF, 40 + i);
        }
        for (int i = 0; i < 6; i++) {
            add(club, "Striker " + i, Position.ATT, 40 + i);
        }
        add(club, "The Best Keeper", Position.GK, 99);

        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        assertThat(names(suggestion.starters()))
                .as("the best goalkeeper in the squad must start, whatever order they were added in")
                .contains("The Best Keeper");
    }

    @Test
    @DisplayName("a keeper is picked even when the squad is thin on them")
    void alwaysFieldsAGoalkeeper() {
        // One keeper, twenty outfield players. Suggesting eleven outfield players would produce a side
        // that cannot keep a ball in play, and it would look like a completed suggestion.
        add(club, "Only Keeper", Position.GK, 60);
        for (int i = 0; i < 14; i++) {
            add(club, "Outfield " + i, Position.MID, 70 + i);
        }

        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        assertThat(names(suggestion.starters())).contains("Only Keeper");
    }

    @Test
    @DisplayName("thirteen players is a legal squad: eleven to start and two on the bench")
    void thirteenPlayersIsValid() {
        // OFK Omladinac's thirteen, deliberately. A suggester that demanded a full bench would refuse to
        // suggest anything here, and the club it would refuse is the owner's own.
        givenSquadOf(13);

        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        assertThat(suggestion.starters()).hasSize(MatchLineupService.STARTERS);
        assertThat(suggestion.bench())
                .as("a squad of thirteen leaves two on the bench, and two is the honest answer")
                .hasSize(2);
        assertThat(suggestion.warnings())
                .as("a short squad is not a fault, so it must not be reported as one")
                .isEmpty();
    }

    @Test
    @DisplayName("a squad too small to field eleven says so instead of suggesting a short side")
    void tooSmallToFieldEleven() {
        givenSquadOf(9);

        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        assertThat(suggestion.starters()).isEmpty();
        // The count, not just "cannot make an eleven". A version of this assertion that accepted either
        // message let a mutation past: the fallback path below the loop also returns a warning, so the
        // test passed whether or not the early check existed.
        assertThat(suggestion.warnings())
                .as("nine players cannot make an eleven, and a nine-player suggestion would be picked")
                .anyMatch(w -> w.contains("9") && w.contains("11"));
    }

    @Test
    @DisplayName("an empty squad says so rather than returning an empty answer as success")
    void anEmptySquadIsSaidOutLoud() {
        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        assertThat(suggestion.starters()).isEmpty();
        assertThat(suggestion.warnings()).isNotEmpty();
    }

    /** One player, with the fields a squad query reads. */
    private Player add(Team team, String name, Position position, int rating) {
        Player player = new Player();
        // The name is used exactly as given: the assertions below look for these names, and appending a
        // uniqueness suffix would make every one of them fail for a reason that is not the suggester.
        player.setName(name);
        player.setTeam(team);
        player.setPosition(position);
        player.setRating(rating);
        player.setAge(24);
        player.setForm(6.5);
        player.setMorale(60.0);
        return players.save(player);
    }

    private void givenSquadOf(int count) {
        Position[] shape = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT};
        for (int i = 0; i < count; i++) {
            add(club, "Player " + i, shape[i % shape.length], 50 + (i % 20));
        }
    }

    private static List<String> names(List<LineupSuggester.Pick> picks) {
        return picks.stream().map(LineupSuggester.Pick::name).toList();
    }
}
