package org.example.commonmanager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One login event for an account (owner, 2026-10-17).
 *
 * <p>Records the IP address, user agent and timestamp of every successful login, so an administrator
 * can see where an account has been accessed from. The history is grouped by IP on the client, because
 * a manager logging in from one address twenty times is one fact, not twenty.
 *
 * <p>Read-only from the user's perspective: only an admin (OWNER / ADMIN / DEV) may view another
 * account's history. A regular manager sees nothing.
 */
@Entity(name = "LoginHistory")
@Table(name = "login_history", indexes = {
        @Index(name = "idx_login_history_user", columnList = "user_id, login_at"),
        @Index(name = "idx_login_history_ip", columnList = "ip_address, login_at")
})
@Getter
@Setter
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt;

    @PrePersist
    void onCreate() {
        if (loginAt == null) {
            loginAt = LocalDateTime.now();
        }
    }
}
