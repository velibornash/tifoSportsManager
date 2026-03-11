package org.example.footballmanager.service;

import org.example.footballmanager.dto.TacticsRuleDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormationSlotCatalogTest {

    @Test
    void defaultRulesKeepCenterBacksBehindMidfieldDuringAttackFallback() {
        FormationSlotCatalog catalog = new FormationSlotCatalog();

        int[] target = catalog.parseCellKey(findTarget(catalog.buildDefaultRules("4-4-2"), "DCL", "ATTACK_LEFT_CORNER", FormationSlotCatalog.WE_HAVE_BALL));

        assertEquals(2, target[0]);
        assertTrue(target[0] <= 2);
    }

    @Test
    void defaultRulesKeepStrikersHigherWhenDefendingWithoutSavedProfile() {
        FormationSlotCatalog catalog = new FormationSlotCatalog();

        int[] target = catalog.parseCellKey(findTarget(catalog.buildDefaultRules("4-4-2"), "STL", "DEFEND_LEFT_CORNER", FormationSlotCatalog.OPPONENT_HAS_BALL));

        assertTrue(target[0] >= 3, () -> "Expected striker fallback target to stay high enough, got progress band " + target[0]);
    }

    private String findTarget(List<TacticsRuleDTO> rules, String slotKey, String ballStateKey, String possessionContext) {
        return rules.stream()
                .filter(rule -> slotKey.equals(rule.getSlotKey()))
                .filter(rule -> ballStateKey.equals(rule.getBallStateKey()))
                .filter(rule -> possessionContext.equals(rule.getPossessionContext()))
                .map(TacticsRuleDTO::getTargetCellKey)
                .findFirst()
                .orElseThrow();
    }
}