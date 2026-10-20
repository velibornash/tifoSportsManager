package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.RatingWatermark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RatingWatermarkRepository extends JpaRepository<RatingWatermark, String> {
}
