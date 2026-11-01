package org.tifo.basketballmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.tifo.basketballmanager.model.BbInboxMessage;
import org.tifo.basketballmanager.repository.BbInboxRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class BbInboxService {

    private final BbInboxRepository repository;

    public BbInboxService(BbInboxRepository repository) {
        this.repository = repository;
    }

    public BbInboxMessage addMessage(Long teamId, String desk, String title, String body, Integer seasonYear) {
        BbInboxMessage msg = BbInboxMessage.builder()
                .teamId(teamId)
                .desk(desk)
                .title(title)
                .body(body)
                .seasonYear(seasonYear)
                .createdAt(LocalDateTime.now())
                .read(false)
                .build();
        return repository.save(msg);
    }

    public List<BbInboxMessage> getMessages(Long teamId) {
        return repository.findByTeamIdOrderByCreatedAtDesc(teamId);
    }

    public long unreadCount(Long teamId) {
        return repository.countByTeamIdAndReadFalse(teamId);
    }

    public void markRead(Long id) {
        repository.findById(id).ifPresent(m -> { m.setRead(true); repository.save(m); });
    }

    public void markAllRead(Long teamId) {
        repository.markAllRead(teamId);
    }
}
