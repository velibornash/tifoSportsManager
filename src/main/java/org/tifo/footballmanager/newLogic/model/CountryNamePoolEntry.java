package org.tifo.footballmanager.newLogic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * One name from a country's pool (redesign.md R-23).
 *
 * <p>The **working copy** of the pools. The files under {@code classpath:/names/<ISO>.json} are what
 * a world build starts from (a <i>Reset DB</i> truncates the tables, and the pools are content, not
 * state); the admin panel edits these rows, so a country can have its club names, cities and
 * people's names set separately, without anyone editing a file.
 */
@Entity
@Table(name = "country_name_pool",
        uniqueConstraints = @UniqueConstraint(columnNames = {"country_id", "category", "name_value"}),
        indexes = @Index(name = "ix_name_pool_country", columnList = "country_id"))
@Data
public class CountryNamePoolEntry {

    /** The four kinds of name a country can set. */
    public enum Category {
        CLUB,
        CITY,
        FIRST_NAME,
        LAST_NAME
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    /**
     * The name itself.
     *
     * <p>The column is called {@code name_value} and not {@code value}, because {@code value} is a
     * reserved word: the seeder's own query failed with "Syntax error in SQL statement ... expected
     * identifier" the first time the table was read (H2 and PostgreSQL both).
     */
    @Column(name = "name_value", nullable = false, length = 120)
    private String value;
}
