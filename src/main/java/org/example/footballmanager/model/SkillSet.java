package org.example.footballmanager.model;

import java.util.Map;

public interface SkillSet {
    Map<SkillName, Integer> getSkills();
    void setSkill(SkillName name, int value);
    double getRatingScore(Position position);
}