package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.scouting.ScoutAssignmentDTO;
import org.example.footballmanager.newLogic.dto.scouting.ScoutingNetworkDTO;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.ScoutAssignment;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.ScoutAssignmentRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is watching where (Sprint 5, S5.1).
 *
 * <p>Before this, a club's academy produced the same rolled distribution every season and there was
 * no way to look outside it. {@code Country.youthRating} had been seeded since Sprint 2.1 and read by
 * nothing — the hook was already in the schema, waiting.
 *
 * <p><b>Owner ruling, 2026-09-27: scouting produces intel, never signings.</b> A posting is not a
 * transfer, is not a registration and carries no fee. That is why this service has no dependency on
 * the transfer or contract layer, and why S5.1 could be built before the non-EU quota that S3.5 never
 * delivered: nothing here puts a foreign player into a squad, so nothing here can breach a quota.
 * The moment that changes, the quota becomes a blocker again.
 *
 * <p>Scout wages are <b>not</b> charged here. A scout is a {@link StaffMember} and his wage already
 * flows through the weekly {@code STAFF_WAGES} ledger line whether or not he is abroad. Adding a
 * second charge for "scouting" would bill the same man twice for the same week.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutingService {

    /**
     * The lowest {@code youthRating} treated as "no pipeline at all".
     *
     * <p>Seeded values run 45-95. Anchoring the scale at 40 rather than 45 leaves a little room below
     * the observed floor so a future re-seed does not silently clamp every weak country to zero.
     */
    private static final int YOUTH_RATING_FLOOR = 40;

    /** The width of the seeded {@code youthRating} band, so the factor lands in 0..1. */
    private static final int YOUTH_RATING_SPAN = 60;

    /** The best a {@link StaffMember#scouting} attribute can be. */
    private static final int MAX_SCOUTING = 20;

    private final ScoutAssignmentRepository scoutAssignmentRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final CountryRepository countryRepository;
    private final TeamRepository teamRepository;

    /**
     * Sends a scout to a country.
     *
     * @param teamId the club doing the scouting
     * @param scoutId a staff member <b>of that club</b> whose role is {@link StaffRole#SCOUT}
     * @param countryId the country to watch
     * @param seasonNumber the season the posting starts, recorded so a report can say how long it has run
     * @return the created assignment, as the manager will see it
     */
    @Transactional
    public ScoutAssignmentDTO assignScout(Long teamId, Long scoutId, Long countryId, int seasonNumber) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found."));

        StaffMember scout = staffMemberRepository.findById(scoutId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCOUT_NOT_FOUND", "Scout not found."));

        // Own-club check first. A scout id is guessable, and without this a manager could post a rival's
        // scout to a country and read reports generated with the rival's scouting attribute.
        if (scout.getTeam() == null || !teamId.equals(scout.getTeam().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SCOUT_NOT_OWN_STAFF",
                    "That scout does not work for your club.");
        }
        if (scout.getRole() != StaffRole.SCOUT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STAFF_NOT_A_SCOUT",
                    scout.getName() + " is not a scout. Only staff with the SCOUT role can be posted abroad.");
        }

        Country country = countryRepository.findById(countryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COUNTRY_NOT_FOUND", "Country not found."));

        if (scoutAssignmentRepository.existsActiveForCountry(teamId, countryId)) {
            throw new ApiException(HttpStatus.CONFLICT, "COUNTRY_ALREADY_COVERED",
                    "Your club already has a scout watching " + country.getName() + ".");
        }

        ScoutAssignment assignment = new ScoutAssignment();
        assignment.setTeam(team);
        assignment.setScout(scout);
        assignment.setCountry(country);
        assignment.setAssignedSeasonNumber(seasonNumber);
        assignment.setActive(true);
        ScoutAssignment saved = scoutAssignmentRepository.save(assignment);

        log.info("Scout assignment: {} posted {} to {} (season {}, reach {})",
                team.getName(), scout.getName(), country.getName(), seasonNumber, reach(scout, country));
        return toDto(saved, seasonNumber);
    }

    /**
     * Brings a scout home.
     *
     * <p>Sets the assignment inactive rather than deleting it. The record is the reason a report can
     * say a club watched a country for two seasons, and the wage is unaffected either way — the scout
     * is still on the books, he is just not abroad.
     */
    @Transactional
    public void recallScout(Long teamId, Long assignmentId) {
        ScoutAssignment assignment = scoutAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND",
                        "Scouting assignment not found."));

        if (assignment.getTeam() == null || !teamId.equals(assignment.getTeam().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ASSIGNMENT_NOT_OWNED",
                    "That scouting assignment belongs to another club.");
        }
        if (!Boolean.TRUE.equals(assignment.getActive())) {
            return;
        }
        assignment.setActive(false);
        scoutAssignmentRepository.save(assignment);
        log.info("Scout recalled: assignment {} ended for team {}", assignmentId, teamId);
    }

    /** The club's whole network, active postings only. */
    @Transactional(readOnly = true)
    public ScoutingNetworkDTO networkFor(Long teamId, int seasonNumber) {
        if (!teamRepository.existsById(teamId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found.");
        }
        List<ScoutAssignment> active = scoutAssignmentRepository.findByTeamIdAndActiveTrueOrderByIdAsc(teamId);

        List<ScoutAssignmentDTO> rows = new ArrayList<>(active.size());
        int total = 0;
        for (ScoutAssignment assignment : active) {
            ScoutAssignmentDTO row = toDto(assignment, seasonNumber);
            total += row.getReach();
            rows.add(row);
        }

        ScoutingNetworkDTO network = new ScoutingNetworkDTO();
        network.setTeamId(teamId);
        network.setAssignments(rows);
        network.setTotalReach(total);
        network.setHasCoverage(!rows.isEmpty());
        network.setSummary(rows.isEmpty() ? "" : summarise(rows.size(), total));
        return network;
    }

    /**
     * How good a single posting is, 0-100.
     *
     * <p>The <b>product</b> of the scout's attribute and the country's pipeline, not their sum. This
     * is the one modelling decision in the feature and it is deliberate: a sum lets a great scout
     * compensate for being sent somewhere with nothing to find, and lets a rich pipeline compensate
     * for being watched by someone who cannot tell a prospect from a squad player. Multiplying means
     * both have to be right, which is what makes the assignment a decision.
     *
     * <p>Both inputs are normalised to 0..1 first, so the output is a genuine 0-100 and not an
     * accident of the two scales happening to be similar.
     */
    static int reach(StaffMember scout, Country country) {
        double scoutFactor = clamp01(scouting(scout) / (double) MAX_SCOUTING);
        double countryFactor = clamp01((youthRating(country) - YOUTH_RATING_FLOOR) / (double) YOUTH_RATING_SPAN);
        return (int) Math.round(100.0 * scoutFactor * countryFactor);
    }

    private String summarise(int countries, int total) {
        if (countries == 1) {
            return "One country covered. A single posting tells you almost nothing about the rest of the world.";
        }
        int perPosting = total / countries;
        if (perPosting < 15) {
            return countries + " countries covered, but the reports will be thin. Better scouts, or richer countries, would tell you more.";
        }
        if (perPosting < 40) {
            return countries + " countries covered. You are hearing about the odd prospect; nobody in particular.";
        }
        if (perPosting < 70) {
            return countries + " countries covered and the reports are worth reading.";
        }
        return countries + " countries covered, and your scouts are close enough to be trusted.";
    }

    private ScoutAssignmentDTO toDto(ScoutAssignment assignment, int seasonNumber) {
        StaffMember scout = assignment.getScout();
        Country country = assignment.getCountry();

        ScoutAssignmentDTO dto = new ScoutAssignmentDTO();
        dto.setAssignmentId(assignment.getId());
        dto.setCountryId(country != null ? country.getId() : null);
        dto.setCountryName(country != null ? country.getName() : null);
        dto.setCountryIsoCode(country != null ? country.getIsoCode() : null);
        dto.setCountryYouthRating(youthRating(country));
        dto.setScoutId(scout != null ? scout.getId() : null);
        dto.setScoutName(scout != null ? scout.getName() : null);
        dto.setScoutScouting(scouting(scout));
        dto.setAssignedSeasonNumber(assignment.getAssignedSeasonNumber());
        dto.setReach(reach(scout, country));
        dto.setReachLabel(reachLabel(dto.getReach()));
        return dto;
    }

    private static String reachLabel(int reach) {
        if (reach < 1) return "No contact";
        if (reach < 15) return "Distant";
        if (reach < 35) return "Occasional";
        if (reach < 60) return "Regular";
        if (reach < 80) return "Close";
        return "Embedded";
    }

    /**
     * The scouting attribute, defaulting to 1 rather than 0.
     *
     * <p>A null or zero attribute means a scout nobody has rated. Defaulting to 0 would make a
     * freshly seeded scout worth nothing at all, and the honest reading of "unrated" is "barely
     * better than blind", not "cannot see".
     */
    private static int scouting(StaffMember scout) {
        if (scout == null || scout.getScouting() == null) return 1;
        return Math.max(1, scout.getScouting());
    }

    private static int youthRating(Country country) {
        if (country == null || country.getYouthRating() == null) return YOUTH_RATING_FLOOR;
        return country.getYouthRating();
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
