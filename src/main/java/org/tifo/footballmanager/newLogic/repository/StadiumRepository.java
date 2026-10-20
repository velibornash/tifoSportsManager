package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Stadium;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StadiumRepository extends JpaRepository<Stadium, Long> {
}