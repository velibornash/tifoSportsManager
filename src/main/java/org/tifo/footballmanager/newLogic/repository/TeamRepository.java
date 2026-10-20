package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.Team;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {
    @Override
    @EntityGraph(attributePaths = {"competition", "country"})
    Optional<Team> findById(Long id);

    @EntityGraph(attributePaths = {"stadium"})
    Optional<Team> findWithStadiumById(Long id);

    Optional<Team> findByName(String name);

    /**
     * Every club with this name, for code that must survive a duplicate.
     *
     * <p>{@link #findByName} returns {@code Optional<Team>} and therefore <b>throws</b> when two
     * clubs share a name — and two clubs sharing a name is explicitly allowed. Anything that
     * resolves a club from a user-supplied or stored string must use this instead and decide
     * explicitly what to do about the ambiguity, rather than crashing or silently picking one.
     */
    List<Team> findAllByNameIgnoreCase(String name);

    /**
     * The names of one country's clubs, and nothing but the names.
     *
     * <p>Added for the seeding bottleneck (owner, 2026-10-07). The builder asked
     * {@link #findAllByNameIgnoreCase} once per club to decide whether it already existed, which is a
     * full scan of a 14,880-row table per club — measured at 10 ms a time, or about 149 seconds for a
     * fresh world. Asking once per country and testing membership in memory turns 14,880 round trips
     * into 48.
     *
     * <p>A projection, not {@code findByCountryId} returning entities: nothing here needs a managed
     * {@code Team}, and loading 310 full rows to read one column from each is the same mistake in a
     * smaller costume.
     */
    @Query("select t.name from Team t where t.country.id = :countryId")
    List<String> findNamesForCountry(@Param("countryId") Long countryId);

    /**
     * Every club of one country, by its ISO prefix.
     *
     * <p>Club names are {@code <ISO> <short division> FCnn}, so the ISO code is a prefix and this is
     * an indexed query. The alternative — {@code findAll()} then filtering in Java — is what made the
     * first run of the simulated-world test take fifteen minutes: it drags the whole world's clubs into
     * memory once per assertion, and there are fourteen thousand of them.
     */
    List<Team> findByNameStartingWithIgnoreCase(String prefix);

    long countByCompetition(Competition league);

    /**
     * Every club that plays in a league division, with its division fetched.
     *
     * <p>For the Elo replay, which needs two things per club and must not ask the database for either
     * of them fifteen thousand times.
     *
     * <p><b>Membership is the division, not the type flag.</b> A national side is
     * {@code type = NATIONAL_TEAM} and has <em>no competition at all</em> — see
     * {@code NationalTeamSeeder}, "no club, no competition, no budget". So a club is anything whose
     * division is a league, and the join below rules out every national side for free. Relying on
     * {@code type} instead would quietly drop clubs: {@code PyramidBuilder} creates every club in the
     * world and <b>never sets {@code type}</b>, so fifteen thousand of them are null.
     *
     * <p>The fetch join is not decoration either. {@code Team.competition} is LAZY, so a plain
     * {@code findAll()} plus a {@code getTier()} call is one extra query per distinct division — about
     * 1,500 of them, every time the replay runs.
     */
    @Query("SELECT t FROM Team t LEFT JOIN FETCH t.competition c "
            + "WHERE c.type = org.tifo.footballmanager.newLogic.model.CompetitionType.LEAGUE "
            + "ORDER BY t.id ASC")
    List<Team> findAllClubsWithDivision();

    List<Team> findAllByTypeOrderByIdAsc(CompetitionTeamType type);

    List<Team> findByCompetitionId(Long competitionId);
    List<Team> findByCountryId(Long countryId);
    List<Team> findByHumanControlledTrue();

    @Query("SELECT t FROM org.tifo.footballmanager.newLogic.model.Team t WHERE t.competition.id = :competitionId AND t.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB")
    List<Team> findClubsByCompetitionId(@Param("competitionId") Long competitionId);

    @Query("select t from Team t where t.type is null or t.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB order by t.id asc")
    List<Team> findClubTeamsForOperations();

    /**
     * The clubs that have an economy (owner, 2026-10-11).
     *
     * <p><b>Human teams, and clubs in an active country. Nothing else.</b> The owner: *"Simulirane
     * lige i AI klubovi nemaju ekonomiju i to, tu su samo za internacionalne mečeve, bot klubovi i
     * za NT bot reprezentacije. Samo human timovi (ili aktivne lige, šta god od to dvoje) imaju
     * ekonomiju."*
     *
     * <p>Measured on the owner's world: 14,880 clubs, of which only **310** are in an active country
     * and have one human manager among them. The other 14,570 are 47 {@code SIMULATED} countries,
     * built as names and tables so their national sides can be drawn against. Settling them was
     * inventing money — 13,630 clubs with no squad earned <b>199,430,400 in one week</b> from
     * merchandising, because with no wages, no upkeep and no gate there is nothing to set it against.
     *
     * <p>Two things are deliberately still true of those clubs, and this query is only about money:
     * their players get the default pace training every week, and their national teams can still be
     * drawn against. Excluding them here must not quietly exclude them anywhere else.
     *
     * <p><b>Null-safe on purpose.</b> The predicate reads {@code t.country is null or ... ACTIVE} rather
     * than a bare equality, because a bare one silently excludes a club that has no country row: it
     * is in no simulated league, so it is not what this filter is for, and dropping its economy without
     * saying so is the kind of quiet hole this query exists to close. Every club
     * {@code PyramidBuilder} creates has a country, so this costs nothing and buys the absence of a
     * trap.
     *
     * <p>{@code type is null} is in the predicate for the same reason it is in
     * {@link #findClubTeamsForOperations()}: {@code PyramidBuilder} creates every club in the world
     * and never sets {@code type}, so an {@code = CLUB} test matches nothing at all.
     */
    @Query("select t from Team t "
            + "where (t.type is null or t.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB) "
            + "and (t.country is null "
            + "     or t.country.state = org.tifo.footballmanager.newLogic.model.CountryState.ACTIVE "
            + "     or t.humanControlled = true) "
            + "order by t.id asc")
    List<Team> findClubsWithAnEconomy();

    /**
     * One country's clubs, by what they are rather than by which of them turn up.
     *
     * <p>{@link #findClubTeamsForOperations()} answers "every club in the world", and the national-squad
     * seeder wanted "this country's clubs" — so it took the whole club table and kept the rows whose
     * country id matched. That is 14,880 clubs at the scale this project targets, loaded to select the
     * 310 that belong to one country, and the question is asked of forty-eight countries as the world's
     * squads are drawn.
     *
     * <p><b>The predicate is {@code findClubTeamsForOperations}'s, unchanged, plus the country.</b> It
     * has to be that exact predicate: {@code type is null} is not a detail. {@code PyramidBuilder}
     * creates every club in the world and never sets {@code type}, so an {@code = CLUB} test alone
     * matches no club at all and a country's squad would silently come out empty.
     */
    @Query("select t from Team t where t.country.id = :countryId "
            + "and (t.type is null or t.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB) "
            + "order by t.id asc")
    List<Team> findClubTeamsForCountry(@Param("countryId") Long countryId);

    /**
     * One country's clubs by ISO code rather than by row id.
     *
     * <p>{@link #findClubTeamsForCountry(Long)} is the same question asked with a different key. This
     * one exists for callers that only know the ISO code — the transfer market filters by the manager's
     * country, and asking for a country id there would have meant loading every club in the world just
     * to find which rows matched.
     *
     * <p>{@code upper(...)} rather than {@code =}, because the Java side this replaces compared with
     * {@code equalsIgnoreCase}. Passing the code already upper-cased keeps both spellings working.
     */
    @Query("select t from Team t where upper(t.country.isoCode) = :isoCode "
            + "and (t.type is null or t.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB) "
            + "order by t.id asc")
    List<Team> findClubTeamsForCountryIso(@Param("isoCode") String isoCode);

    /**
     * Every team of one kind - club or national side.
     *
     * <p>findClubTeamsForOperations returns clubs only, which is right for club work and quietly
     * wrong for anything national: asking it for national teams returns nothing, and "nothing" reads
     * the same as "no squads have been seeded".
     */
    List<Team> findByType(org.tifo.footballmanager.newLogic.model.CompetitionTeamType type);

    /**
     * AI clubs in a country that play in a higher tier than the given one (owner, 2026-10-18).
     *
     * <p>The pool a job offer is drawn from. \"Higher tier\" is a smaller number — tier 1 is the top — so
     * the comparison is {@code <}, and it only means anything inside one country, which is why the
     * country is part of the query rather than applied afterwards.
     *
     * <p>A club with no competition is excluded by the join, which is right: a club with no league has
     * no tier and cannot be \"above\" anything.
     */
    @Query("""
select t from Team t
            where t.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB
              and t.humanControlled = false
              and t.competition.country.id = :countryId
              and t.competition.tier is not null
              and t.competition.tier < :tier
            """)
    List<Team> findAiClubsInHigherTiers(@Param("countryId") Long countryId, @Param("tier") Integer tier);

    /**
     * The ISO codes of every country that has at least one club - a list of strings, not a list of teams.
     *
     * <p>This exists because the country catalog asked {@code teamRepository.findAll()} and then mapped
     * every team to its country. That is a public, unauthenticated endpoint - the registration page calls
     * it before anyone has logged in - and it was materialising every club in the world to answer a
     * yes/no question per country.
     *
     * <p>The board blamed an eager {@code Country.clubs}, which is not quite it: {@code clubs} is LAZY,
     * while {@code seniorNationalTeam} and {@code u21NationalTeam} are {@code @OneToOne} with no fetch
     * attribute and so <b>are</b> eager, so touching any {@code Country} fetched both national sides.
     * This query never loads a {@code Country} or a {@code Team} entity at all.
     */
    @Query("""
            select distinct t.country.isoCode
            from org.tifo.footballmanager.newLogic.model.Team t
            where t.country is not null and t.country.isoCode is not null and trim(t.country.isoCode) <> ''
            """)
    List<String> findDistinctIsoCodesOfCountriesWithClubs();

    /**
     * Clubs whose name contains {@code fragment}, shortest name first.
     *
     * <p>Owner, 2026-10-14: the friendly picker searches the whole world rather than one country, so a
     * name filter is needed that does not drag every club in the pyramid into memory. Ordered by name
     * length so "Kikinda" comes before "OFK Kikinda 1912", which is what a manager typing a partial name
     * means, and paged in the query rather than in Java.
     */
    @Query("SELECT t FROM Team t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :fragment, '%')) "
            + "ORDER BY LENGTH(t.name), t.name")
    List<Team> findByNameFragment(@Param("fragment") String fragment, Pageable pageable);

    /**
     * A club's position in the world by Elo, strongest first, counting only clubs.
     *
     * <p>Owner, 2026-10-14: a club profile has to say where the club stands, and "position" only means
     * something against something. Counted over clubs rather than over every team, because a national
     * side has no club Elo to place and including them would make every club's number smaller by the
     * number of nations. Ties share a position, the way a league table shows them.
     */
    @Query("SELECT COUNT(c) + 1 FROM Team c "
            + "WHERE c.type = org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB "
            + "AND c.eloRating IS NOT NULL "
            + "AND (c.eloRating > (SELECT t.eloRating FROM Team t WHERE t.id = :teamId) "
            + "  OR (c.eloRating = (SELECT t.eloRating FROM Team t WHERE t.id = :teamId) AND c.id < :teamId))")
    Long findWorldPositionByElo(@Param("teamId") Long teamId);
}
