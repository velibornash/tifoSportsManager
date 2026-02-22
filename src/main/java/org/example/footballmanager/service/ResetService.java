package org.example.footballmanager.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResetService {

    private final PromotionRuleRepository promotionRuleRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final CountryRepository countryRepository;

    @Transactional
    public void resetDatabase() {

        log.warn("⚠ RESET DATABASE STARTED");

        promotionRuleRepository.deleteAll();
        competitionEntryRepository.deleteAll();
        seasonCompetitionRepository.deleteAll();
        playerRepository.deleteAll();
        teamRepository.deleteAll();
        competitionRepository.deleteAll();
        seasonRepository.deleteAll();
        countryRepository.deleteAll();

        log.warn("⚠ RESET DATABASE FINISHED");
    }
}