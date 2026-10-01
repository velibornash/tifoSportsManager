package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSPlayer {
    private Long id;
    private String name;
    private String position; // GK, DEF, MID, ATT, WNG
    private int age;
    private int rating;
    private double form;       // 1.0 - 10.0
    private double fatigue;    // 0-10
    private double talent;     // 3.0 top, 9.0 los

    // CSSkills
    private int stamina;
    private int goalkeeper;
    private int defending;
    private int pace;
    private int technique;
    private int playmaker;
    private int passing;
    private int shooting;

    // Stats za sezonu
    private int goals;
    private int assists;

    private double value;
    private double earnings;
    private double height;
    private double weight;

    /**
     * Ocena igrača na skali <b>1-10</b>, izvedena iz njegovih veština.
     *
     * <p>Ovde je bila tri neslaganja u istoj klasi, i sva su bila ozbiljna:
     * <ul>
     *   <li>kolona {@code CPlayer.rating} je dokumentovana kao 1-100, a seeder je upisivao
     *       {@code getRatingScore() / 10}, tj. 1-10;</li>
     *   <li>{@code createGeneratedPlayer} je za promovisane klubove upisivao <b>44-78</b>;</li>
     *   <li>u {@code calculateStrength} ocena ulazi sa težinom 0.4, pa je 60 značilo šestostruko
     *       veći doprinos nego 10 — promovisani klubovi su ulazili u ligu jači od svih ostalih.</li>
     * </ul>
     *
     * <p>Težine su iste kao u {@code CSSkills.getRatingScore}, pa se ocena seederom upisana
     * u bazu i ova računska ocena poklapaju za svakog postojećeg igrača.
     *
     * <p>Namerno je izvedena iz veština, a ne prepisana iz baze: ocena i veštine onda ne mogu
     * da se raziđu, jer nema drugog izvora istine koji bi se mogao zaboraviti.
     */
    public int calculateRating() {
        double score = switch (position == null ? "MID" : position) {
            case "GK" -> goalkeeper * 2.0 + pace * 1.0 + passing * 1.0 + defending * 0.5;
            case "DEF" -> pace * 1.5 + defending * 1.5 + playmaker * 1.0 + passing * 1.0 + technique * 0.8;
            case "MID" -> pace * 1.0 + technique * 1.2 + playmaker * 2.0 + passing * 1.5 + defending * 0.7;
            case "WNG" -> pace * 2.0 + technique * 1.5 + passing * 2.0;
            case "ATT" -> pace * 2.0 + technique * 1.5 + shooting * 2.0 + defending * 0.5;
            default -> technique * 1.2 + passing * 1.5 + playmaker * 1.5;
        };
        return (int) Math.max(1, Math.min(10, Math.round(score / 10.0)));
    }
}
