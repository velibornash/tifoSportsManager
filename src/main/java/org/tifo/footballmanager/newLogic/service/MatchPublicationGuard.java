package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A prepared match must not be readable until it is published (owner, 2026-10-11).
 *
 * <p>The owner's rule computes the whole matchday an hour before kickoff and opens the links at
 * kickoff. The second half of that rule is the part that is easy to get wrong: once the football has
 * been played and stored, the result exists in the {@code match} table, and every endpoint that takes
 * a match id can read it.
 *
 * <p>That is not hypothetical. {@code MatchController} has eight endpoints keyed on {@code matchId},
 * and all eight resolved straight from {@code matchRepository.findById(id)}. A manager holding a
 * prepared match's id — from a log, a URL, a guess, or simply the id sequence — would have read the
 * score an hour before kickoff, and nothing in the fixture list would have stopped him.
 *
 * <p><b>404, not 403.</b> Saying "this match exists but is not available yet" is itself an answer, and
 * an id space of one is short enough to enumerate. A prepared match has to be indistinguishable from a
 * match that does not exist yet.
 *
 * <p>Background readers are deliberately not routed through here. {@code MatchdayScheduler} must be
 * able to find a prepared match in order to publish it, and a world-repair pass must be able to see it
 * to decide it is intact. This guard is for the paths a manager can walk to.
 */
@Service
public class MatchPublicationGuard {

    private final MatchFixtureRepository fixtures;
    private final MatchRepository matches;

    public MatchPublicationGuard(MatchFixtureRepository fixtures, MatchRepository matches) {
        this.fixtures = fixtures;
        this.matches = matches;
    }

    /**
     * The match, if it may be shown.
     *
     * <p>Returning the {@link Match} rather than a filtered id is deliberate. The first version returned
     * {@code null} and the caller did {@code matchRepository.findById(null)}, which is not "not found" —
     * JPA rejects a null id and the endpoint answered <b>500</b>, on a match that was correctly hidden.
     * A leak replaced by a server error is still a leak of one bit: the match exists.
     *
     * @return empty when the match is prepared and unpublished, or when there is no such match
     */
    public Optional<Match> visible(Long matchId) {
        if (matchId == null || isHidden(matchId)) {
            return Optional.empty();
        }
        return matches.findById(matchId);
    }

    /**
     * Whether this match id belongs to a fixture that has been computed but not published.
     *
     * @return true when the match exists only as the hidden half of an hour-before-kickoff computation
     */
    public boolean isHidden(Long matchId) {
        return matchId != null && fixtures.existsPreparedUnpublished(matchId);
    }

}
