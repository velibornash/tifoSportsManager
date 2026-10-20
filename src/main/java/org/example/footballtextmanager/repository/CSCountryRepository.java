package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.CSCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CSCountryRepository extends JpaRepository<CSCountry, Long> {

    /**
     * The country record for an ISO code, if it exists.
     *
     * <p>Added for the same reason the seeder needs it: the text-manager's own data bootstrap
     * early-returns on an already-seeded database, so the row it would have created is not
     * guaranteed to be there. Looked up rather than created blindly, so seeding twice does not
     * produce two Serbias.
     */
    Optional<CSCountry> findByIsoCodeIgnoreCase(String isoCode);
}
