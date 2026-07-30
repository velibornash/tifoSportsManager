package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class TrainingWeekReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    private Integer seasonNumber;
    private Integer weekNumber;

    @Column(columnDefinition = "TEXT")
    private String reportJson;

    private LocalDateTime createdAt;
}
