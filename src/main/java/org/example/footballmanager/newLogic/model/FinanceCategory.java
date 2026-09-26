package org.example.footballmanager.newLogic.model;

/**
 * Every line that can appear in a club's ledger.
 *
 * <p>Split by direction and by owner so the Finances page can group without string matching, and so
 * a club's wage bill and its stadium bill are separately answerable — which is the difference
 * between a manager who understands a budget and one who does not.
 */
public enum FinanceCategory {

    // --- income ---
    GATE_REVENUE(true, "Gate receipts"),
    BROADCAST(true, "Broadcast income"),
    PRIZE_MONEY(true, "Prize money"),
    SPONSORSHIP(true, "Sponsorship"),
    MERCHANDISING(true, "Merchandising"),
    TRANSFER_FEE_IN(true, "Transfer fees received"),
    LOAN_IN(true, "Loan income"),

    // --- costs ---
    WAGES(false, "Player wages"),
    STAFF_WAGES(false, "Staff wages"),
    FACILITY_UPKEEP(false, "Facility upkeep"),
    PITCH_MAINTENANCE(false, "Pitch maintenance"),
    TRANSFER_FEE_OUT(false, "Transfer fees paid"),
    LOAN_OUT(false, "Loan payments");

    private final boolean income;
    private final String label;

    FinanceCategory(boolean income, String label) {
        this.income = income;
        this.label = label;
    }

    /** True if this category adds to the budget. */
    public boolean isIncome() {
        return income;
    }

    /** Human label for the Finances page. */
    public String label() {
        return label;
    }

    /** Sign convention for a stored amount: income positive, cost negative. */
    public double sign(double amount) {
        return income ? Math.abs(amount) : -Math.abs(amount);
    }
}
