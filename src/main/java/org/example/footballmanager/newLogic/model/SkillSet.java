package org.example.footballmanager.newLogic.model;

import java.util.Map;

public interface SkillSet {
    Map<SkillName, Integer> getSkills();
    void setSkill(SkillName name, int value);
}