package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.StadiumSection;
import org.example.footballmanager.newLogic.model.StandPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StadiumSectionRepository extends JpaRepository<StadiumSection, Long> {

    List<StadiumSection> findByStadiumIdOrderByPositionAsc(Long stadiumId);

    Optional<StadiumSection> findByStadiumIdAndPosition(Long stadiumId, StandPosition position);
}
