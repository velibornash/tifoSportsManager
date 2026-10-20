package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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

/**
 * The durable copy of a club's tactical work.
 *
 * <p><b>This file is the only copy of it.</b> Everything else — the {@code tactic} table — can be rebuilt
 * from the world, but a manager's hand-authored rules cannot, so this file is treated as the thing that
 * must never be lost and never be silently reshaped by a change to the application.
 *
 * <p><b>It changes shape, and it reads its old shape.</b> A club can hold several tactics now, so a club
 * is a list rather than one row. The previous format was one flat record per club, keyed by club name. An
 * old file is still read — as one club with one tactic, which is exactly what it was — and is written back
 * in the new shape only when the club is next saved. Reading the old shape is not a courtesy: the file in
 * this repository is the owner's real work, and a reader that understood only the new shape would report
 * an empty library and look like a working feature with nothing in it.
 *
 * <p><b>Keyed by club name, not by id.</b> That predates this class and is kept deliberately: an id would
 * not survive a database being rebuilt from the world, and a name does. It also means a club renamed since
 * the file was written starts a fresh entry, which is the lesser of the two problems.
 */
@Service
@Slf4j
public class TacticsProfileBackupService {

    private final ObjectMapper objectMapper;

    /**
     * An instance field, not a constant, so a test can point the backup somewhere temporary.
     *
     * <p>A test that exercised this path against {@code var/} would be reading and rewriting the
     * repository's own state to prove something about a method — and a {@code @SpringBootTest} that
     * reached the editor through HTTP has already written a fixture club into a tracked file. It is
     * injected, and the production path is unchanged.
     */
    private final Path backupPath;

    /** The production constructor. Annotated because the class has two, and Spring will not guess. */
    @org.springframework.beans.factory.annotation.Autowired
    public TacticsProfileBackupService(
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.tactics-backup-path:var/tactics-editor-profiles.json}") String backupPath) {
        this(objectMapper, Path.of(backupPath));
    }

    public TacticsProfileBackupService(ObjectMapper objectMapper, Path backupPath) {
        this.objectMapper = objectMapper;
        this.backupPath = backupPath;
    }

    /** One tactic, as stored. */
    @lombok.Data
    public static class TacticBackup {
        private String name;
        private String formation;
        private String style;
        private String rulesJson;
        private String setPiecesJson;
        private Long version;
        private java.time.LocalDateTime updatedAt;
    }

    /** One club, with all of its tactics. */
    @lombok.Data
    public static class ClubBackup {
        private String teamName;
        private String defaultTacticName;
        private List<TacticBackup> tactics = new ArrayList<>();

        public ClubBackup() { }

        public ClubBackup(String teamName) {
            this.teamName = teamName;
        }
    }

    /** What the file looked like before a club could hold more than one tactic. */
    @lombok.Data
    @lombok.NoArgsConstructor
    private static class LegacyEntry {
        private String teamName;
        private String formation;
        private String style;
        private String rulesJson;
        private String setPiecesJson;
        private Long version;
        private java.time.LocalDateTime updatedAt;

        TacticBackup asTactic(String name) {
            TacticBackup tactic = new TacticBackup();
            tactic.setName(name);
            tactic.setFormation(formation);
            tactic.setStyle(style);
            tactic.setRulesJson(rulesJson);
            tactic.setSetPiecesJson(setPiecesJson);
            tactic.setVersion(version);
            tactic.setUpdatedAt(updatedAt);
            return tactic;
        }
    }

    /**
     * Every club in the file, whichever shape it was written in.
     *
     * <p>Tries the current shape first and falls back to the old one. Not the other way round: a club
     * record has a {@code tactics} list, an old entry does not, and reading a new file as an old shape
     * would silently drop every tactic in it.
     */
    public synchronized List<ClubBackup> loadAll() {
        if (!Files.exists(backupPath)) {
            return List.of();
        }
        String raw;
        try {
            raw = Files.readString(backupPath);
        } catch (IOException ex) {
            log.warn("Failed to read tactics profile backup file {}", backupPath, ex);
            return List.of();
        }
        if (raw.isBlank()) {
            return List.of();
        }

        List<ClubBackup> clubs = tryRead(raw, new TypeReference<List<ClubBackup>>() {
        }, "club-and-tactics");
        if (clubs != null) {
            return clubs;
        }

        List<LegacyEntry> legacy = tryRead(raw, new TypeReference<List<LegacyEntry>>() {
        }, "single-profile");
        if (legacy == null) {
            return List.of();
        }

        List<ClubBackup> upgraded = new ArrayList<>(legacy.size());
        for (LegacyEntry entry : legacy) {
            if (entry.getTeamName() == null || entry.getTeamName().isBlank()) {
                continue;
            }
            ClubBackup club = new ClubBackup(entry.getTeamName());
            // A single tactic has no name in the old file, so it is named after its formation — which is
            // what a manager would have called it, and it keeps the (club, name) rule intact.
            String name = entry.getFormation() == null || entry.getFormation().isBlank()
                    ? "Default" : entry.getFormation();
            club.setTactics(new ArrayList<>(List.of(entry.asTactic(name))));
            club.setDefaultTacticName(name);
            upgraded.add(club);
        }
        log.info("Read {} club(s) from the previous single-profile shape of {}.", upgraded.size(), backupPath);
        return upgraded;
    }

    private <T> List<T> tryRead(String raw, TypeReference<List<T>> type, String shape) {
        try {
            List<T> parsed = objectMapper.readValue(raw, type);
            return parsed == null ? List.of() : parsed;
        } catch (Exception ex) {
            log.debug("Backup file is not in the {} shape: {}", shape, ex.getMessage());
            return null;
        }
    }

    public synchronized Optional<ClubBackup> findByTeamName(String teamName) {
        String normalized = normalizeTeamName(teamName);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return loadAll().stream()
                .filter(club -> normalized.equals(normalizeTeamName(club.getTeamName())))
                .findFirst();
    }

    /**
     * The tactic a club falls back to when nothing else is asked for.
     *
     * <p>The named default when there is one, otherwise the first tactic, otherwise nothing. The fallbacks
     * are for a file written by an older build or edited by hand — "the default is unset" must not read as
     * "this club has no tactics" when the file plainly has one.
     */
    public static Optional<TacticBackup> defaultTacticOf(ClubBackup club) {
        if (club == null || club.getTactics() == null || club.getTactics().isEmpty()) {
            return Optional.empty();
        }
        String wanted = club.getDefaultTacticName();
        if (wanted != null && !wanted.isBlank()) {
            for (TacticBackup tactic : club.getTactics()) {
                if (wanted.equals(tactic.getName())) {
                    return Optional.of(tactic);
                }
            }
        }
        return Optional.of(club.getTactics().get(0));
    }

    /**
     * Replaces a club's tactics in the file with the ones given.
     *
     * <p>Whole-club, not per-tactic, and by design: the file is a backup of a club's tactical work, and a
     * partial write that failed halfway would leave a club in the file that matches no state anybody can
     * reconstruct. If the club is not in the file it is added, which is what makes an editor save visible
     * in the backup on the first press rather than the second.
     */
    public synchronized void saveClub(String teamName, List<TacticBackup> clubTactics, String defaultTacticName) {
        String normalized = normalizeTeamName(teamName);
        if (normalized.isBlank() || clubTactics == null || clubTactics.isEmpty()) {
            return;
        }

        List<ClubBackup> clubs = new ArrayList<>(loadAll());
        clubs.removeIf(club -> normalized.equals(normalizeTeamName(club.getTeamName())));

        ClubBackup club = new ClubBackup(teamName);
        club.setTactics(new ArrayList<>(clubTactics));
        club.setDefaultTacticName(defaultTacticName);
        clubs.add(club);

        clubs.sort(Comparator.comparing(entry -> normalizeTeamName(entry.getTeamName())));
        writeClubs(clubs);
    }

    private void writeClubs(List<ClubBackup> clubs) {
        try {
            Path parent = backupPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    backupPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(clubs)
            );
        } catch (IOException ex) {
            log.warn("Failed to write tactics profile backup file {}", backupPath, ex);
        }
    }

    private String normalizeTeamName(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}