package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.repository.TacticRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A club's tactics: more than one, exactly one of them the default.
 *
 * <p><b>The invariant this holds.</b> A club may hold any number of tactics and exactly one of them is
 * its default. That is the whole of it, and everything downstream — the per-match selection that is being
 * built next — reads the default when the manager has chosen nothing, because a match still has to be
 * playable and "nobody configured this club" is the ordinary state of a club nobody has touched.
 *
 * <p><b>Why the one-default rule is here and not in the schema.</b> It is a partial unique index in
 * PostgreSQL, which the test database is not, so a constraint one engine honours and another ignores would
 * be a constraint nobody can rely on. It is enforced in the one place that writes the flag, in the same
 * transaction, so it cannot be half-applied.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TacticLibraryService {

    private final TacticRepository tactics;
    private final TeamRepository teams;
    private final TacticsRulesProvider rulesProvider;

    public List<Tactic> forTeam(Long teamId) {
        return tactics.findByTeamIdOrderByIdAsc(teamId);
    }

    public Optional<Tactic> defaultFor(Long teamId) {
        return tactics.findDefaultsForTeam(teamId).stream().findFirst();
    }

    /**
     * Saves a tactic, and makes it the club's default when asked.
     *
     * <p>A save keyed by name rather than by id: the manager identifies a tactic by what they called it,
     * and the screen sends the name. The unique constraint on (club, name) is what stops two tactics
     * ending up with the same label, so this is a lookup and an insert or an update, not a blind write.
     */
    @Transactional
    public Tactic save(Long teamId, String name, String formation, String style,
                       String rulesJson, String setPiecesJson, boolean makeDefault) {
        Team team = teams.findById(teamId).orElseThrow(
                () -> new IllegalArgumentException("Unknown club " + teamId));

        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("A tactic needs a name");
        }
        String trimmedFormation = formation == null ? "" : formation.trim();
        if (trimmedFormation.isEmpty()) {
            throw new IllegalArgumentException("A tactic needs a formation");
        }

        Tactic tactic = tactics.findByTeamIdAndName(teamId, trimmedName).orElseGet(Tactic::new);
        tactic.setTeam(team);
        tactic.setName(trimmedName);
        tactic.setFormation(trimmedFormation);
        tactic.setStyle(style == null ? "" : style.trim());
        tactic.setRulesJson(rulesJson);
        tactic.setSetPiecesJson(setPiecesJson);
        tactic.setVersion(tactic.getVersion() == null ? 1L : tactic.getVersion() + 1L);
        tactic.setUpdatedAt(LocalDateTime.now());

        // A club's first tactic is its default whatever the caller asked for: a club with no default is a
        // state the game has to defend against everywhere else, so it is not created in the first place.
        boolean willBeDefault = makeDefault || tactics.findByTeamIdOrderByIdAsc(teamId).isEmpty();
        if (willBeDefault) {
            clearOtherDefaults(teamId, tactic.getId());
            tactic.setDefaultTactic(true);
        }

        Tactic saved = tactics.save(tactic);
        if (willBeDefault) {
            // The club's default may have changed, so its resolution must not be cached.
            rulesProvider.evict(teamId);
        } else {
            rulesProvider.evict(teamId, saved.getId());
        }
        return saved;
    }

    /** Promotes one of a club's tactics to be its default, demoting whatever held it. */
    @Transactional
    public Tactic makeDefault(Long teamId, Long tacticId) {
        Tactic tactic = tactics.findByIdAndTeamId(tacticId, teamId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tactic " + tacticId + " does not belong to club " + teamId));

        clearOtherDefaults(teamId, tacticId);
        tactic.setDefaultTactic(true);
        tactic.setUpdatedAt(LocalDateTime.now());
        Tactic saved = tactics.save(tactic);
        // Which tactic this club defaults to just changed; its parsed rules may be cached under the old one.
        rulesProvider.evict(teamId);
        return saved;
    }

    @Transactional
    public void delete(Long teamId, Long tacticId) {
        Tactic tactic = tactics.findByIdAndTeamId(tacticId, teamId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Tactic " + tacticId + " does not belong to club " + teamId));

        boolean wasDefault = tactic.isDefaultTactic();
        tactics.delete(tactic);
        rulesProvider.evict(teamId);

        // A club left with no default still has to play, so the oldest survivor is promoted rather than
        // the club silently dropping to the bundled fallback and nobody noticing why.
        if (wasDefault && tactics.findDefaultsForTeam(teamId).isEmpty()) {
            List<Tactic> remaining = tactics.findByTeamIdOrderByIdAsc(teamId);
            if (!remaining.isEmpty()) {
                Tactic promoted = remaining.get(0);
                promoted.setDefaultTactic(true);
                tactics.save(promoted);
                rulesProvider.evict(teamId);
            }
        }
    }

    /** Every other tactic of this club loses the flag. The one being saved is excluded by id. */
    private void clearOtherDefaults(Long teamId, Long keepTacticId) {
        for (Tactic other : tactics.findByTeamIdOrderByIdAsc(teamId)) {
            boolean isTheOneBeingSaved = keepTacticId != null && keepTacticId.equals(other.getId());
            if (!isTheOneBeingSaved && other.isDefaultTactic()) {
                other.setDefaultTactic(false);
                tactics.save(other);
            }
        }
    }

    /**
     * Gives every club that has none a default tactic, from a template.
     *
     * <p><b>Owner ruling, 2026-10-10: the current tactical profile is the default for all teams.</b> The
     * world held one profile against 14,723 clubs, so every club but one was playing the bundled
     * fallback and had nothing to select from a library. This makes that profile the shape every club
     * starts from.
     *
     * <p><b>Idempotent, because it will be pressed more than once.</b> A club that already holds any
     * tactic is skipped, so a second run reports "nothing to do" instead of producing a second copy of
     * everything. The (club, name) unique constraint backs that up at the database level.
     *
     * <p><b>An explicit action, never a boot step.</b> This writes a row per club. AGENTS.md is explicit
     * that boot seeds nothing and that world building happens on admin buttons, and that is the right rule
     * here for a concrete reason: this is the owner's decision being applied to their world, and it should
     * be visible, countable and repeatable rather than something that happened to somebody on a restart.
     */
    @Transactional
    public SeedReport giveEveryClubADefaultTactic(String templateName, String templateFormation,
                                                 String templateStyle, String templateRulesJson,
                                                 String templateSetPiecesJson) {
        List<Team> clubs = teams.findAll();
        int created = 0;
        int skipped = 0;

        for (Team club : clubs) {
            if (!tactics.findByTeamIdOrderByIdAsc(club.getId()).isEmpty()) {
                skipped++;
                continue;
            }
            Tactic tactic = new Tactic();
            tactic.setTeam(club);
            tactic.setName(templateName);
            tactic.setFormation(templateFormation);
            tactic.setStyle(templateStyle);
            tactic.setRulesJson(templateRulesJson);
            tactic.setSetPiecesJson(templateSetPiecesJson);
            tactic.setDefaultTactic(true);
            tactic.setVersion(1L);
            tactic.setUpdatedAt(LocalDateTime.now());
            tactics.save(tactic);
            created++;
        }

        // Every club's default just came into existence; none of them were cached before, and leaving a
        // stale resolution would mean the next match per club reads the fallback anyway.
        rulesProvider.evictAll();
        log.info("Default tactic created for {} club(s); {} already had tactics.", created, skipped);
        return new SeedReport(created, skipped, clubs.size());
    }

    /**
     * What one press of the button did.
     *
     * @param created clubs that had no tactic and now have a default
     * @param skipped clubs that already held a tactic and were left alone — the number that makes a second
     *                 press of the button report zero rather than duplicating the world
     */
    public record SeedReport(int created, int skipped, long totalClubs) { }

    /** A club's tactics with their default marked, for the library list. */
    public List<TacticView> view(Long teamId) {
        List<Tactic> all = tactics.findByTeamIdOrderByIdAsc(teamId);
        List<TacticView> views = new ArrayList<>(all.size());
        for (Tactic tactic : all) {
            views.add(new TacticView(tactic.getId(), tactic.getName(), tactic.getFormation(),
                    tactic.getStyle(), tactic.isDefaultTactic(), tactic.getVersion(),
                    tactic.getUpdatedAt()));
        }
        return views;
    }

    public record TacticView(Long id, String name, String formation, String style,
                             boolean isDefault, Long version, LocalDateTime updatedAt) { }
}