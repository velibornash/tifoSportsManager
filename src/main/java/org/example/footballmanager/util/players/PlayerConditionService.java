package org.example.footballmanager.util.players;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.InjuryEvent;
import org.example.footballmanager.repository.PlayerRepository;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class PlayerConditionService {

    private final PlayerRepository playerRepository;
    private final Random random = new Random();

    public PlayerConditionService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public void updatePlayerCondition(Player player, int minutesPlayed) {
        // Smanji formu na osnovu odigranih minuta
        double formReduction = minutesPlayed * 0.05; // 5% po minutu
        player.setForm(Math.max(1.0, player.getForm() - formReduction));
        playerRepository.save(player);
    }

    public void applyInjury(Player player, InjuryEvent injuryEvent) {
        // Generiši nasumičnu težinu povrede (1-4 nedelje)
        int recoveryWeeks = 1 + random.nextInt(4);
        player.setForm(Math.max(1.0, player.getForm() - 2.0)); // Povreda smanjuje formu
        // Oznaka povrede (možeš dodati polje u Player ako je potrebno)
        playerRepository.save(player);
    }

    public void recoverPlayer(Player player) {
        // Postepeno oporavljanje forme
        player.setForm(Math.min(10.0, player.getForm() + 0.5));
        playerRepository.save(player);
    }
}