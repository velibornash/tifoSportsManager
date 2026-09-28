package org.example.footballmanager.newLogic.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity(name = "RegistrationRequest")
@Table(name = "registration_request")
@Data
@NoArgsConstructor
public class RegistrationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /**
     * The country chosen on the registration form (owner, 2026-09-28).
     *
     * <p>Kept on the request as well as the user, because the club is reserved <i>at submission time</i>
     * and an admin reviewing the request needs to see what the applicant actually asked for. Without
     * it the reviewer sees a club and has to infer the intent from which country the club happens to
     * be in.
     */
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RegistrationRequestStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    private String reviewerUsername;

    @Column(length = 1000)
    private String reviewNote;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = RegistrationRequestStatus.PENDING;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}