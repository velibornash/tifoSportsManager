package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.NationalTeamAppointment;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.NationalTeamAppointmentRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.commonmanager.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * Who holds a national-team job (owner, 2026-09-28).
 *
 * <p>Its own class because the read side and the election both need to appoint. When this lived on
 * {@link NationalTeamService}, the election service had to call back into it and the two services
 * formed a constructor cycle that stopped the context from starting.
 */
@Service
public class NationalTeamAppointments {

    private static final Logger log = LoggerFactory.getLogger(NationalTeamAppointments.class);

    private final NationalTeamAppointmentRepository appointments;
    private final TeamRepository teams;
    private final UserRepository users;

    public NationalTeamAppointments(NationalTeamAppointmentRepository appointments,
                                    TeamRepository teams, UserRepository users) {
        this.appointments = appointments;
        this.teams = teams;
        this.users = users;
    }

    public Optional<NationalTeamAppointment> activeAppointment(Long countryId, NationalTeamLevel level) {
        return appointments.findByCountryIdAndLevelAndActiveTrue(countryId, level);
    }

    /**
     * Appoints a selector, standing the previous one down first.
     *
     * <p>The deactivation mutates the existing row rather than inserting a new inactive one, because
     * the unique constraint allows only a single inactive row per country and level.
     *
     * @param elected false for a manual stand-in, true for a declared election result. It is the
     *                difference between the country page saying "provisional" and saying nothing,
     *                so it is never defaulted.
     */
    @Transactional
    public NationalTeamAppointment appoint(Country country, NationalTeamLevel level, User selector,
                                           boolean elected) {
        // The deactivation is flushed before the insert on purpose. The unique constraint covers
        // (country, level, active), so the outgoing row must already read active=false in the
        // database before the incoming active=true row is written. Without the flush, Hibernate
        // orders the INSERT first and the declaration dies on a duplicate key - which is exactly what
        // happened when the election was first run.
        activeAppointment(country.getId(), level).ifPresent(previous -> {
            previous.setActive(false);
            appointments.save(previous);
            appointments.flush();
        });
        NationalTeamAppointment appointment = new NationalTeamAppointment();
        appointment.setCountry(country);
        appointment.setLevel(level);
        appointment.setSelector(selector);
        appointment.setElected(elected);
        appointment.setActive(true);
        return appointments.save(appointment);
    }

    /**
     * Puts the country's manager in charge of both national sides (owner, 2026-09-28).
     *
     * <p>Stands in until the elections run. Marked {@code elected = false} so the UI can say
     * "provisional" rather than implying a vote that has not happened. Idempotent: an existing
     * appointment is left alone, so a real election result is never overwritten by a reboot.
     */
    @Transactional
    public void appointBaselineSelectors(Country country) {
        if (country == null || country.getId() == null) {
            return;
        }
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            if (activeAppointment(country.getId(), level).isPresent()) {
                continue;
            }
            // There is no manager column on Team: a manager is a User whose app_user.cteam_id points
            // at the club, and the ids are the same space. So the country's selector is the first user
            // whose club sits in this country.
            java.util.Set<Long> clubIds = teams.findClubTeamsForOperations().stream()
                    .filter(team -> team.getId() != null && team.getCountry() != null
                            && country.getId().equals(team.getCountry().getId()))
                    .map(Team::getId)
                    .collect(java.util.stream.Collectors.toSet());
            User manager = users.findAll().stream()
                    .filter(u -> u.getCTeam() != null && u.getCTeam().getId() != null)
                    .filter(u -> clubIds.contains(u.getCTeam().getId()))
                    // Sorted by id, not left in findAll order: the owner must win, and repository
                    // order is arbitrary. Without this, Kecko was appointed ahead of Velja.
                    .sorted(Comparator.comparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .findFirst()
                    .orElse(null);
            if (manager == null) {
                log.warn("No club manager found for {}; {} selector left unappointed.", country.getIsoCode(), level);
                continue;
            }
            appoint(country, level, manager, false);
            log.info("Appointed {} as {} selector for {} (provisional, pending elections).",
                    manager.getUsername(), level, country.getIsoCode());
        }
    }

}
