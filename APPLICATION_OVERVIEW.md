# TIFO Football Manager - Application Overview & Architecture

## 1. APPLICATION SUMMARY

**TIFO Football Manager** is a comprehensive web-based football club management simulation game built with:
- **Backend**: Spring Boot 3.3.3 with Spring Security & JWT Authentication
- **Frontend**: Vanilla JavaScript (ES6 Modules) with responsive HTML/CSS
- **Database**: JPA/Hibernate with relational model
- **Architecture**: RESTful API with modular feature-based frontend

### Purpose
A football club management simulator where users manage their team, players, matches, training, tactics, finances, transfers, and compete in league competitions across a simulated Serbian football pyramid.

### Current Product Direction
- **Primary Flow**: Realistic visual manager (`dashboard.html` → `realisticDemo.html`) backed by `RealisticMatchEngine`
- **Secondary Flow**: Text-based manager (`tifo.html`) backed by `CleanSheetController` and cleanSheet services
- Legacy viewers remain in the repository for reference and backward compatibility, but they are no longer the main runtime path
- Current engineering focus: stronger football realism, more reliable live match flow, and cleaner round/week integration

### Two Main Application Flows

#### Flow 1: Realistic Visual Manager (Primary)
**Entry Point**: `dashboard.html` → `/start-realistic-demo` → `realisticDemo.html`
**Backend**: `SimulationController.startRealisticDemo()` → `RealisticMatchEngine`
**Features**:
- Visual match simulation with position-aware gameplay
- Live/replay viewer with tick-based playback
- Tactics editor integration
- Advanced match statistics and analytics
- WebSocket real-time updates

#### Flow 2: Text-Based Manager (Secondary)
**Entry Point**: `tifo.html` → `/api/cs/start` → Clean Sheet interface
**Backend**: `CleanSheetController` → `CleanSheetService` → cleanSheet engine
**Features**:
- Text-based interface for classic football management
- Simplified match simulation (results only)
- Traditional league table and fixtures
- Inbox system for match reports and news
- Round-by-round progression

---

## 2. CORE FEATURES & MODULES

### 2.1 **Club Management** 🏆
- **First Team Squad Management**: View/manage player roster with detailed stats
- **Juniors Academy**: Manage young players and development
- **Player Profiles**: Individual player details, career history, skills
- **Staff Directory**: Manage coaching staff and personnel
- **Medical Center**: Track injuries, suspensions, player fitness/condition
- **Finances**: Budget management and financial overview
- **Transfers**: Buying/selling players and transfer negotiations
- **Tactics & Formations**: Set team formation, tactical style, and player positions
  - Support for multiple formations: 4-3-3, 3-5-2, 5-3-2, etc.
  - 7 tactical styles: BALANCED, ATTACKING, DEFENSIVE, COUNTER, POSSESSION, HIGH_PRESS, DIRECT

### 2.2 **Match System** ⚽
- **Match Simulation**: Advanced realistic match engine with detailed event simulation
  - Primary engine: `RealisticMatchEngine`
  - Tick-based replay persistence with metadata/chunk loading
  - Position-aware decision making (passing, shooting, dribbling)
  - Tactics-editor-driven movement with limited tactical overrides
  - Duels, offsides, assists, and set-piece events
- **Match Types**:
  - League matches (scheduled competitions)
  - Friendlies (practice matches)
  - Cup matches
  - International matches
- **Match Details**:
  - Live lineups (starting 11 + substitutes)
  - Match statistics (shots, possession, fouls, etc.)
  - Event history (goals, cards, injuries, substitutions, VAR reviews)
  - Match reports with analysis
  - Player ratings per match
  - Replay metadata + chunk playback in the realistic viewer

### 2.3 **Training System** 📊
- **Training Setup**: Configure weekly training sessions
  - General training (all players)
  - Positional training (by player position)
  - Specialized skill training (technical, tactical, physical)
  - Advanced training for high-performing players
- **Training Reports**: Weekly reports on player development
- **Player Progression**: Track skill improvement over time
  - Skills: Pace, Passing, Shooting, Dribbling, Defense, Physical, Mental
  - Skill progression graph over weeks/seasons
  - Condition/Fatigue tracking
- **Fatigue Management**: Monitor player fatigue levels to prevent injuries

### 2.4 **League & Competition** 🏅
- **League Table**: Standing with points, matches played, goal difference
- **League Schedule**: Fixture list with dates and results
- **League Results**: Historical match results
- **Competition Types**:
  - Domestic League (Serbian football pyramid)
  - Cups
  - International competitions
- **Fixtures**: Scheduled matches with opponent info and predictions
- **Season Management**: Multiple seasons with year progression

### 2.5 **Analytics & Statistics** 📈
- **Player Statistics**: Individual player performance metrics
  - Goals, assists, minutes played, ratings
- **Team Statistics**: Overall team performance data
- **Top Scorers & Assists**: League-wide leaderboards
- **Analytics Dashboard**: Advanced team analytics and insights
- **Events Tracking**: Comprehensive event logs (goals, cards, injuries, VAR reviews)
- **Schedule Insights**: Analysis of upcoming fixtures and difficulty

### 2.6 **Community & Social** 💬
- **Community Chat**: Live messaging with other players
- **Forum**: Discussion board for community interaction
- **Country Management**: National team features (optional)
- **League Actions**: 
  - Play scheduled match
  - Simulate other results in current round
  - View community activity

### 2.7 **Legacy Match Views** 🎩
Legacy/alternative match visualization interfaces remain in the repository for reference and backward compatibility, but the intended long-term direction is the realistic live/replay viewer.

---

## 3. TECHNICAL ARCHITECTURE

### 3.1 Frontend Structure (dashboard.html & Connected Files)

#### Main HTML Files
```
dashboard.html          - Primary dashboard/hub page (Realistic Manager)
├── login.html         - Authentication entry point
├── index.html         - Landing/home page
├── realisticDemo.html - Primary live/replay match viewer (Realistic Manager)
├── zox-match-preview.html - Match preview analysis
└── tifo.html          - Text-based manager interface (Clean Sheet)
```

#### Text-Based Manager Frontend (tifo.html)
```
tifo.html (200+ lines) - Text-based manager interface
├── tifo.js (2125 lines) - Text-based game logic and UI
├── tifo.css            - Text-based styling
└── Clean Sheet API integration (/api/cs/*)
```

Archived/legacy viewers are intentionally not listed as the primary user path anymore.

#### CSS Modules (3 main imports)
```
css/dashboard.css (3909 lines)
├── css/dashboard/layout.css      - Grid layout, responsive design
├── css/dashboard/components.css  - Buttons, cards, modals
└── css/dashboard/overrides.css   - Theme overrides

Plus specialized stylesheets:
- login.css             - Login/auth styling
- style.css            - Global styles
- demo.css             - Demo page styling
- key-events.css       - Key events styling
- tifo.css             - TIFO viewer styling
- zox-match-preview.css - Match preview styling
```

#### JavaScript Modules (Modular ES6)
```
Main Entry Points:
- auth.js              - JWT authentication, token management
- dashboard.js (865 lines) - Dashboard logic, state management
- pages.js (5223 lines) - Page router, feature orchestration
- app.js               - DOM initialization, sidebar setup
- clock.js             - Game clock (time/date display)
- demo.js              - Demo/tutorial flow
- sidebar.js           - Sidebar navigation logic

Renderers:
- pages-renderers.js (1102 lines) - HTML generation for pages

Feature Modules (pages/features/):
- academy.js           - Juniors/academy feature
- team.js              - Team squad management
- matches.js           - Match results/fixtures
- club-management.js   - Club operations
- community.js         - Community/chat
- training.js          - Training system
- staff-directory.js   - Staff management

Utilities:
- ui/components.js     - Reusable UI components
- cleanSheet.js        - Clean sheet match viewer
- tifo.js (2000+ lines) - TIFO match visualization
- zox-match-preview.js - Match preview analysis
- realisticDemo.js     - Primary realistic live/replay runner
```

### 3.2 Backend Architecture (Spring Boot)

#### Controllers (20+ endpoints)
```
APIController.java           - General API endpoints
MatchController.java         - Match CRUD, simulations, events
TeamController.java          - Team management, tactics, lineups
PlayerController.java        - Player profiles, stats
TrainingController.java      - Training setup, reports
TransferController.java      - Transfer operations
JuniorController.java        - Academy management
StatsController.java         - Statistics endpoints
MatchPlayerStatsController.java - Individual player match stats
LineupController.java        - Match lineups
CommunityController.java     - Chat, forum, community
CompetitionController.java   - League/cup management
CountryController.java       - National team features
SeasonController.java        - Season management
StadiumController.java       - Stadium data
AdminController.java         - Administrative operations
UserController.java          - User management
ZoxViewController.java       - Match preview analysis
DummyDataController.java     - Test data generation
SimulationController.java    - Match simulation engines (Realistic Manager)
CleanSheetController.java    - Text-based manager endpoints (/api/cs/*)
```

#### Match Simulation Engines
```
MatchEngine.java             - Match creation, fixture alignment, fallback/basic simulation
RealisticMatchEngine.java    - Primary realistic simulation engine
  - Tick-based replay generation
  - Tactics-editor-driven positioning
  - Position-aware decisions
  - Duels, offsides, assists, transitions
ZoxReplayService.java        - Replay metadata/chunk loader for realistic viewer
RuntimeSaveToDB.java         - Match finalization + replay persistence

CleanSheet Engine (Text-Based Manager):
- CleanSheetService.java     - Text-based game logic and state management
- CleanSheetController.java  - REST endpoints for text-based interface
- cleanSheet models/state    - Game state and data models
```

#### Services (15+ services)
```
- TeamTacticsService          - Tactics management
- TeamMedicalService          - Injury/fitness tracking
- LeagueMilestoneService      - Competition milestones
- TrainingProgressionService  - Training progression logic
- PlayerSkillProgressionService - Skill development
- MatchDetailService          - Match detail fetching
- MatchReportService          - Match report generation
- ScheduleInsightService      - Schedule analysis
- SeasonService               - Season management
- And more...
```

#### Models (30+ entities)
```
Core:
- User, Player, Team
- Match, MatchFixture, MatchRuntime
- Competition, League, Season, SeasonPhase
- Lineup, Training, Transfer

Events:
- MatchEvent (abstract)
  - GoalEvent
  - YellowCardEvent, RedCardEvent
  - InjuryEvent
  - SubstitutionEvent
  - ShotOnTargetEvent
  - PenaltyEvent
  - VARReviewEvent
  - MatchEndedEvent

Support:
- SkillSet, Skills, SkillLevel
- Tactics, TeamTrainingSetup
- Country, Stadium, Crowd, Referee
- Ball, MatchTickState, MatchPlayerStats
```

#### Repositories (JPA)
- Core CRUD repositories for all entities
- Custom query methods for complex searches
- Match queries, player queries, team queries, etc.

### 3.3 Data Flow

```
User Login (JWT)
    ↓
Dashboard Initialization
    ├─ Load user team data
    ├─ Load season/competition info
    └─ Initialize sidebar navigation
    ↓
Page Loading (Dynamic SPA)
    ├─ Fetch data from REST API
    ├─ Render UI using JavaScript
    └─ Bind event listeners
    ↓
Match Simulation
    ├─ Dashboard -> /start-realistic-demo
    ├─ SimulationController prepares scheduled user fixture
    ├─ SimulationService runs RealisticMatchEngine
    ├─ RuntimeSaveToDB persists match/events/ticks/stats
    ├─ ZoxReplayService exposes replay metadata/chunks
    └─ realisticDemo.html renders live/replay playback
    ↓
Training System
    ├─ Configure training setup
    ├─ Apply training effects
    ├─ Generate weekly reports
    └─ Track skill progression
```

---

## 4. KEY FEATURES BY MODULE

### Authentication & Security
- JWT-based token authentication
- Spring Security integration
- Token validation on each request
- Automatic logout on session expiry
- Role-based access control (Coach, Admin, etc.)

### User Experience
- Responsive design (mobile + desktop)
- Mobile sidebar navigation with accordion menus
- Desktop full-featured sidebar
- Dark theme (green accent color: #4CAF50)
- Live game clock showing current in-game time

### Match System Details
- **Realistic Simulation**: Primary runtime path for the user-managed match
- **Position Awareness**: Players move from tactics-editor target zones with situational overrides
- **Replay System**: Persisted tick states + chunked playback endpoints
- **Duels System**: Physical confrontations, blocks, saves, offsides, restarts
- **Event Generation**: Goals, shots, fouls, injuries, cards, substitutions, VAR
- **Rating System**: Individual player performance ratings (1-100)
- **Match Statistics**: Possession, shots on target, fouls, corner kicks
- **Known realism gaps**:
  - match flow is still too event-driven vs possession-driven
  - role identity is weaker than it should be
  - defensive coordination needs better cover/track/hold behavior
  - shot selection still creates some low-quality volume spikes

### Training System Details
- **General Training**: Base training for all squad players
- **Positional Training**: Skill focus by player position
- **Advanced Training**: High-level skill enhancement
- **Physical Training**: Stamina and condition management
- **Report Generation**: Weekly progress reports with graphs

### Tactics System
- **7 Tactical Styles**: Each affecting team play
- **Formation Support**: Multiple formation options
- **Lineup Editor**: Visual squad lineup setter
- **Formation Validation**: Player position compatibility checking

---

## 5. IMPORTANT NOTES FOR TICKET CREATION

### Current Known Risks
- Live realistic match can affect round/week progression if finalization or replay persistence fails.
- Session/auth handling should be rechecked after long-running season-flow actions.
- Mobile match viewer is usable but still needs focused QA and further simplification.
- Documentation describing legacy viewers as primary should be treated as outdated unless explicitly marked legacy.

### Connected Files Summary
```
Main Entry: dashboard.html (137 lines)
   ↓
CSS: 8 stylesheets (3909 main lines)
   ↓
JS: 20+ modules (15,000+ total lines)
   ↓
Backend: 20 controllers, 30+ models
         15+ services, match engines
```

### Key Dependencies
- **Frontend**: Vanilla JS (ES6 modules), no framework
- **Backend**: Spring Boot 3.3.3, Spring Security, JPA/Hibernate
- **Database**: Relational (SQL)
- **Authentication**: JWT tokens
- **Match Simulation**: Complex event-driven engine

### Frontend Module Dependencies
```
pages.js (main page router)
├─ Imports from pages-renderers.js
├─ Imports from auth.js
├─ Imports from 7 feature modules
├─ Uses data from backend controllers
└─ Updates dashboard.js state

Each feature module imports:
├─ authFetch from auth.js
└─ Utility functions from pages-renderers.js
```

### Performance Considerations
- Large pages.js (5223 lines) - candidate for refactoring
- CSS dashboard.css (3909 lines) - modular but large
- Complex match simulation in RealisticMatchEngine
- Real-time clock updates for in-game UI

---

## 6. FILE ORGANIZATION FOR TICKETS

### By Functional Area
1. **Authentication** (auth.js, login.html, register.html)
2. **Dashboard Core** (dashboard.html, dashboard.js, dashboard.css)
3. **Team Management** (team.js feature, players, formations)
4. **Match System** (MatchController, RealisticMatchEngine, match visualization)
5. **Training** (TrainingController, training.js feature)
6. **League/Competition** (CompetitionController, league pages)
7. **Community** (CommunityController, forum, chat)
8. **UI/UX** (sidebar.js, responsive design, mobile support)

### By Complexity
- **Simple**: Static pages, UI fixes, CSS styling
- **Medium**: Single feature enhancement, new page layout
- **Complex**: Match simulation logic, training progression, new feature
- **Critical**: Authentication, data persistence, match simulation

---

## 7. NEXT STEPS

Ready to create developer tickets for work done so far. Please specify which area you'd like to start with:
1. **Dashboard & Navigation** - Core UI/UX
2. **Club Management** - Team, players, staff
3. **Match System** - Simulation, visualization
4. **Training System** - Setup, progression, reports
5. **League & Competition** - Tables, fixtures, standings
6. **Community Features** - Chat, forum
7. **UI/UX Polish** - Responsive design, mobile, themes

Or we can create tickets covering **all modules** in sequence.

### Recommended Immediate Track
1. Stabilize realistic live match -> fixture/week integration
2. Improve football realism in `RealisticMatchEngine`
3. Add stronger manual and automated coverage for live match recovery and season progression
