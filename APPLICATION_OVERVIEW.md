# TIFO Football Manager - Application Overview & Architecture

## 1. APPLICATION SUMMARY

**TIFO Football Manager** is a comprehensive web-based football club management simulation game built with:
- **Backend**: Spring Boot 3.3.3 with Spring Security & JWT Authentication
- **Frontend**: Vanilla JavaScript (ES6 Modules) with responsive HTML/CSS
- **Database**: JPA/Hibernate with relational model
- **Architecture**: RESTful API with modular feature-based frontend

### Purpose
A football club management simulator where users manage their team, players, matches, training, tactics, finances, transfers, and compete in league competitions across a simulated Serbian football pyramid.

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
  - 90-minute matches with event-based simulation (1-3 events per minute)
  - Position-aware decision making (passing, shooting, dribbling)
  - Duels and collisions when players are close
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

### 2.7 **TIFO Old School** 🎩
Legacy/alternative match visualization interface for viewing matches and match events

---

## 3. TECHNICAL ARCHITECTURE

### 3.1 Frontend Structure (dashboard.html & Connected Files)

#### Main HTML Files
```
dashboard.html          - Primary dashboard/hub page
├── login.html         - Authentication entry point
├── index.html         - Landing/home page
├── tifo.html          - Alternative match viewer
├── cleanSheetTifo.html - Clean sheet match visualization
├── match_visualisation.html - Match visualization
├── key-events.html    - Key events/moments viewer
├── realisticDemo.html - Demo match viewer
└── zox-match-preview.html - Match preview analysis
```

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
- key-events.js        - Key events viewer
- realisticDemo.js     - Demo match runner
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
SimulationController.java    - Match simulation engines
```

#### Match Simulation Engines
```
MatchEngine.java             - Standard match creation & basic simulation
RealisticMatchEngine.java    - Advanced realistic simulation (3000+ lines)
  - Position-aware decision making
  - Tactical profiles
  - Player duels
  - Event generation (90 min, 1-3 events/min)
  
DemoSimulator.java           - Demo match simulation
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
    ├─ Create match with teams & players
    ├─ Run RealisticMatchEngine
    ├─ Generate match events
    ├─ Store results & stats
    └─ Display match report
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
- **Realistic Simulation**: Advanced AI for player decision-making
- **Position Awareness**: Players make decisions based on field position
- **Duels System**: Physical confrontations between nearby players
- **Event Generation**: Goals, shots, fouls, injuries, cards, substitutions, VAR
- **Rating System**: Individual player performance ratings (1-100)
- **Match Statistics**: Possession, shots on target, fouls, corner kicks

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

