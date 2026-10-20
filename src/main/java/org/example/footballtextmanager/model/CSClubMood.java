package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSClubMood {
    private int boardConfidence;  // 0-100
    private int fanMood;          // 0-100
    private int mediaPressure;    // 0-100 (higher = more pressure)
    private int squadMorale;      // 0-100
    private int financialHealth;  // 0-100
    private String moodLabel;     // "Excellent", "Good", "Stable", "Poor", "Crisis"
    
    public String getBoardConfidenceLabel() {
        if (boardConfidence >= 80) return "Full confidence";
        if (boardConfidence >= 60) return "High confidence";
        if (boardConfidence >= 40) return "Moderate confidence";
        if (boardConfidence >= 20) return "Low confidence";
        return "No confidence";
    }
    
    public String getFinancialHealthLabel() {
        if (financialHealth >= 80) return "Excellent financial health";
        if (financialHealth >= 60) return "Stable finances";
        if (financialHealth >= 40) return "Concerning budget";
        if (financialHealth >= 20) return "Financial strain";
        return "Financial crisis";
    }
    
    public String getFanMoraleLabel() {
        if (fanMood >= 80) return "Fanbase is electric";
        if (fanMood >= 60) return "Supporters are pleased";
        if (fanMood >= 40) return "Mixed feelings among fans";
        if (fanMood >= 20) return "Growing unrest";
        return "Fan dissatisfaction is high";
    }
}
