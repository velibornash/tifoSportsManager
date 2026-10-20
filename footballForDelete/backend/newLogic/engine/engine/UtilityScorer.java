package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.engine.SpaceInfo;

@FunctionalInterface
public interface UtilityScorer {
    double score(PlayerSnapshot player, MatchState state, SpaceInfo space);
}
