package org.example.footballmanager.repository;

import org.example.footballmanager.model.Country;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CountryRepository  extends JpaRepository<Country, Long> {
    Optional<Country> findByName(String name);
    Optional<Country> findByIsoCode(String code);
}
