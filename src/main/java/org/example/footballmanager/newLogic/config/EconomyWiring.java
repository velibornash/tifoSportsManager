package org.example.footballmanager.newLogic.config;

import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.service.JuniorSchoolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the one piece of state the junior school needs from outside itself (owner, 2026-10-11).
 *
 * <p>The academy's cost is a static function because the weekly finance tick calls it for every club
 * in the world — 14,880 of them — and threading a service reference through that path for one
 * multiplication would couple the ledger to a feature it does not own.
 *
 * <p>It needs a squad to cost anything, though: the academy is priced against the first team's wage
 * bill, which is what makes it sit in the same economy as everything else rather than in a scale of
 * its own. That needs a repository, and a static needs it set.
 *
 * <p><b>Done by a container hook rather than by a setter anyone might call.</b> A public setter on a
 * service is a thing that can be called twice, from nowhere, with a mock in a test that then leaks
 * into the next one. This runs once, at startup, on the real bean.
 */
@Configuration
public class EconomyWiring {

    @Autowired
    public EconomyWiring(PlayerRepository players) {
        JuniorSchoolService.usePlayerRepository(players);
    }
}
