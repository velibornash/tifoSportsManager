package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.newLogic.service.ScheduleInsightService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The next-match prediction as the pages receive it.
 *
 * <p>The same eight fields, in the same order, were built twice — once in {@code TeamController} and once,
 * byte for byte, in {@code CountryController}. Two pages show the same prediction of the same fixture, so
 * two builders are two answers waiting to differ: the day one of them gains a field, the other page shows
 * a fixture without it.
 *
 * <p>{@link LinkedHashMap} on purpose: the key order is the order the page's own renderer looks for, and a
 * {@code HashMap} would make that a coincidence of hashing rather than a decision.
 */
public final class PredictionPayload {

    private PredictionPayload() {
    }

    public static Map<String, Object> toMap(ScheduleInsightService.Prediction prediction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("homeWinProbability", prediction.homeWinProbability());
        payload.put("drawProbability", prediction.drawProbability());
        payload.put("awayWinProbability", prediction.awayWinProbability());
        payload.put("expectedHomeGoals", prediction.expectedHomeGoals());
        payload.put("expectedAwayGoals", prediction.expectedAwayGoals());
        payload.put("mostLikelyResult", prediction.mostLikelyResult());
        payload.put("confidence", prediction.confidence());
        payload.put("analysis", prediction.analysis());
        return payload;
    }
}
