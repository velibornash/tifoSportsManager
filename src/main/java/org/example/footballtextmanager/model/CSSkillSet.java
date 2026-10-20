package org.example.footballtextmanager.model;

import java.util.Map;

public interface CSSkillSet {
    Map<CSSkillName, Integer> getSkills();
    void setSkill(CSSkillName name, int value);
    double getRatingScore(CSPosition CSPosition);
}