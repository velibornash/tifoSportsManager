package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Team;
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
            + "WHERE c.type = org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE "
            + "ORDER BY t.id ASC")
    List<Team> findAllClubsWithDivision();

    List<Team> findAllByTypeOrderByIdAsc(CompetitionTeamType type);

    List<Team> findByCompetitionId(Long competitionId);
    List<Team> findByCountryId(Long countryId);
    List<Team> findByHumanControlledTrue();

    @Query("SELECT t FROM org.example.footballmanager.newLogic.model.Team t WHERE t.competition.id = :competitionId AND t.type = org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB")
    List<Team> findClubsByCompetitionId(@Param("competitionId") Long competitionId);

    @Query("select t from Team t where t.type is null or t.type = org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB order by t.id asc")
    List<Team> findClubTeamsForOperations();

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
            + "and (t.type is null or t.type = org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB) "
            + "order by t.id asc")
    List<Team> findClubTeamsForCountry(@Param("countryId") Long countryId);

    /**
     * Every team of one kind - club or national side.
     *
     * <p>findClubTeamsForOperations returns clubs only, which is right for club work and quietly
     * wrong for anything national: asking it for national teams returns nothing, and "nothing" reads
     * the same as "no squads have been seeded".
     */
    List<Team> findByType(org.example.footballmanager.newLogic.model.CompetitionTeamType type);

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
            from org.example.footballmanager.newLogic.model.Team t
            where t.country is not null and t.country.isoCode is not null and trim(t.country.isoCode) <> ''
            """)
    List<String> findDistinctIsoCodesOfCountriesWithClubs();
}
