package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.AllEventDTO;
import org.example.footballmanager.dto.GoalEventDTO;
import org.example.footballmanager.dto.MatchDetailDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MatchDetailService {

    private final MatchRepository matchRepository;

    private final GoalEventRepository goalEventRepository;
    private final ChanceEventRepository chanceEventRepository;
    private final YellowCardEventRepository yellowCardEventRepository;
    private final RedCardEventRepository redCardEventRepository;
    private final PenaltyEventRepository penaltyEventRepository;
    private final FreeKickEventRepository freeKickEventRepository;
    private final OffsideEventRepository offsideEventRepository;
    private final CornerEventRepository cornerEventRepository;
    private final SubstitutionEventRepository substitutionEventRepository;
    private final VARReviewEventRepository varReviewEventRepository;
    private final ShotOnTargetEventRepository shotOnTargetEventRepository;
    private final ShotOffTargetEventRepository shotOffTargetEventRepository;
    private final MatchStartEventRepository matchStartEventRepository;
    private final MatchEndedEventRepository matchEndedEventRepository;
    private final InjuryEventRepository injuryEventRepository;

    public MatchDetailDTO getMatchDetail(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        MatchDetailDTO dto = MatchDetailDTO.fromMatch(match);


        // --- All events ---
        List<AllEventDTO> allEvents = new ArrayList<>();
        allEvents.addAll(goalEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromGoalEvent).toList());
        allEvents.addAll(chanceEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromChanceEvent).toList());
        allEvents.addAll(yellowCardEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromYellowCardEvent).toList());
        allEvents.addAll(redCardEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromRedCardEvent).toList());
        allEvents.addAll(penaltyEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromPenaltyEvent).toList());
        allEvents.addAll(freeKickEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromFreeKickEvent).toList());
        allEvents.addAll(offsideEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromOffsideEvent).toList());
        allEvents.addAll(cornerEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromCornerEvent).toList());
        allEvents.addAll(substitutionEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromSubstitutionEvent).toList());
        allEvents.addAll(varReviewEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromVARReviewEvent).toList());
        allEvents.addAll(shotOnTargetEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromShotOnTargetEvent).toList());
        allEvents.addAll(shotOffTargetEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromShotOffTargetEvent).toList());
        allEvents.addAll(matchStartEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromMatchStartEvent).toList());
        allEvents.addAll(matchEndedEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromMatchEndedEvent).toList());
        allEvents.addAll(injuryEventRepository.findByMatchId(matchId).stream()
                .map(AllEventDTO::fromInjuryEvent).toList());

        dto.setAllEvents(allEvents);

        return dto;
    }
}