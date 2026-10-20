package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.NationalTeamAppointment;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NationalTeamAppointmentRepository
        extends JpaRepository<NationalTeamAppointment, Long> {

    Optional<NationalTeamAppointment> findByCountryIdAndLevelAndActiveTrue(Long countryId, NationalTeamLevel level);

    List<NationalTeamAppointment> findByCountryIdAndActiveTrue(Long countryId);

    long countByCountryIdAndLevelAndActiveTrue(Long countryId, NationalTeamLevel level);

    /** Every appointment a country has made, active or not, for the legacy-country cleanup. */
    List<NationalTeamAppointment> findByCountryId(Long countryId);
}
