package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    List<Competition> findByCountryId(Long countryId);
    List<Competition> findByTypeAndScope(CompetitionType type, CompetitionScope scope);

    /**
     * Every competition of one type.
     *
     * <p>Added for D1, and the season rollover is the reason it is here: it read every competition in
     * the world — the leagues plus the cups, some 1,500 of them — and kept the leagues in Java.
     *
     * <p><b>This was tried in the cup seeder too and had to be taken back out there</b>, which is the
     * useful part of the note. The cup seeder's {@code findFirst()} picks a cup out of an
     * <em>unordered</em> result, and {@code findAll()} and {@code findByType()} do not return the same
     * order — so narrowing the load silently changed which cup the seeder drew, and broke five
     * assertions. A filter that decides <em>which row wins</em> cannot be pushed into the query without
     * also making the choice deterministic, and making it deterministic is a separate decision.
     */
    List<Competition> findByType(CompetitionType type);

    /**
     * The lowest-id competition of a type whose scope is <b>not</b> INTERNATIONAL.
     *
     * <p><b>"Lowest id" is the whole rule, and it is a rule rather than an accident of iteration.</b>
     * The seeder used to write {@code findAll().stream().filter(type == CUP).findFirst()}, and
     * {@code findFirst} over a table with no ORDER BY is a silent coupling to whatever order the rows
     * come back in. This returns the same cup that rule selects, deterministically, and it reads only
     * the domestic cups rather than the whole competition table.
     *
     * <p>Named for the discriminator rather than for a competition kind, because the same rule finds the
     * national cup and the senior internationals competition — and a method called
     * {@code findFirstDomesticCup} being asked for internationals is the sort of thing that reads like a
     * mistake even when it is correct.
     *
     * <p><b>For the cup it is still only ever one country's</b>, which is a parked owner decision rather than
     * a defect: with one job drawing for forty-eight countries, the lowest-id domestic cup in the
     * database is the only one it can reach. See the board on {@code nationalCup()}.
     *
     * <p><b>The {@code Limit} parameter is not decoration.</b> Spring Data only turns {@code findFirst}
     * into a {@code LIMIT 1} for a <em>derived</em> query method; on an explicit {@code @Query} the
     * name is decoration and the query returned every matching row, so an {@code Optional} return blew
     * up with {@code IncorrectResultSizeDataAccessException: 2 results were returned}.
     *
     * <p><b>The null check is load-bearing, and dropping it was a bug found by an existing test.</b>
     * The Java this replaces reads {@code c.getScope() != CompetitionScope.INTERNATIONAL}, and in Java
     * {@code null != INTERNATIONAL} is <em>true</em> — a cup with no scope set is a domestic cup. The
     * obvious translation, {@code scope <> :scope}, is not equivalent: in SQL {@code NULL <> 'X'} is
     * {@code NULL}, not {@code TRUE}, so every unscoped cup would drop out of the result and the draw
     * would find a different cup. Five assertions in {@code CupFixtureSeederCountryTest} caught it,
     * because that test's cups have no scope.
     */
    @Query("SELECT c FROM Competition c WHERE c.type = :type "
            + "AND (c.scope IS NULL OR c.scope <> :scope) ORDER BY c.id ASC")
    Optional<Competition> findFirstNationalScoped(@Param("type") CompetitionType type,
                                               @Param("scope") CompetitionScope scope,
                                               Limit limit);
    Optional<Competition> findByName(String name);

    /**
     * Every competition with this name, tolerating duplicates.
     *
     * <p>{@link #findByName} throws {@code NonUniqueResultException} on a second row, and the first
     * wrong version of the international cups created global rows under the same names the per-tier
     * ones use — so a lookup by name has to be able to survive them. The World page walks this list
     * rather than scanning every competition in the world to find one name in it.
     */
    List<Competition> findAllByName(String name);

    /**
     * Every league division in one tier, with its country.
     *
     * <p>An index lookup, because the alternative was {@code findAll().stream().filter(...)}: the World
     * page asked for all five tiers fifteen times over and each ask re-read the whole competition table
     * — 1,557 rows, thirty times, for a page load. The country is fetched because the caller groups by
     * it and a lazy proxy would be one query per division.
     */
    @EntityGraph(attributePaths = {"country"})
    List<Competition> findByTypeAndTier(CompetitionType type, Integer tier);

    Optional<Competition> findByNameAndCountryIsoCode(String name, String isoCode);
    List<Competition> findByCountryIsoCodeAndType(String isoCode, CompetitionType competitionType);
    List<Competition> findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc(String isoCode, CompetitionType competitionType);
    List<Competition> findByCountryIsoCodeAndTypeAndTierOrderByDivisionLevelAscIdAsc(String isoCode, CompetitionType competitionType, Integer tier);
}
