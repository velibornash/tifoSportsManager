package org.example.basketballmanager.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "bb_leagues")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbLeague {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 10)
    private String shortName;

    @Column(length = 2)
    private String country;

    @Builder.Default
    private Integer tier = 1;

    @Column(name = "points_win")
    @Builder.Default
    private Integer pointsWin = 3;

    @Column(name = "points_draw")
    @Builder.Default
    private Integer pointsDraw = 1;

    @Column(name = "points_loss")
    @Builder.Default
    private Integer pointsLoss = 0;

}
