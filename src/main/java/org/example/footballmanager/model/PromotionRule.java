package org.example.footballmanager.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class PromotionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Competition competition;

    @Enumerated(EnumType.STRING)
    private RuleType ruleType;

    private Integer positionFrom;
    private Integer positionTo;

    @ManyToOne
    private Competition targetCompetition; // za promociju

    private Boolean isPlayoff;

    private Boolean internationalSlot;
}
