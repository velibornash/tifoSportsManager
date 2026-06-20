package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.TacticsSlotDTO;
import org.example.footballmanager.newLogic.model.TacticRules;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NewLogicTacticsService {

    private final FormationSlotCatalog formationSlotCatalog = new FormationSlotCatalog();

    public TacticRules loadTacticRules(Long teamId, String formation) {
        String normalizedFormation = formationSlotCatalog.normalizeFormation(formation);
        List<String> slotKeys = loadSlotKeys(normalizedFormation);
        return TacticRules.createDefault(slotKeys);
    }

    public List<String> loadSlotKeys(String formation) {
        String normalizedFormation = formationSlotCatalog.normalizeFormation(formation);
        return formationSlotCatalog.getSlots(normalizedFormation).stream()
                .map(TacticsSlotDTO::getSlotKey)
                .toList();
    }
}
