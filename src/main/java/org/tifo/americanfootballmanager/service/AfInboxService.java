package org.tifo.americanfootballmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.tifo.americanfootballmanager.model.AfInboxMessage;
import org.tifo.americanfootballmanager.repository.AfInboxRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class AfInboxService {

    private final AfInboxRepository repository;

    public AfInboxService(AfInboxRepository repository) {
        this.repository = repository;
    }

    public AfInboxMessage addMessage(Long teamId, String desk, String title, String body, Integer seasonYear) {
        AfInboxMessage msg = AfInboxMessage.builder()
                .teamId(teamId).desk(desk).title(title).body(body)
                .seasonYear(seasonYear).createdAt(LocalDateTime.now()).read(false).build();
        return repository.save(msg);
    }

    public List<AfInboxMessage> getMessages(Long teamId) {
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
