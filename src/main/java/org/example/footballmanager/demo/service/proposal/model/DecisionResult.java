package org.example.footballmanager.demo.service.proposal.model;

import java.util.List;

/**
 * Evaluation result: the CHOSEN option plus ALL scored options,
 * so a debug log can show every alternative and its score.
 */
public class DecisionResult {
    private final DecisionOption chosen;
    private final List<DecisionOption> options;

    public DecisionResult(DecisionOption chosen, List<DecisionOption> options) {
        this.chosen = chosen;
        this.options = List.copyOf(options);
    }

    public DecisionOption getChosen() { return chosen; }
    public List<DecisionOption> getOptions() { return options; }
}