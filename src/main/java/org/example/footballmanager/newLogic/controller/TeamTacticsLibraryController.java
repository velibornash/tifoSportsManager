package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.service.ClubOwnershipLinker;
import org.example.footballmanager.newLogic.service.TacticLibraryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A club's tactic library (owner, 2026-10-11).
 *
 * <p>The owner: *"imaš 10 taktika i 3 biram za meč, bilo koja koju promeniš, ima copy paste mirror"*.
 * Ten of them per club, three chosen for a match, and the operations a library needs — which is this
 * controller.
 *
 * <p>It is separate from {@code /teams/{id}/tactics-editor} on purpose. The editor edits <b>one</b>
 * tactic's shape; this is the shelf those tactics sit on, and a screen that does both cannot answer
 * "which of my ten?" before it can answer "what does this one look like?".
 *
 * <p><b>Every refusal names the cap.</b> The ten is the owner's number and it lives in
 * {@link TacticLibraryService#MAX_TACTICS_PER_CLUB}; the page shows it, and the refusal repeats it, so a
 * manager is never left guessing why an eleventh tactic would not save.
 */
@RestController
@RequestMapping("/teams/{teamId}/tactics")
public class TeamTacticsLibraryController {

    private final TacticLibraryService library;
    private final ClubOwnershipLinker ownership;

    public TeamTacticsLibraryController(TacticLibraryService library, ClubOwnershipLinker ownership) {
        this.library = library;
        this.ownership = ownership;
    }

    /**
     * Every tactic the club holds, with the cap.
     *
     * <p>The cap is in the payload rather than only in the refusal, so the page can disable its "new
     * tactic" button with the reason visible instead of letting the manager fill in a form that will not
     * save.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable Long teamId) {
        List<TacticLibraryService.TacticView> held = library.view(teamId);
        // A club-level fact, decided once and repeated on every row. It was `i + 1 >= MAX` at first,
        // which made only the last row true - so a page reading row 0 would think there was room, and
        // the manager would fill in a form that could not save.
        boolean atCap = held.size() >= TacticLibraryService.MAX_TACTICS_PER_CLUB;
        List<Map<String, Object>> out = new java.util.ArrayList<>(held.size());
        for (int i = 0; i < held.size(); i++) {
            TacticLibraryService.TacticView v = held.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.id());
            row.put("name", v.name());
            row.put("formation", v.formation());
            row.put("style", v.style());
            row.put("isDefault", v.isDefault());
            row.put("position", i);
            row.put("updatedAt", v.updatedAt());
            // Carried per row, not only from /limits, so a list rendered from this alone can still say
            // "10 of 10" without a second round trip.
            row.put("maxTactics", TacticLibraryService.MAX_TACTICS_PER_CLUB);
            row.put("atCap", atCap);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * One tactic in full — the payload the editor loads into its pitch and rules grid.
     *
     * <p>Separate from the list because the list is a shelf: id, name, shape, and whether it is the
     * default. The editor needs the movement rules and the set pieces, which are large and which no list
     * of ten should carry.
     */
    @GetMapping("/{tacticId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long teamId,
                                                  @PathVariable Long tacticId) {
        Tactic tactic = library.find(teamId, tacticId);
        if (tactic == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", tactic.getId());
        out.put("name", tactic.getName());
        out.put("formation", tactic.getFormation());
        out.put("style", tactic.getStyle());
        out.put("rulesJson", tactic.getRulesJson());
        out.put("setPiecesJson", tactic.getSetPiecesJson());
        out.put("isDefault", tactic.isDefaultTactic());
        out.put("version", tactic.getVersion());
        return ResponseEntity.ok(out);
    }

    /** The cap, on its own, so the editor does not have to infer it from a list it may not be showing. */
    @GetMapping("/limits")
    public ResponseEntity<Map<String, Object>> limits() {
        return ResponseEntity.ok(Map.of(
                "maxTacticsPerClub", TacticLibraryService.MAX_TACTICS_PER_CLUB,
                "perMatch", 3));
    }

    /**
     * Saves a tactic by name, or edits the one that already has that name.
     *
     * <p>Keyed by name because the manager identifies a tactic by what they called it. At the cap this
     * still edits: a manager working on his tenth tactic must not be told the library is full.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable Long teamId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            Tactic saved = library.save(teamId, str(body.get("name")), str(body.get("formation")),
                    str(body.get("style")), str(body.get("rulesJson")),
                    str(body.get("setPiecesJson")),
                    Boolean.TRUE.equals(body.get("makeDefault")));
            return ResponseEntity.ok(TacticSummary.of(saved, library, teamId).toMap());
        } catch (IllegalArgumentException e) {
            // The cap, the empty name, a club that does not exist: all the manager's own doing, and all
            // answered in words rather than as a stack trace.
            return ResponseEntity.badRequest().body(Map.of("error", "TACTIC_REFUSED",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /** Copy — the owner's "create based on". */
    @PostMapping("/{tacticId}/copy")
    public ResponseEntity<Map<String, Object>> copy(
            @PathVariable Long teamId,
            @PathVariable Long tacticId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String name = body == null ? null : str(body.get("name"));
        try {
            return ResponseEntity.ok(TacticSummary.of(
                    library.copy(teamId, tacticId, name == null || name.isBlank() ? null : name),
                    library, teamId).toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "TACTIC_REFUSED",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * The tactics on other clubs that can be pasted into this one (T-MATCH-3c).
     *
     * <p>A search rather than the whole world: 14,976 clubs hold tactics and a list of all of them is a
     * page nobody can use. An empty query returns the clubs' defaults, which is a usable starting point,
     * and a query narrows on tactic name or club name.
     *
     * <p>This club's own tactics are excluded — pasting one of those is Copy, and listing them here would
     * invite the more powerful button for the same job.
     */
    @GetMapping("/pasteable")
    public ResponseEntity<List<Map<String, Object>>> pasteable(
            @PathVariable Long teamId,
            @RequestParam(required = false) String query,
            @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String needle = query == null ? "" : query.trim().toLowerCase();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Tactic tactic : library.findForeign(teamId, needle)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tactic.getId());
            row.put("name", tactic.getName());
            row.put("formation", tactic.getFormation());
            row.put("style", tactic.getStyle());
            row.put("clubId", tactic.getTeam() == null ? null : tactic.getTeam().getId());
            row.put("clubName", tactic.getTeam() == null ? null : tactic.getTeam().getName());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** Paste: another club's tactic becomes one of this club's. The deliberate door across clubs. */
    @PostMapping("/paste")
    public ResponseEntity<Map<String, Object>> paste(
            @PathVariable Long teamId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Long sourceId = body == null ? null : longOf(body.get("tacticId"));
        if (sourceId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "NO_SUCH_TACTIC",
                    "message", "Paste needs the id of the tactic you are taking."));
        }
        try {
            return ResponseEntity.ok(TacticSummary.of(
                    library.paste(teamId, sourceId, body.get("name") == null ? null : str(body.get("name"))),
                    library, teamId).toMap());
        } catch (IllegalArgumentException e) {
            // The service refuses with the reason and the reason is the message: the cap, the duplicate
            // name, or the "that one is already yours, use Copy" case.
            return ResponseEntity.badRequest().body(Map.of("error", "TACTIC_REFUSED",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private static Long longOf(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    /** Mirror: the same shape playing the other way, as a new tactic. */
    @PostMapping("/{tacticId}/mirror")
    public ResponseEntity<Map<String, Object>> mirror(
            @PathVariable Long teamId,
            @PathVariable Long tacticId,
            @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return ResponseEntity.ok(TacticSummary.of(library.mirror(teamId, tacticId), library, teamId).toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "TACTIC_REFUSED",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/{tacticId}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long teamId,
            @PathVariable Long tacticId,
            @AuthenticationPrincipal User user) {
        if (!mayManage(user, teamId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            library.delete(teamId, tacticId);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "TACTIC_REFUSED",
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private boolean mayManage(User user, Long teamId) {
        Long owned = ownership.clubIdOf(user);
        return owned != null && owned.equals(teamId);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** One tactic as the page needs it, with its position in the library and whether it is the default. */
    // Named TacticSummary, not TacticView: the service already has one of those, and two records of the
    // same name in the same call site is the kind of clash that compiles until it does not.
    private record TacticSummary(Long id, String name, String formation, String style,
                                 boolean isDefault, int position, int maxTactics) {

        static TacticSummary of(Tactic tactic, TacticLibraryService library, Long teamId) {
            List<Tactic> all = library.forTeam(teamId);
            int position = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(tactic.getId())) {
                    position = i;
                    break;
                }
            }
            return new TacticSummary(tactic.getId(), tactic.getName(), tactic.getFormation(),
                    tactic.getStyle(), tactic.isDefaultTactic(), position,
                    TacticLibraryService.MAX_TACTICS_PER_CLUB);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("name", name);
            out.put("formation", formation);
            out.put("style", style);
            out.put("isDefault", isDefault);
            out.put("position", position);
            out.put("maxTactics", maxTactics);
            return out;
        }
    }
}