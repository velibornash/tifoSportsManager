package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.CountryNamePoolEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** The per-country name pools (redesign.md R-23). */
public interface CountryNamePoolEntryRepository extends JpaRepository<CountryNamePoolEntry, Long> {

    List<CountryNamePoolEntry> findByCountryIdOrderByCategoryAscValueAsc(Long countryId);

    void deleteByCountryId(Long countryId);
}
