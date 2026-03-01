package org.example.footballmanager.model;

import jakarta.persistence.Embeddable;
import lombok.Data;

import java.util.EnumMap;
import java.util.Map;

@Embeddable
@Data
public class Skills implements SkillSet {

    private int stamina;
    private int goalkeeper;
    private int defender;
    private int pace;
    private int technique;
    private int playmaker;
    private int passing;
    private int striker;
    private int fatigue; // NOVO

    @Override
    public Map<SkillName, Integer> getSkills() {
        Map<SkillName, Integer> map = new EnumMap<>(SkillName.class);
        map.put(SkillName.STAMINA, stamina);
        map.put(SkillName.GOALKEEPER, goalkeeper);
        map.put(SkillName.DEFENDER, defender);
        map.put(SkillName.PACE, pace);
        map.put(SkillName.TECHNIQUE, technique);
        map.put(SkillName.PLAYMAKER, playmaker);
        map.put(SkillName.PASSING, passing);
        map.put(SkillName.STRIKER, striker);
        map.put(SkillName.FATIGUE, fatigue); // dodajemo
        return map;
    }

    @Override
    public void setSkill(SkillName name, int value) {
        switch (name) {
            case STAMINA -> stamina = value;
            case GOALKEEPER -> goalkeeper = value;
            case DEFENDER -> defender = value;
            case PACE -> pace = value;
            case TECHNIQUE -> technique = value;
            case PLAYMAKER -> playmaker = value;
            case PASSING -> passing = value;
            case STRIKER -> striker = value;
            case FATIGUE -> fatigue = value; // dodajemo
        }
    }

    @Override
    public double getRatingScore(Position position) {
        return switch (position) {
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
