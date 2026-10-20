package org.example.commonmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "common_seasons")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommonSeason {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_year", nullable = false)
    private Integer seasonYear;

    private String description;
}
