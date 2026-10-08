package org.example.commonmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.service.AdminDatabaseAsyncService;
import org.example.commonmanager.service.DatabaseBackupService;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.example.footballmanager.newLogic.jobs.impl.InternationalClubCupJob;
import org.example.footballmanager.newLogic.service.CountryActivationService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.TransferService;
import org.example.footballmanager.newLogic.util.NationalRatingResetBackfill;
import org.example.footballmanager.newLogic.util.WorldCatalogSeeder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminDatabaseAsyncService adminDatabaseAsyncService;
    private final DatabaseBackupService databaseBackupService;
    private final org.example.footballmanager.newLogic.service.JobStatusService jobStatusService;
    private final org.example.footballmanager.newLogic.service.CountryActivationService countryActivationService;
    private final org.example.footballmanager.newLogic.service.WorldIntegrityService worldIntegrityService;
    private final org.example.footballmanager.newLogic.service.WorldRepairService worldRepairService;
    private final TransferService transferService;
    private final org.example.footballmanager.newLogic.service.RegistrationService registrationService;
    private final org.example.footballmanager.newLogic.repository.RegistrationRequestRepository registrationRequests;
    private final org.example.footballmanager.newLogic.util.NationalTournamentWorldService nationalTournamentWorldService;
    private final org.example.footballmanager.newLogic.util.NationalRatingResetBackfill nationalRatingResetBackfill;
    private final SeasonService seasonService;
    private final InternationalClubCupJob internationalClubCupJob;

    /**
     * The pending registration requests, for the admin queue.
     *
     * <p><b>All four legs of registration were unwired</b> (owner, 2026-09-28): {@code register.html}
     * posted to an endpoint that did not exist, the admin queue called one that did not exist, and
     * {@code RegistrationService.approveRequest} and {@code rejectRequest} — both fully written — had
     * no caller anywhere. So applying to play did nothing, and approving an application could not be
     * done from the interface. The only accounts in the game were the two hand-seeded ones.
     *
     * <p>Each request now carries the country its applicant chose, because the club was reserved
     * from that country's leagues and a reviewer looking at a club name alone cannot tell what the
     * applicant actually asked for.
     */
    @GetMapping("/registration-requests")
    public ResponseEntity<java.util.List<Map<String, Object>>> listPendingRegistrations() {
        return ResponseEntity.ok(registrationRequests
                .findByStatus(org.example.footballmanager.newLogic.model.RegistrationRequestStatus.PENDING)
                .stream()
                .map(request -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", request.getId());
                    row.put("username", request.getUsername());
                    row.put("email", request.getEmail());
                    row.put("countryCode", request.getCountryCode());
                    row.put("countryName",
                            org.example.footballmanager.newLogic.model.CountryCatalog
                                    .byCode(request.getCountryCode())
                                    .map(org.example.footballmanager.newLogic.model.CountryCatalog::displayName)
                                    .orElse(request.getCountryCode()));
                    row.put("teamId", request.getTeam() != null ? request.getTeam().getId() : null);
                    row.put("teamName", request.getTeam() != null ? request.getTeam().getName() : null);
                    row.put("createdAt", request.getCreatedAt());
                    return row;
                })
                .toList());
    }

    /** Approves a request, which is what actually creates the account and links the club. */
    @PostMapping("/registration-requests/{id}/{action:approve|reject}")
    public ResponseEntity<Map<String, Object>> decideRegistration(
            @PathVariable Long id,
            @PathVariable String action,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User reviewer,
            @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        try {
            var request = "approve".equalsIgnoreCase(action)
                    ? registrationService.approveRequest(id, reviewer, note)
                    : registrationService.rejectRequest(id, reviewer, note);
            return ResponseEntity.ok(Map.of(
                    "id", request.getId(),
                    "status", request.getStatus().name(),
                    "countryCode", request.getCountryCode() == null ? "" : request.getCountryCode()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/initialize-db")
    public ResponseEntity<Map<String, Object>> initializeDatabase() {
        return ResponseEntity.accepted().body(toDatabaseJobResponse(
                adminDatabaseAsyncService.startOrGetRunningJob("initialize")
        ));
    }

    /**
     * Builds the simulated nations — every country that is not activated, with its divisions, clubs,
     * ratings and a standing table.
     *
     * <p>Its own button, because it is not "Initialise". Init is the Serbian structure a manager plays
     * in; this is the other forty-six countries, and it is the longest write in the admin panel by a
     * wide margin. It is idempotent, so it tops up a half-built world rather than duplicating it.
     */
    @PostMapping("/seed-other-nations")
    public ResponseEntity<Map<String, Object>> seedOtherNations() {
        return ResponseEntity.accepted().body(toDatabaseJobResponse(
                adminDatabaseAsyncService.startOrGetRunningJob("seed-other-nations")
        ));
    }

    @PostMapping("/reset-db")
    public ResponseEntity<Map<String, Object>> resetDatabase() {
        return ResponseEntity.accepted().body(toDatabaseJobResponse(
                adminDatabaseAsyncService.startOrGetRunningJob("reset")
        ));
    }

    /**
     * Re-seeds national teams, and re-draws the cup, on demand (owner, 2026-09-29).
     *
     * <p>Both are idempotent - seeding tops up only what is missing, and the cup draw skips rounds
     * that already have ties - so running one is a repair, not a wipe. That is why they are exposed
     * rather than left to a reset: a world can be repaired without throwing away a season.
     */
    @PostMapping("/world-reseed")
    public ResponseEntity<Map<String, Object>> reseedWorld(
            @RequestParam(defaultValue = "national-teams") String what) {
        return ResponseEntity.ok(worldRepairService.repair(what));
    }

    /**
     * The national-team competitions: creates the four of them and draws the qualifying groups
     * (owner, 2026-10-06).
     *
     * <p>An admin action and not a boot action, per the rule that boot writes nothing. The tournament
     * bracket is not drawn here: it depends on qualifying results that do not exist yet, and the draw
     * job takes it a round at a time once they do.
     */
    @PostMapping("/national-tournaments")
    public ResponseEntity<Map<String, Object>> seedNationalTournaments() {
        return ResponseEntity.ok(toMap(nationalTournamentWorldService.seed()));
    }

    /**
     * Draws whatever the national tournaments' results so far allow.
     *
     * <p>The manual counterpart to the week-12 draw job, for a tournament that stalled and should not
     * wait for the clock to be nudged.
     */
    @PostMapping("/national-tournaments/advance")
    public ResponseEntity<Map<String, Object>> advanceNationalTournaments() {
        return ResponseEntity.ok(toMap(nationalTournamentWorldService.advanceTournaments()));
    }

    /**
     * Draws the international club-cup group stage for the season that has just finished.
     *
     * <p>This is deliberately the job itself rather than a second repair implementation, and it is called
     * on <b>the draw's own week and day</b> — week 12, day 7 — rather than on "whatever week it happens to
     * be". It used to pass the current week and a hardcoded day 1, which was two things wrong at once: the
     * job only reads the week, so on any other week the button answered 200 and drew nothing, and the day
     * in the request was a number the job never looks at.
     *
     * <p>So the button now does what its label says at any point in the year: it draws the field for the
     * season named, qualifying off that season's finished tables and creating next season's competition.
     * Repeating it is safe, because a cup whose group stage already exists is skipped.
     */
    @PostMapping("/international-club-cups/redraw")
    public ResponseEntity<Map<String, Object>> redrawInternationalClubCups() {
        int season = seasonService.getActiveSeasonYear();
        int drawWeek = InternationalClubCupJob.DRAW_WEEK;
        internationalClubCupJob.run(
                new JobContext(season, drawWeek, InternationalClubCupJob.DRAW_DAY, 8));
        return ResponseEntity.ok(Map.of(
                "season", season,
                "qualifiedFromSeason", season,
                "drawnForSeason", season + 1,
                "week", drawWeek,
                "day", InternationalClubCupJob.DRAW_DAY,
                "job", InternationalClubCupJob.KEY));
    }

/**
     * Re-draws the national-team qualifying groups for the current season.
     *
     * <p>This action deletes all unplayed qualifying and tournament fixtures for the current season,
     * re-creates any missing competitions, and draws fresh qualifying groups for both the senior and
     * U-21 levels. It is the proper "Re-draw" action: a clean slate for the qualifying phase.
     */
    @PostMapping("/national-tournaments/redraw")
    public ResponseEntity<Map<String, Object>> redrawNationalTournaments() {
        return ResponseEntity.ok(toMap(nationalTournamentWorldService.forceRedraw()));
    }

    /**
     * A record to a map, spelled out rather than reflected.
     *
     * <p>The result of a seed is three values and a list, and every one of them is something the admin
     * screen shows. {@code Map.of} also refuses a null value, which a draw can legitimately produce
     * (a competition with no group standings yet), so it would throw on a successful seed.
     */
    private Map<String, Object> toMap(
            org.example.footballmanager.newLogic.util.NationalTournamentWorldService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("competitions", result.competitions());
        body.put("season", result.season());
        List<Map<String, Object>> draws = new java.util.ArrayList<>();
        int groups = 0;
        int groupFixtures = 0;
        int knockoutFixtures = 0;
        for (org.example.footballmanager.newLogic.util.NationalTournamentSeeder.DrawResult draw : result.draws()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groups", draw.groups());
            row.put("entrants", draw.entrants());
            row.put("groupFixtures", draw.groupFixtures());
            row.put("knockoutFixtures", draw.knockoutFixtures());
            row.put("note", draw.note());
            draws.add(row);
            groups += draw.groups();
            groupFixtures += draw.groupFixtures();
            knockoutFixtures += draw.knockoutFixtures();
        }
        body.put("draws", draws);
        body.put("groups", groups);
        body.put("groupFixtures", groupFixtures);
        body.put("knockoutFixtures", knockoutFixtures);
        return body;
    }

    /**
     * Puts every country back on the starting national rating (owner, 2026-10-06).
     *
     * <p>Separate from {@link #worldReseed} because it is the one action that discards information:
     * it resets what the Elo replay has derived, and doing that to a world mid-season throws away real
     * results. It is idempotent and reports how many countries actually moved, so pressing it on a
     * healthy world changes nothing.
     */
    @PostMapping("/national-ratings/reset")
    public ResponseEntity<Map<String, Object>> resetNationalRatings() {
        NationalRatingResetBackfill.Result result = nationalRatingResetBackfill.resetAll();
        return ResponseEntity.ok(Map.of(
                "countries", result.countries(),
                "moved", result.moved(),
                "alreadyCorrect", result.alreadyCorrect(),
                "startingRating", WorldCatalogSeeder.STARTING_RATING));
    }

    /** Which countries are off the starting rating, without changing anything. */
    @GetMapping("/national-ratings/offenders")
    public ResponseEntity<Map<String, Object>> nationalRatingOffenders() {
        return ResponseEntity.ok(Map.of(
                "startingRating", WorldCatalogSeeder.STARTING_RATING,
                "offenders", nationalRatingResetBackfill.offenders()));
    }

    /**
     * Is the world whole, and if not, put it back (owner, 2026-09-29).
     *
     * <p>Reachable because a reset or an interrupted start must not need a developer to fix. Reports
     * on GET without changing anything, so it can be asked the question safely; POST repairs.
     */
    /**
     * Every country, its state, and whether it holds a pyramid.
     *
     * <p>Under {@code /admin}, so the existing {@code hasAnyRole("ADMIN", "OWNER", "DEV")} guard covers
     * it. Activating a country writes 31 divisions and about 7,750 player rows, which is not something
     * an authenticated user should be able to do to the world by accident.
     */
    @GetMapping("/countries")
    public ResponseEntity<List<CountryActivationService.CountryRow>> countries() {
        return ResponseEntity.ok(countryActivationService.overview());
    }

    /**
     * Gives a country its five-tier pyramid and marks it active.
     *
     * <p>Idempotent, and the state flag is written only once the football is there — a country marked
     * ACTIVE over a half-built pyramid is worse than one that stayed SIMULATED, because every reader
     * would believe it was playable.
     */
    @PostMapping("/countries/{isoCode}/activate")
    public ResponseEntity<CountryActivationService.Result> activateCountry(@PathVariable String isoCode) {
        return ResponseEntity.ok(countryActivationService.activate(isoCode));
    }

    @GetMapping("/world-integrity")
    public ResponseEntity<Map<String, Object>> worldIntegrity() {
        return ResponseEntity.ok(worldIntegrityService.report());
    }

    @PostMapping("/world-integrity/repair")
    public ResponseEntity<Map<String, Object>> repairWorld() {
        return ResponseEntity.ok(worldIntegrityService.repair());
    }

    @GetMapping("/database-job/status")
    public ResponseEntity<Map<String, Object>> getDatabaseJobStatus() {
        return ResponseEntity.ok(toDatabaseJobResponse(adminDatabaseAsyncService.getJobSnapshot()));
    }

    /**
     * Every database dump on the server, newest first.
     *
     * <p>The list is what makes a backup worth taking: a dump nobody can find again is not a backup.
     */
    /**
     * Every job: its trigger, when it last ran, when it next runs, and whether anything failed.
     *
     * <p>The panel exists because of the question it makes answerable (owner, 2026-10-07): "does training
     * work?" could not be answered from anywhere in the application. Training did work — it was recorded
     * DONE — but with no history, no next trigger and no failure badge, <b>a job that ran and a job that
     * did not looked identical</b>. The rows were already in `job_run`; they had no reader.
     */
    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> jobs() {
        return ResponseEntity.ok(jobStatusService.report());
    }

    @GetMapping("/backups")
    public ResponseEntity<Map<String, Object>> listBackups() {
        return ResponseEntity.ok(Map.of("backups", databaseBackupService.list()));
    }

    /**
     * Dumps the whole database to a timestamped file (owner, 2026-10-06).
     *
     * <p>For taking a world away with you: a clean season 1 week 1 day 1 with every team seeded and
     * every cup drawn, kept so it can be put back exactly. Safe to press at any time - it only reads.
     */
    @PostMapping("/backups")
    public ResponseEntity<Map<String, Object>> createBackup() {
        return ResponseEntity.ok(Map.of("backup", databaseBackupService.create()));
    }

    /**
     * Replaces the database with the contents of one dump.
     *
     * <p>Destroys the current world, which is what restoring means, so it is a separate route with a
     * name in it rather than an option on the dump route: the admin screen confirms by showing which
     * file was picked.
     */
    @PostMapping("/backups/{name}/restore")
    public ResponseEntity<Map<String, Object>> restoreBackup(@PathVariable String name) {
        return ResponseEntity.ok(Map.of("restore", databaseBackupService.restore(name)));
    }

    /**
     * Force a player off the transfer list, ignoring any registered interest or live offers.
     *
     * <p>Operator escape hatch for a stuck listing. Kept under {@code /admin} deliberately: the
     * {@code /transfers} prefix is not role-guarded, so putting it there would let any authenticated
     * user delist another club's player.
     *
     * <p>Also the intended route for force-listing a specific player (for example a national-team
     * player who should not be sitting in a bot club).
     */
    @DeleteMapping("/transfer-list/{playerId}")
    public TransferDTO forceUnlist(@PathVariable Long playerId) {
        return transferService.forceUnlist(playerId);
    }

    private Map<String, Object> toDatabaseJobResponse(AdminDatabaseAsyncService.AdminDatabaseSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (snapshot.payload() != null && ("completed".equals(snapshot.status()) || "failed".equals(snapshot.status()))) {
            payload.putAll(snapshot.payload());
        }
        payload.put("status", snapshot.status());
        payload.put("action", snapshot.action());
        payload.put("jobId", snapshot.jobId());
        payload.put("message", snapshot.message());
        payload.put("completedSteps", snapshot.completedSteps());
        payload.put("totalSteps", snapshot.totalSteps());
        return payload;
    }
}
