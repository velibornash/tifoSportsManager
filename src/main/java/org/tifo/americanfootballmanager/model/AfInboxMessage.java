package org.tifo.americanfootballmanager.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "af_inbox")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfInboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(length = 20)
    private String desk;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "is_read")
    @Builder.Default
    private Boolean read = false;

    @Column(name = "season_year")
    private Integer seasonYear;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
