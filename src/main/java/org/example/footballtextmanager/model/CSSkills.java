package org.example.footballtextmanager.model;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Transient;
import lombok.Data;

import java.util.EnumMap;
import java.util.Map;

@Embeddable
@Access(AccessType.FIELD)
@Data
public class CSSkills implements CSSkillSet {

    private int stamina;
    private int goalkeeper;
    private int defender;
    private int pace;
    private int technique;
    private int playmaker;
    private int passing;
    private int striker;
    private int fatigue; // NOVO

    // Hidden exact values used by training progression.
    private Double staminaExact;
    private Double goalkeeperExact;
    private Double defenderExact;
    private Double paceExact;
    private Double techniqueExact;
    private Double playmakerExact;
    private Double passingExact;
    private Double strikerExact;

    @Override
    @Transient
    public Map<CSSkillName, Integer> getSkills() {
        Map<CSSkillName, Integer> map = new EnumMap<>(CSSkillName.class);
        map.put(CSSkillName.STAMINA, stamina);
        map.put(CSSkillName.GOALKEEPER, goalkeeper);
        map.put(CSSkillName.DEFENDER, defender);
        map.put(CSSkillName.PACE, pace);
        map.put(CSSkillName.TECHNIQUE, technique);
        map.put(CSSkillName.PLAYMAKER, playmaker);
        map.put(CSSkillName.PASSING, passing);
        map.put(CSSkillName.STRIKER, striker);
        map.put(CSSkillName.FATIGUE, fatigue); // dodajemo
        return map;
    }

    @Override
    public void setSkill(CSSkillName name, int value) {
        switch (name) {
            case STAMINA -> { stamina = value; staminaExact = (double) value; }
            case GOALKEEPER -> { goalkeeper = value; goalkeeperExact = (double) value; }
            case DEFENDER -> { defender = value; defenderExact = (double) value; }
            case PACE -> { pace = value; paceExact = (double) value; }
            case TECHNIQUE -> { technique = value; techniqueExact = (double) value; }
            case PLAYMAKER -> { playmaker = value; playmakerExact = (double) value; }
            case PASSING -> { passing = value; passingExact = (double) value; }
            case STRIKER -> { striker = value; strikerExact = (double) value; }
            case FATIGUE -> fatigue = value; // dodajemo
        }
    }

    public double getExact(CSSkillName name) {
        return switch (name) {
            case STAMINA -> staminaExact != null ? staminaExact : stamina;
            case GOALKEEPER -> goalkeeperExact != null ? goalkeeperExact : goalkeeper;
            case DEFENDER -> defenderExact != null ? defenderExact : defender;
            case PACE -> paceExact != null ? paceExact : pace;
            case TECHNIQUE -> techniqueExact != null ? techniqueExact : technique;
            case PLAYMAKER -> playmakerExact != null ? playmakerExact : playmaker;
            case PASSING -> passingExact != null ? passingExact : passing;
            case STRIKER -> strikerExact != null ? strikerExact : striker;
            case FATIGUE -> fatigue;
        };
    }

    public void setExact(CSSkillName name, double value) {
        double clamped = Math.max(0.0, Math.min(20.99, value));
        switch (name) {
            case STAMINA -> staminaExact = clamped;
            case GOALKEEPER -> goalkeeperExact = clamped;
            case DEFENDER -> defenderExact = clamped;
            case PACE -> paceExact = clamped;
            case TECHNIQUE -> techniqueExact = clamped;
            case PLAYMAKER -> playmakerExact = clamped;
            case PASSING -> passingExact = clamped;
            case STRIKER -> strikerExact = clamped;
            case FATIGUE -> fatigue = (int) Math.floor(clamped);
        }
    }

    public void initializeExactFromVisibleIfNeeded() {
        if (staminaExact == null) staminaExact = (double) stamina;
        if (goalkeeperExact == null) goalkeeperExact = (double) goalkeeper;
        if (defenderExact == null) defenderExact = (double) defender;
        if (paceExact == null) paceExact = (double) pace;
        if (techniqueExact == null) techniqueExact = (double) technique;
        if (playmakerExact == null) playmakerExact = (double) playmaker;
        if (passingExact == null) passingExact = (double) passing;
        if (strikerExact == null) strikerExact = (double) striker;
    }

    public void syncVisibleFromExact() {
        stamina = (int) Math.floor(Math.max(0.0, staminaExact != null ? staminaExact : stamina));
        goalkeeper = (int) Math.floor(Math.max(0.0, goalkeeperExact != null ? goalkeeperExact : goalkeeper));
        defender = (int) Math.floor(Math.max(0.0, defenderExact != null ? defenderExact : defender));
        pace = (int) Math.floor(Math.max(0.0, paceExact != null ? paceExact : pace));
        technique = (int) Math.floor(Math.max(0.0, techniqueExact != null ? techniqueExact : technique));
        playmaker = (int) Math.floor(Math.max(0.0, playmakerExact != null ? playmakerExact : playmaker));
        passing = (int) Math.floor(Math.max(0.0, passingExact != null ? passingExact : passing));
        striker = (int) Math.floor(Math.max(0.0, strikerExact != null ? strikerExact : striker));
    }

    @Override
    public double getRatingScore(CSPosition CSPosition) {
        return switch (CSPosition) {
            case GK -> goalkeeper * 2.0 + pace * 1.0 + passing * 1.0 + defender * 0.5;
            case DEF -> pace * 1.5 + defender * 1.5 + playmaker * 1.0 + passing * 1.0 + technique * 0.8;
            case MID -> pace * 1.0 + technique * 1.2 + playmaker * 2.0 + passing * 1.5 + defender * 0.7;
            case ATT -> pace * 2.0 + technique * 1.5 + striker * 2.0 + defender * 0.5;
            case WNG -> pace * 2.0 + technique * 1.5 + passing * 2.0;

        };
    }

    public int getTotalForRating(String position) {
        return switch (position.toUpperCase()) {
            case "GK", "GOALKEEPER" -> goalkeeper;
            default -> defender + pace + technique + playmaker + passing + striker;
        };
    }
}
