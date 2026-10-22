package org.tifo.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.footballmanager.newLogic.model.NotificationKind;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.Transfer;
import org.tifo.footballmanager.newLogic.model.TransferStatus;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * The club that cannot pay its way.
 *
 * <p><b>Owner, 2026-10-22:</b> *"kad je tim 2 uzastopne nedelje budget u minusu - najbolji igrac
 * ide automatski na TL. Ako ta prodaja ne izvadi iz minusa ide sledeci dok ne izadje iz minusa.
 * ako ne izadje iz minusa - bankrot, igrac ostaje bez tima i tim postaje AI tim (resetuju mu se
 * finansije). Igrac samo moze cekati ako mu se javi neki drugi tim da preuzme sto se moze a i ne
 * mora desiti."*
 *
 * <p>Two consecutive weeks in the red and the club starts selling its best players to survive. If a
 * sale does not pull it out of the red, the next best goes, and so on until the club is solvent or
 * has nothing left to sell. If it never gets out, the manager loses the club, the club becomes AI,
 * and the finances reset. The manager is then free and may be offered another club - or not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankruptcyService {

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final TransferRepository transfers;
    private final TransferService transferService;
    private final UserRepository users;
    private final NotificationService notifications;

    /**
     * Called after a club's weekly finances have been settled. Decides whether the club must sell
     * or has run out of road.
     */
    @Transactional
    public void check(Team club, double closingBudget) {
        if (club == null || club.getId() == null) return;
        if (closingBudget >= 0) return;
        if (club.getConsecutiveNegativeWeeks() < 2) return;

        // The best player who is not already on the market. Sold at his value, because a forced
        // sale is still a sale at a price the market can see.
        List<Player> squad = players.findByTeamId(club.getId());
        List<Player> unlisted = squad.stream()
                .filter(p -> !isListed(p.getId()))
                .sorted(Comparator.comparingDouble(Player::getPlayerValue).reversed())
                .toList();

        if (!unlisted.isEmpty()) {
            Player best = unlisted.get(0);
            try {
                transferService.forceListPlayer(best.getId(), best.getPlayerValue());
                log.info("Bankruptcy recovery: {} listed {} (value {}) to survive.",
                        club.getName(), best.getName(), best.getPlayerValue());
            } catch (RuntimeException e) {
                log.warn("Bankruptcy recovery failed to list {} for {}: {}",
                        best.getName(), club.getName(), e.getMessage());
            }
            return;
        }

        // Nothing left to sell and still in the red: bankruptcy.
        bankrupt(club);
    }

    private boolean isListed(Long playerId) {
        return transfers.findByPlayerId(playerId)
                .map(t -> t.getStatus() == TransferStatus.LISTED && t.getBuyerTeam() == null)
                .orElse(false);
    }

    private void bankrupt(Team club) {
        log.warn("Bankruptcy: {} (id {}) has run out of road. The manager loses the club, it becomes "
                + "AI, and the finances reset.", club.getName(), club.getId());

        // The club becomes AI and its finances reset.
        club.setHumanControlled(false);
        club.setBudget(0.0);
        club.setConsecutiveNegativeWeeks(0);
        teams.save(club);

        // The manager loses the club. He is now free and may be offered another one - or not.
        for (User user : users.findAll()) {
            if (user.getFootballTeam() != null
                    && user.getFootballTeam().getId().equals(club.getId())) {
                user.setFootballTeam(null);
                users.save(user);
                try {
                    notifications.notify(user, NotificationKind.BANKRUPTCY,
                            club.getName() + " went bankrupt. You are now without a club and may be "
                                    + "offered another.",
                            "office", club.getId());
                } catch (RuntimeException e) {
                    log.warn("Bankruptcy notification failed for user {}: {}", user.getId(), e.getMessage());
                }
            }
        }
    }
}
