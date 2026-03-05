package org.example.footballmanager.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "match_fixture")
public class MatchFixture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team awayTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    private Competition competition;

    private Integer seasonYear;
    private Integer roundNumber;
    private Integer weekNumber;
    private LocalDateTime matchDate;
    private boolean played;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "played_match_id")
    private Match playedMatch;
}
