package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.RatingWatermark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RatingWatermarkRepository extends JpaRepository<RatingWatermark, String> {
}
