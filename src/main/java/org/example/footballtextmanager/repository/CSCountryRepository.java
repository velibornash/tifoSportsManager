package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.CSCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CSCountryRepository extends JpaRepository<CSCountry, Long> {
}
