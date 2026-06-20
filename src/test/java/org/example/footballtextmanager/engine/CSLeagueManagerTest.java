package org.example.footballtextmanager.engine;


import org.example.footballtextmanager.engine.CSLeagueManager;
import org.example.footballtextmanager.model.CSTeam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CSLeagueManagerTest {

	@Test
	void generateDerbyRivalriesDoesNotShuffleImmutableList() {
	    CSLeagueManager leagueManager = new CSLeagueManager();
	    List<CSTeam> teams = List.of(
	            CSTeam.builder().id(1L).name("OFK Omladinac").build(),
	            CSTeam.builder().id(2L).name("SK Smederevo").build(),
	            CSTeam.builder().id(3L).name("TSK Beograd").build(),
	            CSTeam.builder().id(4L).name("FK Cacak United").build()
	    );

	    Map<Long, Set<Long>> rivalries = assertDoesNotThrow(() -> leagueManager.generateDerbyRivalries(teams));

	    assertNotNull(rivalries);
	    assertEquals(teams.size(), rivalries.size());
	    teams.forEach(team -> {
	        Set<Long> rivals = rivalries.get(team.getId());
	        assertNotNull(rivals);
	        assertFalse(rivals.contains(team.getId()));
	    });
	}
}