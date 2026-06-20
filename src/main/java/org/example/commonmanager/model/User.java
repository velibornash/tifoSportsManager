package org.example.commonmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import org.example.footballtextmanager.model.CTeam;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Entity(name = "CommonUser")
@Table(name = "app_user")
@Data
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String email;
    private String password;

    @Enumerated(EnumType.STRING)
    private UserRole role;

    private LocalDateTime communityLastViewedAt;

    @OneToOne
    private CTeam CTeam;

    @OneToOne
    private org.example.americanfootballmanager.model.AfTeam americanFootballTeam;

    @OneToOne
    private org.example.basketballmanager.model.BbTeam basketballTeam;

    @OneToOne
    private CTeam tifoCTeam;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return true; }
}
