package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TacticsProfileBackupService {

    private static final Path BACKUP_PATH = Path.of("var", "tactics-editor-profiles.json");

    private final ObjectMapper objectMapper;

    public synchronized List<TacticsProfileBackupEntry> loadAll() {
        if (!Files.exists(BACKUP_PATH)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(BACKUP_PATH), new TypeReference<List<TacticsProfileBackupEntry>>() {});
        } catch (Exception ex) {
            log.warn("Failed to load tactics profile backup file {}", BACKUP_PATH, ex);
            return List.of();
        }
    }

    public synchronized Optional<TacticsProfileBackupEntry> findByTeamName(String teamName) {
        String normalized = normalizeTeamName(teamName);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return loadAll().stream()
                .filter(entry -> normalized.equals(normalizeTeamName(entry.getTeamName())))
                .findFirst();
    }

    public synchronized void saveOrUpdate(Team team, TeamTacticsProfile profile) {
        if (team == null || profile == null) {
            return;
        }
        String normalized = normalizeTeamName(team.getName());
        if (normalized.isBlank()) {
            return;
        }

        List<TacticsProfileBackupEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(entry -> normalized.equals(normalizeTeamName(entry.getTeamName())));
        entries.add(new TacticsProfileBackupEntry(
                team.getName(),
                profile.getFormation(),
                profile.getStyle(),
                profile.getRulesJson(),
                profile.getSetPiecesJson(),
                profile.getVersion(),
                profile.getUpdatedAt()
        ));
        entries.sort(Comparator.comparing(entry -> normalizeTeamName(entry.getTeamName())));
        writeEntries(entries);
    }

    private void writeEntries(List<TacticsProfileBackupEntry> entries) {
        try {
            Path parent = BACKUP_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    BACKUP_PATH,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries)
            );
        } catch (IOException ex) {
            log.warn("Failed to write tactics profile backup file {}", BACKUP_PATH, ex);
        }
    }

    private String normalizeTeamName(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
