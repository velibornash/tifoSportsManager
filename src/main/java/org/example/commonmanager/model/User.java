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

    /**
     * DELETED with the chat (P2-20 Phase 6).
     *
     * <p>Was the only persisted read-tracking in the application: one timestamp per account, written
     * every time {@code GET /community/chat} was called — <b>a write on a GET</b> — and read by
     * {@code /community/summary} to count messages newer than it.
     *
     * <p>It could not answer the question it was being asked. Four unread messages and a single cursor
     * means the store cannot say which of them the manager has seen, and there is nothing to clear
     * individually. {@code nl_notification} replaced it with a row per notification and a {@code readAt}
     * per row.
     *
     * <p>The column itself stays in the database: {@code ddl-auto=update} adds columns and never drops
     * them, and this repository has no migration mechanism to remove it with.
     */
    private LocalDateTime communityLastViewedAt;

    /**
     * The last time this account made an authenticated request (owner, 2026-09-30).
     *
     * <p><b>Wall-clock time, deliberately — not the game clock.</b> "Online" has to mean a person is
     * at their desk, and a game clock the owner can advance a week in one click would report 48 managers
     * online the moment he moved it. The World page reads this to answer who is actually here.
     *
     * <p>Written at most once a minute per account; see {@code PresenceRegistry}. It is null until the
     * account has made a request, which is why a null here means "never seen" rather than "offline".
     */
    private LocalDateTime lastSeenAt;

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

    /**
     * Until when this account may not post in the forum, or null when it may.
     *
     * <p><b>A forum write ban and nothing else.</b> Reading the forum stays open, private messages stay
     * sendable, and the rest of the game is untouched — the manager can still manage his club, still
     * play, still do everything. A ban that locks somebody out of their own game is a support ticket,
     * not a moderation tool.
     *
     * <p>Wall-clock, like {@link #lastSeenAt}, because "for 7 days" has to mean seven days of the
     * manager's life and not seven weeks of the season.
     *
     * <p>Kept on the account rather than in a ban table because there is at most one active ban: the
     * moderation history that would justify a table is Phase 6's problem, and a second ban row per
     * manager would be read by nothing.
     */
    private LocalDateTime forumBanUntil;

    /**
     * Why this account was banned, shown to him and to moderators.
     *
     * <p>Not nullable on purpose. A ban a manager cannot see the reason for is one he cannot argue with,
     * and an argument he cannot make is how a moderation decision becomes a grievance.
     */
    @Column(name = "forum_ban_reason", length = 500)
    private String forumBanReason;

    /** Who applied the ban, as a name rather than a key: the row outlives the moderator. */
    @Column(name = "forum_ban_by", length = 120)
    private String forumBanBy;

    private LocalDateTime forumBanAt;

    /**
     * Whether this account is banned from posting in the forum right now.
     *
     * <p>A ban that has expired is not a ban. {@code forumBanUntil} is left in place so the reason stays
     * visible to moderators; only this question changes its answer as the clock moves.
     */
    public boolean isForumBanned() {
        return forumBanUntil != null && forumBanUntil.isAfter(java.time.LocalDateTime.now());
    }

    /**
     * The newLogic club this account actually manages, as a real foreign key.
     *
     * <p><b>This field did not exist, and the absence was the single most expensive fact in the
     * codebase.</b> {@code User} and {@code Team} were joined by a <i>string</i>: a user held a
     * {@link CTeam}, and every consumer resolved the football club by looking up
     * {@code Team.name == CTeam.name}. That join produced four separate defects, all the same mistake
     * in a different costume — assuming two {@code IDENTITY} sequences share a number space:
     *
     * <ul>
     *   <li>{@code PlusFeatureService.viewerTeamId} once returned a {@code CTeam} id from a method
     *       every caller compared against {@code Team.id} (P0-18). It withheld the <b>owner's own
     *       players' talent}, because the owner is the one account the seeders gave a {@code tifoCTeam}.</li>
     *   <li>{@code UserRepository.findDistinctManagedTeamIds} selects {@code u.tifoCTeam.id} — CTeam
     *       ids — and is compared against {@code Team.getId()} in {@code TransferService}.</li>
     *   <li>{@code APIController.myMatch}, {@code TeamController.getMatches/getSchedule} and
     *       {@code CountryController.getLeagueMatches} each read {@code user.getTifoCTeam().getId()}
     *       as though it were a {@code Team.id}.</li>
     *   <li>{@code NationalTeamAppointments} walks clubs to users by claiming "the ids are the same
     *       space", which is the same false premise written into a comment.</li>
     * </ul>
     *
     * <p><b>Why {@link #CTeam} stays.</b> It is the legacy footballtextmanager club and the other three
     * modes (basketball, American football, clean sheet) all link through the same pattern. Replacing
     * one field with an id while leaving its neighbours joined by name would not have removed the class
     * of bug; this field is the football answer, and the others still need theirs.
     *
     * <p>Nullable, because a legacy row predates it. {@code ClubOwnershipLinker} backfills it on
     * demand, and every reader falls back to the name-join rather than reporting "no club" for an
     * account that plainly has one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    private org.example.footballmanager.newLogic.model.Team footballTeam;

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
