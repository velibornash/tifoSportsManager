package org.example.footballmanager.demo.service.model;

/**
 * Threat severity levels — corePrinciples Section 4.4/6.
 * Threat override is proportional to severity.
 */
public enum ThreatLevel {
    NONE(0.0),
    LOW(0.25),
    MEDIUM(0.5),
    HIGH(0.75),
    CRITICAL(1.0);

    private final double severity;

    ThreatLevel(double severity) { this.severity = severity; }
    public double severity() { return severity; }

    public static ThreatLevel fromScore(double score) {
        if (score >= 0.8) return CRITICAL;
        if (score >= 0.55) return HIGH;
        if (score >= 0.3) return MEDIUM;
        if (score > 0.05) return LOW;
        return NONE;
    }
}
