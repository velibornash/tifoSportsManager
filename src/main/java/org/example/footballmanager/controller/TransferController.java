package org.example.footballmanager.controller;

import org.example.footballmanager.model.Transfer;
import org.example.footballmanager.service.TransferService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/list/{playerId}")
    public Transfer listPlayer(@PathVariable Long playerId, @RequestParam double price) {
        return transferService.listPlayerForTransfer(playerId, price);
    }

    @GetMapping
    public List<Transfer> getAllTransfers() {
        return transferService.getAllTransfers();
    }

    @PostMapping("/interest/{playerId}")
    public Transfer expressInterest(@PathVariable Long playerId, @RequestParam String club) {
        return transferService.addInterest(playerId, club);
    }

    @DeleteMapping("/remove/{playerId}")
    public void removeFromList(@PathVariable Long playerId) {
        transferService.removeFromTransferList(playerId);
    }
}