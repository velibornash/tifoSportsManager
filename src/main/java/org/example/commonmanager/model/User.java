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

    /**
     * Whether this account has actually <b>paid</b> for PLUS.
     *
     * <p>Deliberately not the same question as "may this account see talent", which is
     * {@code PlusFeatureService.hasPlus} and additionally honours the role bypass. A role is a
     * permission and a subscription is a purchase; showing an owner as a paying customer because his
     * role overrode the check would be wrong on the one screen whose entire job is to report the
     * account truthfully.
     *
     * <p>It lives here rather than in the gate service so the profile can ask it without the auth
     * module depending on the football engine. It was previously a method on the service that nothing
     * called, because the profile read the column directly — the duplication the
     * {@code PlusGateHasCallersTest} guard exists to stop.
     */
    public boolean isPlusSubscriber() {
        return Boolean.TRUE.equals(this.plusSubscription);
    }

    @Enumerated(EnumType.STRING)
    private UserRole role;

    private LocalDateTime communityLastViewedAt;

    /**
     * The nation this manager plays in, chosen at registration (owner, 2026-09-28).
     *
     * <p>This is the field the whole country-agnostic system hangs off. It used to not exist: a user's
     * country was <i>derived</i> on every request by looking up the club they managed, so you got a
     * country as a consequence of picking a club rather than by choosing one — and a country could
     * never be chosen that had no club to hand out yet.
     *
     * <p>A three-letter code from {@code CountryCatalog}, not a foreign key, on purpose: the
     * registration form has to offer these before any user exists, and the two must not be able to
     * drift. Nullable, because a legacy row predates the field; the resolver falls back to the
     * club's country so an old account keeps working.
     */
    private String countryCode;

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
