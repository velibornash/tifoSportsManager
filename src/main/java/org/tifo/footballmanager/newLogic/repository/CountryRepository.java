package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Country;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CountryRepository extends JpaRepository<Country, Long> {
    Optional<Country> findByName(String name);
    Optional<Country> findByIsoCode(String isoCode);
}