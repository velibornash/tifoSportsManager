package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global search: managers, clubs and players, anywhere in the world.
 *
 * <p><b>Owner, 2026-10-14:</b> *"Search mora biti globalan tool ... niti mora da se koristi samo za
 * prijateljske ... stavio bih dugme sa lupica ikonicom posle dugmeta notification u headeru, kad se
 * klikne otvori se search stranica u kojoj mogu da se pretrazuju useri, klubovi ili igraci."*
 *
 * <p>It is deliberately its own endpoint rather than the friendly opponent picker wearing a hat. That
 * picker answers a narrower question — *which club can I ask for a friendly* — and it answers it by
 * excluding national sides and the viewer's own club. A search does neither: it is a way of finding
 * something in the world, and a manager looking for a club is not being asked to send it a friendly.
 *
 * <p>The world holds roughly 14,880 clubs and more than 31,000 players, so each of the three queries is
 * {@code LIMIT}ed inside the database and ordered by name length — a partial "Kikinda" then puts
 * {@code SK Kikinda} before {@code OFK Kikinda 1912}, which is what somebody typing half a name means.
 *
 * <p>National sides are returned, unlike in the friendly picker. They are clubs in the data model and a
 * manager may legitimately want to look at one; the row says which kind it is, so nothing is passed off
 * as something it is not.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final TeamRepository teams;
    private final UserRepository users;
    private final PlayerRepository players;

    public SearchController(TeamRepository teams, UserRepository users, PlayerRepository players) {
        this.teams = teams;
        this.users = users;
        this.players = players;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> search(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) String type,
                                                            @RequestParam(defaultValue = "40") int limit) {
        String fragment = q == null ? "" : q.trim().toLowerCase();
        // An empty box returns nothing. This is a search, not a browse list, and 45,000 rows is not
        // something anybody reads - it is also the only answer that cannot be wrong by being truncated.
        if (fragment.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Owner: "search treba da moze da se trazi club, user ili igrac". Absent or unrecognised means
        // all three, so a caller written before the filter existed keeps the behaviour it had.
        boolean all = type == null || type.isBlank() || "ALL".equalsIgnoreCase(type.trim());
        String kind = type == null ? "ALL" : type.trim().toUpperCase();
        boolean wantTeam = all || "TEAM".equalsIgnoreCase(kind) || "CLUB".equalsIgnoreCase(kind);
        boolean wantUser = all || "USER".equalsIgnoreCase(kind) || "MANAGER".equalsIgnoreCase(kind);
        boolean wantPlayer = all || "PLAYER".equalsIgnoreCase(kind);

        int cap = Math.max(1, Math.min(limit, 100));
        PageRequest page = PageRequest.of(0, cap);
        List<Map<String, Object>> out = new ArrayList<>();

        if (wantTeam) {
            for (Team team : teams.findByNameFragment(fragment, page)) {
                String detail = team.getType() == CompetitionTeamType.NATIONAL_TEAM
                        ? "National team" + country(team)
                        : describe(team);
                out.add(row("TEAM", team.getId(), team.getName(), detail, team));
            }
        }

        if (wantUser) {
            for (org.example.commonmanager.model.User manager : users.findByNameFragment(fragment, page)) {
                Team club = manager.getFootballTeam();
                String name = manager.getDisplayName() != null && !manager.getDisplayName().isBlank()
                        ? manager.getDisplayName() : manager.getUsername();
                String detail = club == null
                        ? "Manager · no club"
                        : "Runs " + club.getName() + " · " + describe(club);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "USER");
                row.put("id", manager.getId());
                row.put("name", name);
                row.put("detail", detail);
                row.put("teamId", club == null ? null : club.getId());
                row.put("teamName", club == null ? null : club.getName());
                row.put("openFor", "user");
                row.put("openArg", manager.getId());
                out.add(row);
            }
        }

        if (wantPlayer) {
            List<Player> found = players.findByNameFragment(fragment, page);
            // A player called up to a national side is filtered out of the results above, because the
            // copy is not a second person. That is the right answer for "who is this", but it would
            // quietly hide that he is an international, so the squads he belongs to are named on the row
            // that survives. The information is moved onto one row, not dropped.
            //
            // One query for the whole page, not one per row: the world holds 33,000 players and this
            // endpoint is typed into.
            java.util.Map<Long, List<String>> ntByPlayer = new java.util.HashMap<>();
            List<Long> ids = found.stream().map(Player::getId)
                    .filter(java.util.Objects::nonNull).toList();
            if (!ids.isEmpty()) {
                for (Object[] pair : players.findNationalSquadsForSources(ids)) {
                    Long source = (Long) pair[0];
                    if (source == null) continue;
                    ntByPlayer.computeIfAbsent(source, k -> new java.util.ArrayList<>()).add((String) pair[1]);
                }
            }
            for (Player player : found) {
                Team club = player.getTeam();
                String detail = club == null ? "No club" : club.getName() + " · " + describe(club);
                List<String> national = ntByPlayer.get(player.getId());
                if (national != null && !national.isEmpty()) {
                    detail = detail + " · also " + String.join(", ", national);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "PLAYER");
                row.put("id", player.getId());
                row.put("name", player.getName());
                row.put("detail", detail);
                row.put("teamId", club == null ? null : club.getId());
                row.put("teamName", club == null ? null : club.getName());
                row.put("openFor", "player");
                row.put("openArg", player.getId());
                out.add(row);
            }
        }

        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> row(String type, Object id, String name, String detail, Team team) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("id", id);
        row.put("name", name);
        row.put("detail", detail);
        row.put("teamId", team.getId());
        row.put("teamName", team.getName());
        row.put("openFor", "club");
        row.put("openArg", team.getId());
        return row;
    }

    private static String country(Team team) {
        return team.getCountry() == null || team.getCountry().getName() == null
                ? "" : " · " + team.getCountry().getName();
    }

    private static String describe(Team team) {
        if (team.getCompetition() != null && team.getCompetition().getName() != null) {
            return team.getCompetition().getName();
        }
        return team.getCountry() != null && team.getCountry().getName() != null
                ? team.getCountry().getName() : "";
    }
}
