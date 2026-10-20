package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.newLogic.repository.TacticRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TeamTacticsProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    /**
     * How many tactics one club may hold (owner, 2026-10-11): *"imaš 10 taktika i 3 biram za meč"*.
     *
     * <p>Ten, because the manager chooses three of them for a match and a library without a ceiling makes
     * that choice meaningless. It is a constant here rather than a number in the page so the rule, the
     * refusal and the message that names it cannot disagree.
     */
    public static final int MAX_TACTICS_PER_CLUB = 10;

    private final TacticRepository tactics;
    private final TeamRepository teams;
    private final TacticsRulesProvider rulesProvider;

    /** The per-club tactical-editor profile, which carries the world-default flag. */
    private final TeamTacticsProfileRepository profiles;

    public List<Tactic> forTeam(Long teamId) {
        return tactics.findByTeamIdOrderByIdAsc(teamId);
    }

    /**
     * One of this club's tactics, or null.
     *
     * <p>Checked against the club rather than fetched by id alone. The two id spaces overlap — club 1's
     * tactic 1 and club 2's tactic 1 are both 1 — so a bare findById would hand one manager another's
     * shape, which is the exact shape of the P0 fixture defect this repository has been bitten by.
     */
    @Transactional(readOnly = true)
    public Tactic find(Long teamId, Long tacticId) {
        if (teamId == null || tacticId == null) {
            return null;
        }
        return tactics.findByIdAndTeamId(tacticId, teamId).orElse(null);
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

        Tactic tactic = tactics.findByTeamIdAndName(teamId, trimmedName).orElse(null);
        if (tactic == null) {
            // The cap, enforced here and not in the page. The owner: ten tactics a club, three of them
            // chosen for a match. A library without a ceiling makes the choice meaningless and puts the
            // whole lot through the editor every matchday.
            //
            // It is checked before the insert and not after it, so a refused save leaves no row behind.
            long held = tactics.findByTeamIdOrderByIdAsc(teamId).size();
            if (held >= MAX_TACTICS_PER_CLUB) {
                throw new IllegalArgumentException("This club already holds " + MAX_TACTICS_PER_CLUB
                        + " tactics. Delete one, or edit an existing tactic by saving over its name.");
            }
            tactic = new Tactic();
        }
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

    /**
     * Copies a tactic into a new one — the owner's *"create based on"*.
     *
     * <p>One operation rather than two, because they are the same thing: a new tactic that starts as a
     * copy of an existing one. The copy keeps the formation and the rules and is a <b>separate</b> row,
     * so editing it afterwards cannot reach the tactic it came from.
     *
     * @param newName the copy's name; must be free, because (club, name) is unique
     * @throws IllegalArgumentException if the source is not this club's, the name is taken, or the club
     *         already holds {@link #MAX_TACTICS_PER_CLUB} tactics
     */
    @Transactional
    public Tactic copy(Long teamId, Long tacticId, String newName) {
        Tactic source = tactics.findByIdAndTeamId(tacticId, teamId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Tactic " + tacticId + " does not belong to club " + teamId));

        String trimmedName = newName == null ? "" : newName.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("A copy needs a name");
        }
        if (tactics.findByTeamIdAndName(teamId, trimmedName).isPresent()) {
            throw new IllegalArgumentException("This club already has a tactic called " + trimmedName);
        }

        return save(teamId, trimmedName, source.getFormation(), source.getStyle(),
                source.getRulesJson(), source.getSetPiecesJson(), false);
    }

    /**
     * Tactics belonging to other clubs, for the Paste picker (T-MATCH-3c).
     *
     * <p>Bounded on purpose. There are 14,976 clubs and returning every tactic is a page nobody can use
     * and a query nobody should run on a request path. Defaults first, then a match on the tactic name or
     * the club name.
     *
     * @param needle lower-cased, may be empty; empty returns each other club's default tactic
     */
    @Transactional(readOnly = true)
    public List<Tactic> findForeign(Long teamId, String needle) {
        String q = needle == null ? "" : needle.trim().toLowerCase();
        return foreignCandidates(teamId).stream()
                .filter(t -> t.getName() != null)
                .filter(t -> q.isEmpty()
                        || t.getName().toLowerCase().contains(q)
                        || (t.getTeam() != null && t.getTeam().getName() != null
                            && t.getTeam().getName().toLowerCase().contains(q)))
                // Defaults first, then by name, so the same club's default is not at a different place
                // each time the list is opened.
                .sorted(java.util.Comparator
                        .comparing((Tactic t) -> t.isDefaultTactic() ? 0 : 1)
                        .thenComparing(Tactic::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(PASTE_RESULTS)
                .toList();
    }

    /** The cap on what the Paste picker returns. Enough to search, not enough to be a data dump. */
    public static final int PASTE_RESULTS = 60;

    private List<Tactic> foreignCandidates(Long teamId) {
        // Every other club's default tactic. That is a bounded, indexed-enough set and the useful one to
        // offer first - a club's default is the shape it actually plays. `tactics.findAll()` would be the
        // whole world's tactics and this runs on a request path.
        return tactics.findAll().stream()
                .filter(Tactic::isDefaultTactic)
                .filter(t -> t.getTeam() != null && !teamId.equals(t.getTeam().getId()))
                .toList();
    }

    /**
     * Paste: takes another club's tactic into this one (Sokker's Paste, T-MATCH-3c).
     *
     * <p><b>Distinct from {@link #copy}, and the distinction is the point.</b> Copy duplicates one of
     * <em>your</em> tactics and refuses anything else, which is the right default: an endpoint that
     * silently accepted a stranger's id would let a manager import by accident. Paste is the deliberate
     * door, named as such, and it is the only way across.
     *
     * <p>The tactic is <b>copied, not referenced</b>, exactly as Copy does. Referencing would mean one
     * edit on the other club's pitch changing the tactic this club is playing, which is not something a
     * manager would expect from a button called Paste and could not undo.
     *
     * <p>Reading another club's tactic is a feature here — it is what Sokker's gallery is for and the
     * owner asked for it — so it is not gated. It is <em>named</em>, so it is not an accident.
     *
     * @param sourceTacticId any club's tactic
     * @param newName        the name it takes here; a pasted tactic defaults to the source's name
     * @throws IllegalArgumentException if the source does not exist, or this club is at the cap
     */
    @Transactional
    public Tactic paste(Long teamId, Long sourceTacticId, String newName) {
        Tactic source = tactics.findById(sourceTacticId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Tactic " + sourceTacticId + " does not exist"));

        // Refuse a paste of this club's own tactic: that is Copy, and sending it here would take the
        // same-name check and fail with a message about a duplicate rather than about the right button.
        if (source.getTeam() != null && teamId.equals(source.getTeam().getId())) {
            throw new IllegalArgumentException(
                    "That tactic is already yours. Use Copy to duplicate it.");
        }

        String trimmedName = newName == null || newName.isBlank()
                ? source.getName()
                : newName.trim();
        if (trimmedName == null || trimmedName.isBlank()) {
            throw new IllegalArgumentException("A pasted tactic needs a name");
        }
        if (tactics.findByTeamIdAndName(teamId, trimmedName).isPresent()) {
            throw new IllegalArgumentException("This club already has a tactic called " + trimmedName);
        }

        return save(teamId, trimmedName, source.getFormation(), source.getStyle(),
                source.getRulesJson(), source.getSetPiecesJson(), false);
    }

    /**
     * Mirrors a tactic: the same shape playing the other way.
     *
     * <p>Sokker's Mirror, and it is a real transformation rather than a copy. Cells are
     * {@code CELL_<row>_<col>}, three rows deep and three across, so the mirror is the column and
     * nothing else: {@code CELL_1_1} and {@code CELL_1_3} exchange, and {@code CELL_r_2} — the middle
     * column — stands where it is, because its mirror image is itself.
     *
     * <p>Mirroring creates a <b>new</b> tactic and never edits the source. A button that quietly rewrote
     * the tactic it was taken from would lose the manager's original, and the undo would be the delete.
     *
     * @param newName the mirrored tactic's name
     * @throws IllegalArgumentException if the source is not this club's, or the club is at the cap
     */
    @Transactional
    public Tactic mirror(Long teamId, Long tacticId) {
        Tactic source = tactics.findByIdAndTeamId(tacticId, teamId).orElseThrow(
                () -> new IllegalArgumentException(
                        "Tactic " + tacticId + " does not belong to club " + teamId));

        long held = tactics.findByTeamIdOrderByIdAsc(teamId).size();
        if (held >= MAX_TACTICS_PER_CLUB) {
            throw new IllegalArgumentException("This club already holds " + MAX_TACTICS_PER_CLUB
                    + " tactics, so there is no room for a mirrored copy.");
        }

        // Unique on (club, name), so a mirrored copy cannot collide with its own source. The numbering
        // is readable and says what it is, rather than leaving the manager to invent a name.
        String mirroredName = nextFreeMirrorName(teamId, source.getName());
        return save(teamId, mirroredName, source.getFormation(), source.getStyle(),
                mirrorRules(source.getRulesJson()), source.getSetPiecesJson(), false);
    }

    /** "4-4-2 wide", then "4-4-2 wide (mirrored)", then "(mirrored 2)". */
    private String nextFreeMirrorName(Long teamId, String sourceName) {
        String base = sourceName + " (mirrored)";
        String candidate = base;
        int n = 2;
        while (tactics.findByTeamIdAndName(teamId, candidate).isPresent()) {
            candidate = sourceName + " (mirrored " + n++ + ")";
        }
        return candidate;
    }

    /**
     * The last column of the editor grid, which is what a mirror reflects about.
     *
     * <p>The playable grid is <b>six columns wide and seven rows deep</b>, 0-based: {@code TacticsRules
     * .ballStateKey} clamps the column to 0–5 with the touchlines outside, and {@code parseCell} turns
     * {@code CELL_r_c} into the physical centre {@code (r + 1.5, c + 1.5)}. So column 0 is the left
     * touchline and column 5 the right.
     *
     * <p>Six columns is an <b>even</b> width, which has a consequence worth stating: there is no centre
     * column, and every column pairs with a different one. An earlier version of this reflected about 3
     * and left the middle column alone, which is what a 7-wide pitch would want and is wrong here — it
     * mapped 1↔3 instead of 1↔4 and silently left columns 0 and 5 exactly where they were.
     */
    private static final int LAST_EDITOR_COLUMN = 5;

    /**
     * Swaps the column of every {@code targetCellKey}, leaving rows and every other field alone.
     *
     * <p><b>Rows are not mirrored.</b> This is a left-right flip — the same shape playing the other way
     * across the pitch — not a goal-to-goal one. Flipping rows as well would produce a tactic that
     * defends where it attacks.
     *
     * <p>Written as a text rewrite rather than a parse-and-reserialise because these rules are authored
     * JSON a manager may have written by hand, and a round trip through a parser would reformat every
     * line of it. Only the cell key is touched, and only when it names a column this grid actually has.
     */
    static String mirrorRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return rulesJson;
        }
        return mirrorCorners(mirrorCells(rulesJson));
    }

    /**
     * Swaps the column of every {@code CELL_r_c}, leaving the row alone.
     *
     * <p>Six columns is an <b>even</b> width, so there is no centre column and every column pairs with a
     * different one: 0↔5, 1↔4, 2↔3. An earlier version reflected about column 3 and left anything
     * outside 1–3 untouched, which mapped 1↔3 instead of 1↔4 and silently kept columns 0 and 5 as they
     * were. Its test passed because every cell in it was in the three columns that version happened to
     * handle.
     */
    private static String mirrorCells(String json) {
        java.util.regex.Pattern cell = java.util.regex.Pattern.compile("CELL_(\\d)_(\\d)");
        java.util.regex.Matcher matcher = cell.matcher(json);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int row = Integer.parseInt(matcher.group(1));
            int column = Integer.parseInt(matcher.group(2));
            // A column outside the vocabulary is left exactly as it is. The editor only ever offers
            // 0–5, so this is a guard against hand-edited JSON rather than a rule of the game.
            String mirrored = column < 0 || column > LAST_EDITOR_COLUMN
                    ? matcher.group(0)
                    : "CELL_" + row + "_" + (LAST_EDITOR_COLUMN - column);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(mirrored));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Swaps the four corner states, left for right.
     *
     * <p>Corners are not cells — {@code FormationSlotCatalog} lists them as named ball states beside the
     * grid — and they carry the side in the name. A rule written for the attacking left corner mirrors
     * to the attacking <em>right</em> corner, and mirroring only the cells would have quietly left every
     * corner rule pointing at the corner the manager was deliberately not using.
     */
    private static String mirrorCorners(String json) {
        String out = json;
        for (String phase : new String[]{"ATTACK", "DEFEND"}) {
            out = out.replace(phase + "_LEFT_CORNER", phase + "\u0000CORNER")
                    .replace(phase + "_RIGHT_CORNER", phase + "_LEFT_CORNER")
                    .replace(phase + "\u0000CORNER", phase + "_RIGHT_CORNER");
        }
        return out;
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

    /**
     * Designates one club's tactical profile as the world's starting tactic.
     *
     * <p>Owner, 2026-10-18: <i>"da ova opcija snimi taktiku koju ću napraviti za Omladinac i da ta
     * taktika tj ti tactical rules preživi Reset db i da se stalno učitavaju nazad"</i>.
     *
     * <p><b>What the button did instead.</b> {@code saveDefaultTactics} in {@code admin-view.js} read the
     * club's saved editor profile and PUT it straight back, field for field. It was a no-op that reported
     * success, it stored nothing new, and there was no concept of a world default for it to store — so
     * "give every club a default tactic" was left to guess, and it guessed with
     * {@code findAll().stream().findFirst()}, which is whichever row the database ordered first.
     *
     * <p><b>Exactly one row carries the flag.</b> Setting it clears it everywhere else first, inside the
     * same transaction, so there is never a moment with two world defaults or none. A world-wide setting
     * that can be ambiguous is not a setting.
     *
     * <p><b>Why the content survives a reset on its own.</b> The profile's rules were already written to
     * {@code var/tactics-editor-profiles.json} by {@link TacticsProfileBackupService} and restored by
     * {@code DatabaseInitializer}; the flag says which of those profiles is <em>the</em> one, and lives
     * on a table the football-data reset does not clear.
     *
     * @param teamId the club whose editor profile becomes the world default
     * @return the club's name and formation, so the caller can report what it actually designated
     *         rather than what it hoped for
     * @throws IllegalStateException if that club has no profile — the honest answer, because silently
     *                               doing nothing here is what produced this bug
     */
    @Transactional
    public Map<String, Object> markProfileAsWorldDefault(Long teamId) {
        TeamTacticsProfile profile = profiles.findByTeamId(teamId)
                .orElseThrow(() -> new IllegalStateException(
                        "That club has no tactical editor profile yet. Build one in the Tactic Editor "
                                + "and save it, then press this again."));

        // Clear first, then set: one transaction, so there is no window with two or zero.
        for (TeamTacticsProfile other : profiles.findAll()) {
            if (other.isWorldDefault() && !other.getId().equals(profile.getId())) {
                other.setWorldDefault(false);
                profiles.save(other);
            }
        }
        profile.setWorldDefault(true);
        profiles.save(profile);

        // Anything that already resolved a default for this club is now stale.
        rulesProvider.evictAll();

        log.info("World default tactic is now '{}' ({}) from club '{}'.",
                profile.getFormation(), profile.getStyle(),
                profile.getTeam() == null ? "?" : profile.getTeam().getName());
        return Map.of(
                "teamId", teamId,
                "formation", profile.getFormation(),
                "style", profile.getStyle(),
                "team", profile.getTeam() == null ? "" : profile.getTeam().getName());
    }

    /**
     * The profile to copy when giving every club a default tactic.
     *
     * <p>The owner-designated world default if there is one. Otherwise a deterministic fallback —
     * lowest id — rather than {@code findFirst()}, which is whatever the database ordered first and can
     * differ between two databases holding the same profiles.
     *
     * @return the template, or empty when nobody has authored anything at all, in which case nothing
     *         should be written: rules nobody authored, pushed to 14,880 clubs under a button labelled
     *         "give every club a default", is the quiet authorship this whole feature exists to avoid
     */
    @Transactional(readOnly = true)
    public Optional<TeamTacticsProfile> worldDefaultProfile() {
        List<TeamTacticsProfile> designated = profiles.findByWorldDefaultTrue();
        if (designated.size() > 1) {
            throw new IllegalStateException(
                    designated.size() + " profiles are marked as the world default. That should be "
                            + "impossible — markProfileAsWorldDefault clears the flag first — so the "
                            + "data is wrong rather than the code. Refusing rather than guessing which "
                            + "one the owner meant.");
        }
        if (designated.size() == 1) return Optional.of(designated.get(0));
        return profiles.findAll().stream().min(Comparator.comparing(TeamTacticsProfile::getId));
    }

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