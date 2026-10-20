package org.tifo.footballmanager.newLogic.engine;

import org.tifo.footballmanager.newLogic.model.MatchState;
import org.tifo.footballmanager.newLogic.model.PlayerSnapshot;
import org.tifo.footballmanager.newLogic.engine.SpaceInfo;

@FunctionalInterface
public interface UtilityScorer {
    double score(PlayerSnapshot player, MatchState state, SpaceInfo space);
}
