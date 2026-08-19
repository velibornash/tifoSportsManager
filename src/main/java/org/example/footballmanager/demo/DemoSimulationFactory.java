package org.example.footballmanager.demo;

/**
 * DemoSimulationFactory — ODGOVORNOST: SKLAPANJE GRAFA SIMULACIJE.
 *
 * Vlasnik je konstrukcije simulacionog steka (igraci, lopta, engin), koju
 * demo/UI sloj ne sme da zna. UI samo poziva {@link #create(DemoScenario)} i
 * dobija spreman {@link SimulationEngine} — kako se interne komponente sklapaju
 * ne zanima ga.
 *
 * Sastav je IDENTICAN kao ranije u {@link TacticalGridDemo}: igraci iz
 * {@link DemoPlayerFactory}, lopta na pocetnoj poziciji scenarija i
 * {@code new SimulationEngine(players, ball, new TacticsRules())}
 * (bez seed-a — nasumicno ponasanje kao do sada).
 */
public class DemoSimulationFactory {

    private final DemoPlayerFactory playerFactory;

    public DemoSimulationFactory() {
        this(new DemoPlayerFactory());
    }

    public DemoSimulationFactory(DemoPlayerFactory playerFactory) {
        this.playerFactory = playerFactory;
    }

    /** Sklapa kompletan simulation stek za zadati scenario (default pravila). */
    public SimulationEngine create(DemoScenario scenario) {
        return create(scenario, new TacticsRules());
    }

    /** Sklapa simulation stek sa eksplicitnim taktickim pravilima (testovi). */
    public SimulationEngine create(DemoScenario scenario, TacticsRules tacticsRules) {
        java.util.List<Player> players = playerFactory.createPlayers(scenario);
        Ball ball = new Ball(scenario.getBallStartPosition(), scenario.getBallStartPosition());
        return new SimulationEngine(players, ball, tacticsRules);
    }

    /** Simulation stack with the {@link ThreatEngine} defensive/offside layer enabled (live demo play). */
    public SimulationEngine createWithThreatOverride(DemoScenario scenario) {
        return createWithThreatOverride(scenario, new TacticsRules());
    }

    /** Simulation stack with the {@link ThreatEngine} defensive/offside layer enabled. */
    public SimulationEngine createWithThreatOverride(DemoScenario scenario, TacticsRules tacticsRules) {
        java.util.List<Player> players = playerFactory.createPlayers(scenario);
        Ball ball = new Ball(scenario.getBallStartPosition(), scenario.getBallStartPosition());
        return new SimulationEngine(players, ball, tacticsRules, new java.util.Random(), true);
    }
}
