package org.example.footballmanager.util;

import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.model.UserRole;
import org.example.footballmanager.repository.CompetitionEntryRepository;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.CountryRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.PromotionRuleRepository;
import org.example.footballmanager.repository.SeasonCompetitionRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.TeamTacticsProfileRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.ResetService;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.YouthAcademyService;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.players.SquadNumberAssigner;
import org.example.footballmanager.util.teams.TeamFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseInitializerTest {

    @Mock private CountryRepository countryRepository;
    @Mock private CompetitionRepository competitionRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private SeasonRepository seasonRepository;
    @Mock private SeasonCompetitionRepository seasonCompetitionRepository;
    @Mock private CompetitionEntryRepository competitionEntryRepository;
    @Mock private PromotionRuleRepository promotionRuleRepository;
    @Mock private TeamTacticsProfileRepository teamTacticsProfileRepository;
    @Mock private PlayerFactory playerFactory;
    @Mock private TeamFactory teamFactory;
    @Mock private PasswordEncoder encoder;
    @Mock private ResetService resetService;
    @Mock private SeasonService seasonService;
    @Mock private YouthAcademyService youthAcademyService;
    @Mock private SquadNumberAssigner squadNumberAssigner;

    @InjectMocks private DatabaseInitializer databaseInitializer;

    @Test
    void applyOwnerIdentityPreservesExistingPassword() {
        User owner = new User();
        owner.setPassword("existing-password-hash");
        owner.setRole(null);

        Team ownerTeam = new Team();
        ownerTeam.setName("OFK Omladinac");

        databaseInitializer.applyOwnerIdentity(owner, ownerTeam);

        assertEquals("velibor@example.com", owner.getUsername());
        assertEquals("velibor@example.com", owner.getEmail());
        assertEquals("existing-password-hash", owner.getPassword());
        assertSame(ownerTeam, owner.getTeam());
    }
}
