package org.example.commonmanager.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
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
@Getter
@Setter
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String email;
    private String password;

    /**
     * What the manager is actually called, shown on the profile and in the account corner.
     *
     * <p>Distinct from {@link #username}, which is an email address and doubles as the login. Showing
     * "velibor@example.com" where a person's name belongs is the same class of mistake as a raw
     * double in a percentage field: technically accurate, and wrong on the screen.
     */
    @Column(name = "display_name", length = 80)
    private String displayName;

    /**
     * Whether this account has paid for PLUS.
     *
     * <p><b>Deliberately separate from {@link #role}.</b> A role is a permission ("may reset the
     * database"); a subscription is a purchase ("may see talent"). Collapsing them means an owner who
     * never subscribed is shown as a paying customer, which is wrong on a profile screen and wrong
     * commercially.
     *
     * <p>{@code PlusFeatureService.hasPlus} still lets {@code OWNER}/{@code DEV}/{@code ADMIN}
     * through regardless — that bypass is a debugging affordance so a developer looking at a bug can
     * see the real number, and it is documented there as such. This field is what the UI reports.
     *
     * <p>Nullable means "never set", which is not the same as {@code false}: a legacy row has not been
     * through a checkout, and reading null as a refusal is the safe direction for a paid feature.
     */
    @Column(name = "plus_subscription")
    private Boolean plusSubscription;

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
