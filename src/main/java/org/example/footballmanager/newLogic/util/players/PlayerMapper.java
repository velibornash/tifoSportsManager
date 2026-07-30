package org.example.footballmanager.newLogic.util.players;

import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.TrainingRepository;
import org.example.footballmanager.newLogic.repository.TransferRepository;
import org.springframework.stereotype.Service;

@Service
public class PlayerMapper {

    private final TrainingRepository trainingRepository;
    private final TransferRepository transferRepository;

    public PlayerMapper(TrainingRepository trainingRepository, TransferRepository transferRepository) {
        this.trainingRepository = trainingRepository;
        this.transferRepository = transferRepository;
    }

    public PlayerDTO toDTO(Player player) {
        PlayerDTO dto = new PlayerDTO();
        dto.setId(player.getId());
        dto.setName(player.getName());

/*        Optional<Training> training = trainingRepository.findByPlayerId(player.getId());
        training.ifPresent(t -> {
            dto.setTrainingFormation(t.getFormation());
            dto.setInAdvancedTraining(t.isAdvanced());
        });*/

/*        Optional<Transfer> transfer = transferRepository.findByPlayerId(player.getId());
        if (transfer.isPresent()) {
            dto.setOnTransferList(true);
            dto.setAskingPrice(transfer.get().getAskingPrice());
        } else {
            dto.setOnTransferList(false);
        }*/

        return dto;
    }
}