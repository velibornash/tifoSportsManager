    package org.example.footballmanager.model;

    import jakarta.persistence.*;
    import lombok.Data;

    import java.util.Optional;

    @Data
    @Entity
    public class Player {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @Embedded
        private Skills skills;

        private double talent;   // 3.0 top, 9.0 užasan
        public Position getPositionEnum() {
            return Optional.ofNullable(position)
                    .map(String::toUpperCase)
                    .map(Position::valueOf)
                    .orElseThrow(() -> new IllegalStateException("Position is null for player: " + name));
        }
        private int age;
        private double playerValue;
        private double earnings;
        private double height;   // u metrima, npr 1.85
        private double weight;   // u kg
        private double form;     // recimo od 1.0 do 10.0
        private int rating;      // ocena na utakmici 1-100
        private String position;
       // private Position position;
        private int totalGoals;
        private int totalAssists;
        @ManyToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "team_id")
        private Team team;

    }