package org.example.footballtextmanager;

import org.example.footballtextmanager.model.CPlayer;
import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSPosition;
import org.example.footballtextmanager.model.CSSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Roster lists must be mutable, because the transfer engine mutates them in place.
 *
 * <p>Every roster in the text mode comes from {@link CSMapper#toCSPlayers}. That method returned
 * {@code stream().map(...).toList()}, and {@code Stream.toList()} produces an <b>immutable</b>
 * list. So the first {@code roster.remove(player)} in
 * {@code CleanSheetService.executeTransfer} threw {@link UnsupportedOperationException} and every
 * transfer in the mode answered HTTP 500 "Unexpected server error".
 *
 * <p>This is the kind of defect that a behaviour test written against the service would have
 * caught, but there is none — the transfer path had never been exercised. The assertion below is
 * deliberately small and structural: it states the contract the mapper has to honour, so the
 * regression cannot come back through a refactor that "tidies" the mapper again.
 */
class CSMapperRosterMutabilityTest {

    @Test
    @DisplayName("toCSPlayers returns a list that supports remove and add")
    void toCSPlayersReturnsAMutableList() {
        List<CSPlayer> roster = CSMapper.toCSPlayers(List.of(player(1L, "Marko Markovic"), player(2L, "Nina Nikolic")));

        assertEquals(2, roster.size());

        CSPlayer victim = roster.get(0);

        // The two operations the transfer engine performs on a roster.
        assertDoesNotThrow(() -> roster.remove(victim),
                "roster.remove() threw: the roster handed out by the mapper is immutable, "
                        + "so every buy and every sale ends in HTTP 500");

        assertDoesNotThrow(() -> roster.add(CSMapper.toCSPlayer(player(3L, "Petar Petrovic"))),
                "roster.add() threw: an incoming loanee or a signed player cannot join the squad");

        assertEquals(2, roster.size());
        assertEquals("Nina Nikolic", roster.get(0).getName());
    }

    @Test
    @DisplayName("The returned list is independent of the source list")
    void returnedRosterIsDetachedFromTheSource() {
        List<CPlayer> source = new ArrayList<>(List.of(player(1L, "Marko Markovic")));
        List<CSPlayer> roster = CSMapper.toCSPlayers(source);

        source.clear();

        assertEquals(1, roster.size(),
                "Roster must be a copy. If it were a view over the source, clearing the source "
                        + "would silently empty a squad mid-season");
    }

    private static CPlayer player(Long id, String name) {
        CSSkills skills = new CSSkills();
        skills.setStamina(10);
        skills.setGoalkeeper(1);
        skills.setDefender(10);
        skills.setPace(10);
        skills.setTechnique(10);
        skills.setPlaymaker(10);
        skills.setPassing(10);
        skills.setStriker(10);
        skills.setFatigue(0);
        skills.initializeExactFromVisibleIfNeeded();

        CPlayer player = new CPlayer();
        player.setId(id);
        player.setName(name);
        player.setCSPosition(CSPosition.MID);
        player.setAge(24);
        player.setForm(6.0);
        player.setHeight(1.80);
        player.setWeight(75.0);
        player.setCSSkills(skills);
        return player;
    }
}