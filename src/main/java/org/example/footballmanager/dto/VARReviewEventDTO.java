package org.example.footballmanager.dto;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data
@EqualsAndHashCode(callSuper = true)
public class VARReviewEventDTO  extends MatchEventDTO {
    private String decision;
    private String reviewTarget;
    private String overturnReason;
    private boolean isSecondYellow;
}
