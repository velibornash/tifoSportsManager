package org.example.footballmanager.newLogic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity(name = "Country")
@Table(indexes = {
        @Index(name = "ix_country_state", columnList = "country_state")
})
@Getter
@Setter
public class Country {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String name;
    @Column(nullable = false, unique = true, length = 3)
    private String isoCode;
    private String flagImagePath;
    private String currencyCode;
    private Integer reputation;
    private Integer youthRating;

    @OneToMany(mappedBy = "country", fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Competition> competitions;
    @OneToMany(mappedBy = "country")
    @JsonIgnore
    private List<Team> clubs;
    /**
     * Ignored in JSON deliberately: {@code Team} holds a back-reference to {@code Country},
     * so serialising this pair is a cycle. It stayed hidden while both columns were null in
     * every country; the first real national team turned the country page into truncated JSON
     * that the browser could not parse. Ignored here, at the Country side, so that
     * {@code Team.country} stays serialisable for the pages that already read it.
     */
    @OneToOne
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Team seniorNationalTeam;
    /**
     * Ignored in JSON deliberately: {@code Team} holds a back-reference to {@code Country},
     * so serialising this pair is a cycle. It stayed hidden while both columns were null in
     * every country; the first real national team turned the country page into truncated JSON
     * that the browser could not parse. Ignored here, at the Country side, so that
     * {@code Team.country} stays serialisable for the pages that already read it.
     */
    @OneToOne
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Team u21NationalTeam;

    /**
     * Played or merely represented (owner, 2026-09-29).
     *
     * <p>Defaults to SIMULATED so a country nobody has activated is cheap, and so adding a country
     * cannot accidentally make the game simulate 48 pyramids. Serbia is flipped to ACTIVE by the
     * seeder, which is the only place that should ever change it at boot - activation is otherwise an
     * explicit act from the admin panel.
     */
    // A column DEFAULT, and not NOT NULL. ddl-auto=update adds the column to a table that already has
    // nine rows, and a NOT NULL column with no default cannot be added to a table with data - the ALTER
    // fails and the application context never starts. The default is how existing rows backfill.
    @jakarta.persistence.Column(name = "country_state", length = 16,
            columnDefinition = "varchar(16) default 'SIMULATED'")
    @Enumerated(jakarta.persistence.EnumType.STRING)
    private CountryState state = CountryState.SIMULATED;

    public CountryState getState() {
        return state;
    }

    public void setState(CountryState state) {
        this.state = state;
    }
}