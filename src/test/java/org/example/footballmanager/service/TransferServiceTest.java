package org.example.footballmanager.service;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.Transfer;
import org.example.footballmanager.model.TransferStatus;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TransferRepository;
import org.example.footballmanager.util.players.SquadNumberAssigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferRepository transferRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private SquadNumberAssigner squadNumberAssigner;

    @InjectMocks private TransferService transferService;

    @Test
    void buyListedPlayerCompletesTransferAndMovesBudgetsAndPlayer() {
        Team seller = new Team();
        seller.setId(1L);
        seller.setName("Seller FC");
        seller.setBudget(1_000.0);

        Team buyer = new Team();
        buyer.setId(2L);
        buyer.setName("Buyer FC");
        buyer.setBudget(800.0);

        Player player = new Player();
        player.setId(10L);
        player.setName("Marko");
        player.setPosition(Position.ATT);
        player.setTeam(seller);

        Transfer transfer = new Transfer();
        transfer.setId(77L);
        transfer.setPlayer(player);
        transfer.setSellerTeam(seller);
        transfer.setStatus(TransferStatus.LISTED);
        transfer.setAskingPrice(500.0);
        transfer.setListedAt(LocalDateTime.now().minusDays(1));

        when(transferRepository.findByPlayerId(10L)).thenReturn(Optional.of(transfer));
        when(teamRepository.findById(2L)).thenReturn(Optional.of(buyer));
        when(squadNumberAssigner.nextNumberForTeam(buyer, Position.ATT)).thenReturn(19);
        when(teamRepository.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(playerRepository.save(any(Player.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var dto = transferService.buyListedPlayer(10L, 2L, 450.0);

        assertEquals(550.0, buyer.getBudget());
        assertEquals(1_450.0, seller.getBudget());
        assertSame(buyer, player.getTeam());
        assertEquals(19, player.getSquadNumber());
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        assertEquals(450.0, transfer.getAgreedPrice());
        assertEquals(2L, dto.getBuyerTeamId());
        assertEquals(1L, dto.getSellerTeamId());
        verify(squadNumberAssigner).assignMissingNumbers(seller);
        verify(squadNumberAssigner).assignMissingNumbers(buyer);
    }

    @Test
    void removeFromTransferListRejectsWhenInterestAlreadyExists() {
        Team seller = new Team();
        seller.setId(1L);
        seller.setName("Seller FC");

        Player player = new Player();
        player.setId(10L);
        player.setTeam(seller);

        Transfer transfer = new Transfer();
        transfer.setPlayer(player);
        transfer.setSellerTeam(seller);
        transfer.setStatus(TransferStatus.LISTED);
        transfer.setInterestedTeams(new HashSet<>(Set.of("Buyer FC")));

        when(transferRepository.findByPlayerId(10L)).thenReturn(Optional.of(transfer));

        assertThrows(RuntimeException.class, () -> transferService.removeFromTransferList(10L, 1L));
        verify(transferRepository, never()).save(any(Transfer.class));
    }
}