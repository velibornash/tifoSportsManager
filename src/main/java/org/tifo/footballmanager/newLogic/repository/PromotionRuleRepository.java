package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.PromotionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromotionRuleRepository extends JpaRepository<PromotionRule, Long> {
}
