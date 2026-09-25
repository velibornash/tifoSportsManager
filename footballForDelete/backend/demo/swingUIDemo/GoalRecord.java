package org.example.footballmanager.demo.swingUIDemo;

/** Saved goal information for the match scoreboard and final summary. */
public record GoalRecord(int minute, String scorerId, String scorerLabel, String team) {}
