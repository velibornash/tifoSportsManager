package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class TacticsProfileBackupService {

    private static final Path DEFAULT_BACKUP_PATH = Path.of("var", "tactics-editor-profiles.json");

    private final ObjectMapper objectMapper;

    /**
     * An instance field, not the constant it was, so a test can point the backup somewhere temporary.
     *
     * <p>This file is the <b>only</b> durable copy of a club's tactical editor work, and the restore path
     * now reads it — so a test that exercised that path against {@code var/} would be reading and
     * rewriting the repository's own state to prove something about a method. It is injected instead, and
     * the production path is unchanged.
     */
    private final Path backupPath;

    /** The production constructor. Annotated because the class now has two, and Spring will not guess. */
    @org.springframework.beans.factory.annotation.Autowired
    public TacticsProfileBackupService(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_BACKUP_PATH);
    }

    public TacticsProfileBackupService(ObjectMapper objectMapper, Path backupPath) {
        this.objectMapper = objectMapper;
        this.backupPath = backupPath;
    }

    public synchronized List<TacticsProfileBackupEntry> loadAll() {
        if (!Files.exists(backupPath)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(backupPath), new TypeReference<List<TacticsProfileBackupEntry>>() {});
        } catch (Exception ex) {
            log.warn("Failed to load tactics profile backup file {}", backupPath, ex);
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
            Path parent = backupPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    backupPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries)
            );
        } catch (IOException ex) {
            log.warn("Failed to write tactics profile backup file {}", backupPath, ex);
        }
    }

    private String normalizeTeamName(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
