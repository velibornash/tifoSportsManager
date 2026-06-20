# TIFO Football Manager - Manual Testing Tickets
## Status: Manual Testing Guidelines
**Generated**: March 24, 2026

---

## TABLE OF CONTENTS
1. [Authentication & User Management](#authentication--user-management)
2. [Dashboard & Navigation](#dashboard--navigation)
3. [Club Management](#club-management)
4. [Match System](#match-system)
5. [Training System](#training-system)
6. [League & Competition](#league--competition)
7. [Analytics & Statistics](#analytics--statistics)
8. [Community & Social](#community--social)
9. [UI/UX & Responsive Design](#uiux--responsive-design)

---

## AUTHENTICATION & USER MANAGEMENT

### TEST-001: User Login Flow
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Test account credentials: `test@primer.rs` / `A12345!`
- Browser with localStorage enabled
- Network connectivity

**Test Steps**:
1. Navigate to login page
2. Enter valid email: `test@primer.rs`
3. Enter valid password: `A12345!`
4. Click login button
5. Verify token stored in localStorage (browser DevTools > Application > LocalStorage)
6. Verify redirect to dashboard
7. Check that Authorization header includes `Bearer {token}` in subsequent requests
8. Close browser and reopen
9. Verify user still logged in (token persists)

**Expected Results**:
- ✅ Login form submits successfully
- ✅ No error messages displayed
- ✅ JWT token stored in localStorage with key `token`
- ✅ Redirect to dashboard occurs
- ✅ User information displays in dashboard
- ✅ Token persists after browser close/reopen
- ✅ All API requests include Authorization header

**Failure Criteria**:
- ❌ Login fails or shows error
- ❌ Token not stored or malformed
- ❌ Redirect doesn't occur
- ❌ Token lost on browser refresh

---

### TEST-002: Invalid Login Attempt
**Priority**: HIGH  
**Test Type**: Negative Testing  
**Estimated Duration**: 10 minutes  

**Test Steps**:
1. Navigate to login page
2. Enter invalid email: `invalid@example.com`
3. Enter password: `wrongpassword`
4. Click login button
5. Verify error message displays
6. Try with valid email but wrong password
7. Verify appropriate error message
8. Try with empty email field
9. Try with empty password field
10. Verify form validation messages

**Expected Results**:
- ✅ Clear error message on invalid credentials
- ✅ User remains on login page (no redirect)
- ✅ localStorage has no token
- ✅ Form validation errors for empty fields
- ✅ Appropriate error handling messages

**Failure Criteria**:
- ❌ No error message on invalid login
- ❌ User logged in despite invalid credentials
- ❌ Confusing error messages

---

### TEST-003: User Registration
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Test Steps**:
1. Navigate to registration page
2. Enter new email address (e.g., `testuser123@test.com`)
3. Enter password meeting requirements (uppercase, lowercase, number, special char, 8+ chars)
4. Confirm password (matching)
5. Click register button
6. Verify success message appears
7. Verify user redirected to login page
8. Attempt to login with new credentials
9. Try registering with same email again
10. Verify duplicate email prevention message

**Expected Results**:
- ✅ Registration form accepts valid inputs
- ✅ Success message displayed after registration
- ✅ Redirect to login page after registration
- ✅ New user can login with registered credentials
- ✅ Duplicate email rejection message appears
- ✅ Password requirements clearly stated

**Failure Criteria**:
- ❌ Registration fails with valid inputs
- ❌ Weak password accepted
- ❌ Duplicate email allowed
- ❌ No success feedback

---

### TEST-004: Session Timeout & Re-authentication
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Ability to wait for token expiration or simulate it

**Test Steps**:
1. Login to application
2. Make note of token expiry time (check in DevTools)
3. Wait for token to expire OR manually remove token from localStorage
4. Attempt to access protected page (e.g., dashboard)
5. Verify automatic redirect to login page
6. Verify session timeout message displays
7. Verify no console errors for 401/403 responses
8. Login again to restore session

**Expected Results**:
- ✅ User redirected to login on token expiration
- ✅ Clear message explaining session expired
- ✅ No broken page displays
- ✅ Can successfully re-login after timeout
- ✅ New token generated on re-login

**Failure Criteria**:
- ❌ User remains on protected page after token expires
- ❌ Cryptic error messages displayed
- ❌ Infinite redirect loop
- ❌ Can't access dashboard after re-login

---

### TEST-005: User Profile Information Display
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 10 minutes  

**Prerequisites**:
- User logged in
- User has assigned CTeam and role

**Test Steps**:
1. Login as test user
2. Navigate to dashboard
3. Verify user name displays in header
4. Verify user's CTeam name displays
5. Check user profile menu (if available)
6. Verify user role displayed (Coach, Admin, Viewer)
7. Verify user's CTeam information accessible
8. Check that user-specific data filters by CTeam

**Expected Results**:
- ✅ User name displays correctly
- ✅ Team name/logo visible
- ✅ User role shown appropriately
- ✅ Data scoped to user's CTeam
- ✅ Profile information accurate

**Failure Criteria**:
- ❌ User info shows as "null" or undefined
- ❌ Wrong CTeam data displayed
- ❌ Role not reflected correctly

---

## DASHBOARD & NAVIGATION

### TEST-006: Dashboard Layout - Desktop
**Priority**: CRITICAL  
**Test Type**: UI/UX  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Desktop browser (1024px+ width)
- User logged in
- Standard resolution (1920x1080 or larger)

**Test Steps**:
1. Login and navigate to dashboard
2. Verify top navigation menu visible with buttons:
   - Club, Training, League, Country, Community
3. Verify left sidebar visible with menu sections:
   - Club (expandable), Training (expandable), League, Country, Community
4. Verify main content area displays in center
5. Verify logo in header/sidebar
6. Verify clock display in top-right (showing current date/time)
7. Verify responsive background and theme colors
8. Check that all navigation elements are clickable
9. Verify no horizontal scroll needed at standard resolution
10. Verify proper spacing and alignment

**Expected Results**:
- ✅ All navigation elements visible and properly positioned
- ✅ Sidebar occupies ~200px width, main content ~80%
- ✅ Top menu bar spans full width
- ✅ Clock displays in correct format (HH:MM DD.MM.YYYY)
- ✅ Dark theme with green accents visible
- ✅ No overlapping elements
- ✅ Professional appearance

**Failure Criteria**:
- ❌ Navigation elements cut off
- ❌ Layout distorted or misaligned
- ❌ Horizontal scroll required
- ❌ Clock displays incorrectly or absent

---

### TEST-007: Dashboard Layout - Mobile
**Priority**: CRITICAL  
**Test Type**: UI/UX  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Mobile device or mobile emulation (<768px width)
- User logged in
- iPhone/Android device or DevTools mobile view

**Test Steps**:
1. Login on mobile device or emulate mobile (DevTools)
2. Verify hamburger menu (☰) visible in top-left
3. Verify logo/title in header
4. Verify top menu buttons NOT visible (hidden)
5. Verify sidebar NOT visible (collapsed)
6. Verify main content spans full width
7. Click hamburger menu to open sidebar
8. Verify sidebar slides in from left with overlay
9. Verify accordion menu items (Club, Training, League, etc.)
10. Click overlay to close sidebar
11. Verify smooth close animation
12. Verify clock displays in mobile-appropriate location
13. Test on different mobile sizes (320px, 480px, 768px breakpoints)

**Expected Results**:
- ✅ Hamburger menu visible and functional
- ✅ Sidebar slides in smoothly
- ✅ Overlay allows click-to-close
- ✅ Content is readable and touch-friendly
- ✅ All elements properly sized for mobile
- ✅ No horizontal scroll
- ✅ Layout consistent across mobile sizes

**Failure Criteria**:
- ❌ Hamburger menu missing or non-functional
- ❌ Sidebar doesn't slide or stuck
- ❌ Horizontal scroll present
- ❌ Text too small to read

---

### TEST-008: Sidebar Navigation - Desktop Accordion
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Desktop view (1024px+)
- User logged in

**Test Steps**:
1. View sidebar navigation menu
2. Verify sections with arrows (▼/▶): Club, Training
3. Click "Club" section header
4. Verify submenu expands with items:
   - First Team, Schedule, Juniors, Tactics, Staff, Finances, Transfers, Medical Center, Profile
5. Click "Club" again to collapse
6. Verify submenu collapses smoothly
7. Click "Training" section to expand
8. Verify training submenu shows: Training Setup, Training Reports
9. Verify clicking menu items navigates to correct pages
10. Verify current page highlighted
11. Test expanding/collapsing multiple times (stability)

**Expected Results**:
- ✅ Accordion items expand/collapse smoothly
- ✅ Current page highlighted in menu
- ✅ Navigation works correctly
- ✅ No performance issues (smooth animations)
- ✅ All submenu items present and functional

**Failure Criteria**:
- ❌ Accordion doesn't expand/collapse
- ❌ Navigation doesn't work
- ❌ Animation stutters or lags
- ❌ Submenu items missing

---

### TEST-009: Sidebar Navigation - Mobile Accordion
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Mobile view (<768px)
- User logged in

**Test Steps**:
1. Open hamburger menu on mobile
2. Verify sidebar menu displays
3. Verify menu items are large enough to tap
4. Click "Club" item
5. Verify submenu expands
6. Verify menu items are clearly visible and readable
7. Click menu item (e.g., "First Team")
8. Verify navigation occurs and sidebar closes
9. Reopen sidebar and verify accordion state reset
10. Test expanding multiple accordion items
11. Verify overlay/background is tappable to close

**Expected Results**:
- ✅ Sidebar opens and closes smoothly
- ✅ Touch targets at least 44px tall (WCAG standard)
- ✅ Menu items clearly labeled
- ✅ Navigation works correctly
- ✅ Sidebar auto-closes on navigation
- ✅ Overlay dismissal works

**Failure Criteria**:
- ❌ Touch targets too small
- ❌ Sidebar doesn't open/close
- ❌ Navigation doesn't work
- ❌ Sidebar sticks open

---

### TEST-010: Live Clock Display
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 10 minutes  

**Prerequisites**:
- User logged in
- Network connectivity

**Test Steps**:
1. Login and view dashboard
2. Locate clock display (desktop: top-right, mobile: header)
3. Verify clock shows current time (HH:MM format)
4. Verify clock shows current date (DD.MM.YYYY format)
5. Wait 30 seconds and verify time updates
6. Verify Serbian locale formatting
7. Check formatting: Should be "14:35 24.03.2026" format
8. Verify no errors in browser console
9. Check clock persists across page navigation
10. Test on mobile and desktop

**Expected Results**:
- ✅ Clock displays correctly formatted time
- ✅ Clock displays correctly formatted date
- ✅ Time updates in real-time
- ✅ Uses Serbian locale (sr-RS)
- ✅ Consistent across all pages
- ✅ No console errors

**Failure Criteria**:
- ❌ Clock doesn't display
- ❌ Time doesn't update
- ❌ Incorrect format
- ❌ Shows wrong timezone

---

### TEST-011: Page Navigation - All Sections
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Desktop view preferred (but test mobile too)

**Test Steps**:
1. From dashboard, click "Club" in top menu (or sidebar)
2. Verify page loads and displays club information
3. Click "Training" in navigation
4. Verify training page loads
5. Click "League" in navigation
6. Verify league page loads
7. Click "Country" in navigation
8. Verify country page loads
9. Click "Community" in navigation
10. Verify community page loads
11. Test using sidebar submenu items for detailed navigation
12. Test back button functionality
13. Test breadcrumb navigation (if available)
14. Verify page transitions are smooth

**Expected Results**:
- ✅ All navigation links work
- ✅ Pages load correctly
- ✅ Correct content displays
- ✅ No broken links or 404 errors
- ✅ Page transitions smooth
- ✅ Back button returns to previous page
- ✅ Current page highlighted in menu

**Failure Criteria**:
- ❌ Navigation links broken
- ❌ 404 or error pages displayed
- ❌ Content doesn't load
- ❌ Page navigation laggy or broken

---

## CLUB MANAGEMENT

### TEST-012: First Team Squad List Display
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Team has squad data
- Navigate to Club > First Team

**Test Steps**:
1. Navigate to First Team section
2. Verify squad table/list displays all CPlayers
3. Verify each CPlayer shows:
   - Name, Position (GK/DEF/MID/FWD)
   - Shirt Number, Age, Rating (★★★★☆ format)
   - Condition percentage, Salary
   - Injury status (if applicable)
4. Click column headers to sort (if available):
   - Sort by Name (A-Z)
   - Sort by Rating (high to low)
   - Sort by Position
   - Sort by Age
5. Verify sorting works correctly
6. Test filter by CSPosition (dropdown or buttons)
7. Filter to show only forwards, verify results
8. Click on CPlayer name to view profile
9. Verify CPlayer detail page loads
10. Return to squad list and verify state maintained

**Expected Results**:
- ✅ All squad members display with correct data
- ✅ Sorting functions correctly for each column
- ✅ Filtering by CSPosition works
- ✅ Player links navigate to profile page
- ✅ Ratings display as stars (1-5 scale)
- ✅ Condition shows as percentage
- ✅ Injury indicators visible
- ✅ Table is responsive on mobile

**Failure Criteria**:
- ❌ Players missing from list
- ❌ Incorrect CPlayer data
- ❌ Sorting doesn't work
- ❌ Filtering broken
- ❌ Links don't navigate

---

### TEST-013: Player Profile Details
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to First Team
- Click on a CPlayer name

**Test Steps**:
1. Click on any CPlayer from squad list
2. Verify CPlayer detail page loads
3. Verify personal information displays:
   - Name, Age, Nationality, Position
   - Height, Weight, Preferred Foot
   - Shirt Number
4. Verify career statistics section:
   - Matches Played, Goals, Assists
   - Appearances, Average Rating
   - Minutes Played
5. Verify contract information:
   - Contract Expiry Date, Salary
   - Agent information (if available)
6. Verify skills breakdown:
   - Pace, Passing, Shooting, Dribbling
   - Defense, Physical, Mental (1-20 scale)
7. Verify physical/injury status
8. Verify recent match statistics (last 5 matches)
9. Test clicking back button to return to squad list
10. Verify page displays correctly on mobile

**Expected Results**:
- ✅ All CPlayer information displays accurately
- ✅ Career statistics are correct and complete
- ✅ Skills display in readable format (bars or numbers)
- ✅ Contract details accurate
- ✅ Injury status clearly indicated
- ✅ Recent form visible
- ✅ No missing data fields

**Failure Criteria**:
- ❌ Missing CPlayer information
- ❌ Incorrect statistics
- ❌ Empty data fields shown as blank/null
- ❌ Back navigation doesn't work

---

### TEST-014: Medical Center - Injury Tracking
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Navigate to Club > Medical Center
- Team should have injured/suspended CPlayers for testing

**Test Steps**:
1. Navigate to Medical Center
2. Verify page displays list of unavailable CPlayers
3. For injured CPlayers, verify display:
   - Player name, Position, Type of injury
   - Injury Date, Expected Return Date
   - Recovery percentage (0-100%)
   - Severity indicator (Minor/Moderate/Severe)
4. For suspended CPlayers, verify display:
   - Player name, Yellow/Red card status
   - Suspension duration (matches remaining)
   - Next availability date
5. Verify CPlayers unavailable in squad selector
6. Verify injured CPlayers excluded from lineup selection
7. Check if recovery percentage increases over time
8. Verify clear visual indicators for injury severity
9. Test on mobile view

**Expected Results**:
- ✅ All injuries and suspensions display
- ✅ Return dates accurately calculated
- ✅ Recovery progress visible
- ✅ Severity clearly indicated with colors/icons
- ✅ Injured CPlayers can't be selected for matches
- ✅ Suspension duration accurate
- ✅ Medical alerts appear on relevant pages

**Failure Criteria**:
- ❌ Injuries don't display
- ❌ Players still selectable when injured
- ❌ Return dates missing or incorrect
- ❌ No severity indication

---

### TEST-015: Transfers Management - Transfer List
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Navigate to Club > Transfers
- Sufficient budget for transfers
- Available transfer market CPlayers

**Test Steps**:
1. Navigate to Transfers section
2. Verify available CPlayers for transfer display
3. For each CPlayer, verify:
   - Name, Position, Age, Current Club, Rating
   - Asking Price/Value
   - Contract Length Remaining
4. Search/filter available CPlayers:
   - Filter by CSPosition (dropdown)
   - Search by name (if available)
   - Sort by price/rating
5. Click on CPlayer to view details
6. Verify CPlayer detail modal/popup shows:
   - Full CPlayer stats
   - Transfer history
   - Negotiation status
7. Try to place bid on CPlayer:
   - Enter bid amount (less than asking price)
   - Verify offer is submitted
8. Verify confirmation message displays
9. Check that budget is deducted (if offer accepted)
10. Verify transfer list updates with new status

**Expected Results**:
- ✅ Available CPlayers list displays
- ✅ Player information accurate and complete
- ✅ Filtering/sorting works
- ✅ Bid submission successful
- ✅ Confirmation message displays
- ✅ Budget updated appropriately
- ✅ Transfer status shows in CPlayer list

**Failure Criteria**:
- ❌ Transfer list empty or incorrect
- ❌ Bid submission fails
- ❌ No confirmation feedback
- ❌ Budget not updated
- ❌ Transfer status doesn't update

---

### TEST-016: Finances Overview
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to Club > Finances

**Test Steps**:
1. Navigate to Finances section
2. Verify total budget displays
3. Verify available budget shows (after expenses)
4. Check budget breakdown:
   - Player salaries (% of budget)
   - Staff salaries
   - Operating expenses
   - Transfer activity budget
5. Verify income sources display:
   - Match day revenue
   - Sponsorship income
   - Prize money
   - Player sales
6. Verify expense categories display correctly
7. Check financial balance/net CSPosition
8. Verify financial warnings (if budget low)
9. Verify budget history/trends (if available)
10. Check that financial data updates after transfers

**Expected Results**:
- ✅ All financial figures display accurately
- ✅ Budget categories correctly categorized
- ✅ Income/expenses properly calculated
- ✅ Percentages add up correctly
- ✅ Financial warnings appear when appropriate
- ✅ Numbers formatted clearly (currency)
- ✅ Data reflects recent transactions

**Failure Criteria**:
- ❌ Financial data missing or incorrect
- ❌ Budget calculations wrong
- ❌ Formatting unclear
- ❌ Data not updated after transactions

---

## MATCH SYSTEM

### TEST-017: Match Engine - Playing a Match
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 30 minutes  

**Prerequisites**:
- User logged in
- Navigate to next scheduled match
- Set lineup for match (if required)

**Test Steps**:
1. Navigate to next scheduled fixture
2. Verify opponent, date, and venue display
3. Select "Play Your Match" or similar action
4. Verify match simulation starts
5. During match, verify:
   - Live scoreboard updates (goals counted)
   - Events display in real-time (goals, cards, substitutions)
   - Time counter advances (0-90 minutes)
   - Teams and formation visible
6. Wait for match to complete (or watch for 5+ minutes)
7. Verify final score displays
8. Verify match result stored (win/draw/loss)
9. Verify event log shows all major events
10. Check that match result updates CTeam statistics

**Expected Results**:
- ✅ Match simulation runs without errors
- ✅ Events display in real-time
- ✅ Score updates correctly
- ✅ Match time progresses 0-90 minutes
- ✅ Final result accurate and realistic
- ✅ Statistics captured and stored
- ✅ No missing events in log
- ✅ Performance is smooth (no freezes/stutters)

**Failure Criteria**:
- ❌ Match doesn't start or crashes
- ❌ Events don't display
- ❌ Score doesn't update
- ❌ Unrealistic results
- ❌ Match time stuck or goes beyond 90 minutes
- ❌ Performance issues (lag/freeze)

---

### TEST-018: Match Events - Goal Scoring
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Play a match (from TEST-017)
- Wait for goal to be scored

**Test Steps**:
1. During match simulation, wait for a goal event
2. Verify goal event displays with:
   - Goal scorer name
   - Assist provider (if applicable)
   - Time in match (minute)
   - Updated score
3. Verify goal animation or highlight (if available)
4. Verify scoreboard updates immediately
5. Verify event log shows goal event with details
6. Play another match and verify multiple goals are tracked
7. Verify own goals display differently (if applicable)
8. Verify penalty goals show penalty indicator
9. Check goal statistics update for CPlayer and CTeam

**Expected Results**:
- ✅ Goals display with correct details
- ✅ Assist credit given correctly
- ✅ Score updates immediately
- ✅ Event log captures all goals
- ✅ Player statistics updated
- ✅ Goal time accurate
- ✅ Multiple goals tracked correctly

**Failure Criteria**:
- ❌ Goals don't display
- ❌ Score doesn't update
- ❌ Assist not credited
- ❌ Event log missing goals
- ❌ Statistics not updated

---

### TEST-019: Match Events - Cards and Discipline
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Play a match (from TEST-017)
- Wait for card events

**Test Steps**:
1. During match, wait for yellow card event
2. Verify yellow card displays with:
   - Player name receiving card
   - Reason (if shown: foul, dissent, etc.)
   - Time in match
3. Verify yellow card count displayed for CPlayer
4. Verify accumulation over season:
   - Track if CPlayer reaches suspension threshold (e.g., 5 yellow = 1 match ban)
5. Wait for red card event (or complete multiple matches)
6. Verify red card displays:
   - Player name
   - Time
   - Automatic suspension
7. Verify suspended CPlayers can't play next match
8. Check that cards clear periodically (if applicable)
9. Verify discipline history available in CPlayer profile

**Expected Results**:
- ✅ Cards display correctly with details
- ✅ Card colors accurate (yellow/red)
- ✅ Card count accumulates properly
- ✅ Suspension triggered on threshold
- ✅ Suspended CPlayers excluded from lineup
- ✅ Event log captures all cards
- ✅ History available for review

**Failure Criteria**:
- ❌ Cards don't display
- ❌ Card count incorrect
- ❌ Suspension not enforced
- ❌ Suspended CPlayers still selectable
- ❌ Card history lost

---

### TEST-020: Match Lineup Selection
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Navigate to next scheduled match
- Select "Set Lineup" or similar option

**Test Steps**:
1. Open lineup editor for next match
2. Verify formation selector displays (e.g., 4-3-3, 4-2-3-1, etc.)
3. Select a formation
4. Verify CPlayer positions match formation:
   - Exactly 1 goalkeeper
   - 4-5 defenders (depending on formation)
   - 3-4 midfielders
   - 1-2 forwards
5. Attempt to drag-and-drop CPlayers (desktop) or select from dropdown (mobile)
6. Verify injured/suspended CPlayers show as unavailable
7. Verify save lineup button works
8. Select different formation and verify CPlayers are reorganized
9. Add substitute CPlayers (typically 5-7 additional CPlayers)
10. Verify validation prevents invalid lineups (wrong CPlayer count)
11. Confirm lineup is saved before match

**Expected Results**:
- ✅ Lineup editor loads and displays
- ✅ Formations displayed and selectable
- ✅ Drag-and-drop or dropdown selection works
- ✅ Injured CPlayers excluded from selection
- ✅ Player count validation enforced
- ✅ Lineup saves successfully
- ✅ Lineup persists until match plays
- ✅ Mobile interface functional

**Failure Criteria**:
- ❌ Lineup editor doesn't load
- ❌ Formation selection doesn't work
- ❌ Injured CPlayers selectable
- ❌ Validation doesn't work
- ❌ Lineup not saved
- ❌ Incorrect CPlayers in match

---

### TEST-021: Match Statistics - Team Stats Display
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Completed match (from TEST-017)
- View match details/report

**Test Steps**:
1. Navigate to completed match details
2. Verify match statistics display:
   - Possession percentage
   - Shots (total, on target, off target)
   - Pass attempts and accuracy
   - Fouls committed
   - Yellow/red cards
   - Corner kicks
   - Tackles, interceptions, clearances
3. Verify comparison between both teams
4. Check statistics are realistic and proportional to match events
5. Verify CTeam statistics updated in club profile
6. Check season statistics accumulation
7. Verify statistics available in CTeam profile/analytics

**Expected Results**:
- ✅ All statistics display with correct values
- ✅ Statistics realistic and match events
- ✅ Team comparison clear
- ✅ Season accumulation works
- ✅ Statistics accessible from multiple pages
- ✅ Values properly formatted and readable

**Failure Criteria**:
- ❌ Statistics missing
- ❌ Incorrect values
- ❌ Unrealistic statistics
- ❌ Don't match match events
- ❌ Not accessible elsewhere

---

### TEST-022: Match Visualization - TIFO Viewer
**Priority**: HIGH  
**Test Type**: UI/UX  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Access to match details/TIFO viewer
- Completed or live match with events

**Test Steps**:
1. Open TIFO match viewer
2. Verify page displays match information:
   - Score, CTeam names, match time
   - Formation display
   - Player names and positions
3. Verify event feed displays on side:
   - Goals with time and scorer
   - Cards with time and CPlayer
   - Substitutions
   - Other key events
4. Click on event in feed to highlight CPlayer involved
5. Verify match statistics tab displays (possession, shots, etc.)
6. Verify lineups tab shows starting XI and substitutes
7. Verify match timeline (if available)
8. Check that interface is responsive on mobile
9. Test navigation between tabs
10. Verify no broken links or missing data

**Expected Results**:
- ✅ TIFO viewer displays match information clearly
- ✅ Event feed shows all major events
- ✅ Statistics tab displays correctly
- ✅ Lineups tab accurate
- ✅ All interactive elements work
- ✅ Mobile responsive and readable
- ✅ No data missing or broken

**Failure Criteria**:
- ❌ Viewer doesn't load
- ❌ Events not displayed
- ❌ Data missing
- ❌ Tabs don't work
- ❌ Layout broken on mobile

---

## TRAINING SYSTEM

### TEST-023: Training Setup - Player Pool Assignment
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 25 minutes  

**Prerequisites**:
- User logged in
- Navigate to Training > Training Setup
- Team squad available

**Test Steps**:
1. Navigate to Training Setup page
2. Verify training pools displayed:
   - General Training (all squad)
   - Positional Training (by CSPosition)
   - Advanced Training (elite CPlayers)
   - Specialized Training (technical, tactical, physical)
3. Verify CPlayer list displays all squad members
4. Assign CPlayers to training pools:
   - Drag-and-drop on desktop (test functionality)
   - Select from dropdown on mobile
5. Verify injured CPlayers are excluded from selection
6. Assign 5-10 CPlayers to different pools
7. Verify CPlayer can only be in one pool (no duplicates)
8. Verify "clear" or "remove" button works
9. Test quick templates (if available):
   - "All forwards to advanced training" template
10. Verify training configuration saves
11. Verify configuration persists on page reload

**Expected Results**:
- ✅ All training pools display
- ✅ Player assignment works (drag-drop or dropdown)
- ✅ No duplicate assignments
- ✅ Injured CPlayers excluded
- ✅ Training templates work
- ✅ Configuration saves successfully
- ✅ Data persists after page reload
- ✅ Mobile interface functional

**Failure Criteria**:
- ❌ Training pools don't display
- ❌ Player assignment doesn't work
- ❌ Duplicate assignments possible
- ❌ Configuration doesn't save
- ❌ Injured CPlayers selectable

---

### TEST-024: Training Reports - Skill Progression Display
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Navigate to Training > Training Reports
- Multiple weeks of training data (may need to simulate)

**Test Steps**:
1. Navigate to Training Reports
2. Verify weekly report displays:
   - Week number and date range
   - Training configuration used
   - Player list with progression data
3. For each CPlayer, verify:
   - Assigned training pool
   - Skill changes (7 core skills: Pace, Passing, Shooting, Dribbling, Defense, Physical, Mental)
   - Change indicators (↑, ↓, or →)
   - Overall rating change
   - Condition percentage
   - Fatigue level
4. Verify CTeam summary statistics:
   - Average skill improvement
   - Training compliance
   - Injury count/risk
5. Check if previous week reports accessible (dropdown or tabs)
6. Verify graphs/charts display progression over time:
   - Line chart showing skill development
   - Condition trend
7. Verify recommendations display (if available)
8. Check data accuracy (should reflect training setup)

**Expected Results**:
- ✅ Training reports display with all required data
- ✅ Skill progression visible for all CPlayers
- ✅ Change indicators accurate
- ✅ Team summary accurate
- ✅ Historical comparison available
- ✅ Graphs display correctly
- ✅ Recommendations logical and helpful

**Failure Criteria**:
- ❌ Reports don't load or display
- ❌ Skill progression missing
- ❌ Incorrect change calculations
- ❌ Graphs broken or empty
- ❌ No historical data available

---

### TEST-025: Training Impact - Skill Development Over Time
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 30+ minutes (time-dependent)  

**Prerequisites**:
- User logged in
- Can access training reports
- Ability to simulate multiple weeks (or wait)

**Test Steps**:
1. Note current skill levels for 2-3 CPlayers
2. Set training configuration (e.g., advanced training for strikers)
3. Wait 1 week (or simulate if possible)
4. Check training reports
5. Verify striker skills improved:
   - Shooting, Dribbling, Physical should increase
   - Pace might increase slightly
6. Note specific skill improvements match training focus
7. Repeat with different training configuration
8. Verify goalkeepers don't improve shooting skills
9. Verify young CPlayers improve faster
10. Check that overtraining increases injury risk
11. Verify fatigue accumulates from high-intensity training
12. Check that rest reduces fatigue

**Expected Results**:
- ✅ Skill improvements visible after training
- ✅ Skill improvements match training focus
- ✅ Position-specific improvement (forwards shoot, defenders defend)
- ✅ Young CPlayers learn faster
- ✅ Fatigue accumulates from high-intensity
- ✅ Rest/recovery reduces fatigue
- ✅ Injury risk increases with overtraining
- ✅ Improvements consistent over multiple weeks

**Failure Criteria**:
- ❌ No skill improvement after training
- ❌ Improvements don't match training focus
- ❌ Unrealistic skill changes
- ❌ Fatigue doesn't accumulate
- ❌ No risk/benefit tradeoff

---

## LEAGUE & COMPETITION

### TEST-026: League Table & Standings
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Navigate to League > Standings or similar
- Active league with teams/matches

**Test Steps**:
1. Navigate to league standings table
2. Verify table displays all teams in league
3. For each CTeam, verify columns:
   - Rank (1st, 2nd, etc.)
   - Team name/logo
   - Matches Played (MP)
   - Wins, Draws, Losses (W-D-L)
   - Goals For (F), Goals Against (A)
   - Goal Difference (±)
   - Points (W=3, D=1, L=0)
4. Verify teams sorted by points (descending)
5. Verify tiebreaker rules applied (goal difference, head-to-head)
6. Check user's CTeam highlighted or easily identifiable
7. Verify promotion/relegation zones color-coded:
   - Green (promotion)
   - White (safe)
   - Red (relegation)
8. Verify table updates after match completion
9. Check form indicators (last 5 matches: W/D/L)
10. Test on mobile view

**Expected Results**:
- ✅ All teams display in correct ranking
- ✅ Statistics accurate and calculated correctly
- ✅ Sorting by points and tiebreaker rules correct
- ✅ Zones color-coded appropriately
- ✅ Updates after matches
- ✅ Form indicators visible
- ✅ Mobile responsive

**Failure Criteria**:
- ❌ Incorrect CTeam ranking
- ❌ Wrong point calculations
- ❌ Table doesn't update after matches
- ❌ Zones not color-coded
- ❌ Layout broken on mobile

---

### TEST-027: Match Fixtures & Schedule
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to fixtures list or schedule
- League with scheduled matches

**Test Steps**:
1. Navigate to fixtures/schedule page
2. Verify upcoming matches display with:
   - Opponent name/logo
   - Home/Away indicator
   - Match date and time
   - Stadium/venue
3. Verify matches sorted by date (earliest first)
4. Click on match to view details:
   - Pre-match statistics (if available)
   - Team form
   - Key CPlayer matchups
   - Injury/suspension information
5. Verify "Next Match" highlighted or featured
6. Check past results display (if in past)
7. Verify fixture difficulty rating (if shown)
8. Test filtering by round/week
9. Test pagination (if many matches)
10. Verify mobile view shows fixtures clearly

**Expected Results**:
- ✅ Fixtures display with complete information
- ✅ Correct sorting and organization
- ✅ Match details accessible
- ✅ Next match clearly identified
- ✅ Difficulty/form indicators helpful
- ✅ Mobile view functional
- ✅ No missing or broken data

**Failure Criteria**:
- ❌ Fixtures don't display
- ❌ Information incomplete
- ❌ Wrong dates or times
- ❌ Details not accessible
- ❌ Layout broken

---

### TEST-028: Competition Results History
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to results/history
- Completed matches in league

**Test Steps**:
1. Navigate to match results/history page
2. Verify completed matches display with:
   - Score (home CTeam score - away CTeam score)
   - Teams names/logos
   - Match date
   - Competition/round
   - Result status (W/D/L from user's perspective)
3. Verify sorting options:
   - By date (newest/oldest)
   - By opponent
   - By competition
4. Click on result to view match detail
5. Verify detailed match statistics available
6. Check if filters work:
   - Filter by competition (league, cup, etc.)
   - Filter by result (wins, draws, losses)
7. Verify pagination works (if many results)
8. Check head-to-head history (if available)
9. Test on mobile

**Expected Results**:
- ✅ All completed matches display
- ✅ Scores accurate
- ✅ Sorting/filtering works
- ✅ Match details accessible
- ✅ Data complete and correct
- ✅ Mobile view functional

**Failure Criteria**:
- ❌ Results don't display
- ❌ Scores incorrect
- ❌ Sorting/filtering broken
- ❌ Details not accessible
- ❌ Missing information

---

## ANALYTICS & STATISTICS

### TEST-029: Player Statistics - Career Stats
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to Analytics > Statistics or Player Profile
- Players with matches played

**Test Steps**:
1. Navigate to CPlayer statistics page or profile
2. For a CPlayer with matches played, verify stats:
   - Matches Played
   - Goals Scored
   - Assists
   - Minutes Played
   - Average Rating (1-10 scale)
   - Appearances (starts vs substitutes)
3. Verify stats are cumulative across all matches
4. Verify stats break down by competition (if available):
   - League stats
   - Cup stats
   - International stats (if applicable)
5. Click on CPlayer to view detailed stats
6. Verify percentages correct:
   - Goals per match ratio
   - Assists per match
   - Pass completion rate
7. Check season comparison (if multiple seasons)
8. Verify no negative numbers
9. Test on mobile

**Expected Results**:
- ✅ All statistics display and are accurate
- ✅ Breakdowns by competition correct
- ✅ Ratios and percentages accurate
- ✅ Cumulative totals match individual match data
- ✅ Comparison functionality works
- ✅ Mobile view readable

**Failure Criteria**:
- ❌ Statistics missing or incorrect
- ❌ Wrong calculations
- ❌ Negative values
- ❌ Data doesn't accumulate properly

---

### TEST-030: Team Statistics - Season Performance
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to Team Statistics or Analytics
- Team with completed matches

**Test Steps**:
1. Navigate to CTeam statistics page
2. Verify season statistics display:
   - Matches Played
   - Wins, Draws, Losses
   - Goals For, Goals Against
   - Goal Difference
   - Points
   - Win Percentage
   - Average Goals Per Match
   - Average Goals Against Per Match
3. Verify home vs away statistics (if available)
4. Check tactical statistics:
   - Average possession
   - Average shots per match
   - Average fouls per match
   - Average corners per match
5. Verify performance trends:
   - Form (last 5 matches)
   - Recent performance chart
6. Check league CSPosition and context
7. Verify data accurate compared to league table
8. Test on mobile

**Expected Results**:
- ✅ All statistics accurate and complete
- ✅ Calculations correct (e.g., win % = wins/matches)
- ✅ Home/away split accurate
- ✅ Trends visible and logical
- ✅ Data matches league standings
- ✅ Mobile view functional

**Failure Criteria**:
- ❌ Statistics missing or incorrect
- ❌ Wrong calculations
- ❌ Inconsistent with standings
- ❌ Trends not logical

---

### TEST-031: Top Scorers Leaderboard
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 10 minutes  

**Prerequisites**:
- User logged in
- Navigate to Leaderboards or Statistics
- League with matches played

**Test Steps**:
1. Navigate to top scorers leaderboard
2. Verify leaderboard displays:
   - Player rank (1st, 2nd, etc.)
   - Player name and CTeam
   - Goals scored
   - Matches played
   - Goals per match ratio
3. Verify ranking is correct (highest goals at top)
4. Verify top scorers are realistic
5. Click on CPlayer to view profile
6. Check if penalty goals broken out separately (if available)
7. Verify leaderboard updates after matches
8. Test filtering by CSPosition (if available)
9. Check top 10 accuracy

**Expected Results**:
- ✅ Leaderboard displays correctly
- ✅ Ranking accurate (highest first)
- ✅ Player information complete
- ✅ Links to profiles work
- ✅ Updates after matches
- ✅ Realistic goal tallies

**Failure Criteria**:
- ❌ Leaderboard doesn't display
- ❌ Incorrect ranking
- ❌ Missing CPlayer data
- ❌ Doesn't update

---

### TEST-032: Analytics Dashboard - Performance Trends
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Navigate to Analytics Dashboard
- Team with multiple matches (10+)

**Test Steps**:
1. Navigate to analytics dashboard
2. Verify dashboard displays KPIs (Key Performance Indicators):
   - Current league CSPosition
   - Win percentage
   - Average goals per match
   - Recent form (last 5 matches)
3. Check visualizations display:
   - Trend lines (wins/losses over time)
   - Performance charts
   - Possession averages
   - Attacking/defensive metrics
4. Verify CPlayer insights:
   - Best performing CPlayers
   - Underperforming CPlayers
   - In-form CPlayers (recent success)
5. Check tactical analysis:
   - Formation effectiveness
   - Tactical style performance
   - Home vs away results
6. Verify recommendations display:
   - Training focus suggestions
   - Transfer suggestions (if applicable)
   - Squad rotation recommendations
7. Test filters (if available)
8. Check data accuracy against raw statistics
9. Test on mobile (charts should be readable)

**Expected Results**:
- ✅ Dashboard loads and displays correctly
- ✅ KPIs accurate and meaningful
- ✅ Charts display clearly with accurate data
- ✅ Insights logical and helpful
- ✅ Recommendations reasonable
- ✅ Mobile view functional

**Failure Criteria**:
- ❌ Dashboard doesn't load
- ❌ Incorrect data in KPIs/charts
- ❌ Charts empty or broken
- ❌ Recommendations nonsensical
- ❌ Mobile view broken

---

## COMMUNITY & SOCIAL

### TEST-033: Community Chat - Basic Functionality
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to Community > Chat
- At least 2 test accounts available (if testing multi-user)

**Test Steps**:
1. Navigate to community chat
2. Verify chat interface loads
3. Verify message input field present
4. Type a test message and send
5. Verify message appears in chat (with timestamp)
6. Verify message shows sender name
7. Check if message persists (reload and verify still there)
8. Test mentioning other users (@username) if available
9. Test message formatting (bold, italic, if available)
10. Verify message timestamps in correct format
11. Check chat is responsive on mobile
12. Test on different rooms/channels (if multiple available)

**Expected Results**:
- ✅ Chat messages send and display successfully
- ✅ Messages show sender, content, and timestamp
- ✅ Messages persist
- ✅ Mention functionality works (if available)
- ✅ Chat responsive on mobile
- ✅ No errors or broken displays

**Failure Criteria**:
- ❌ Messages don't send or display
- ❌ Missing sender/timestamp information
- ❌ Messages don't persist
- ❌ Layout broken on mobile

---

### TEST-034: Match Simulation Controls - Play Your Match
**Priority**: CRITICAL  
**Test Type**: Functional  
**Estimated Duration**: 30 minutes  

**Prerequisites**:
- User logged in
- Navigate to next scheduled match
- Lineup set for match
- Access to match simulation action

**Test Steps**:
1. Navigate to next scheduled match
2. Verify "Play Your Match" action available
3. Click "Play Your Match"
4. Verify match simulation starts
5. Watch match progress:
   - Verify score updates in real-time
   - Verify events display (goals, cards, etc.)
   - Verify match time progresses 0-90 minutes
   - Verify CTeam formations visible (if shown)
6. Wait for match to complete
7. Verify final score displays
8. Verify match result saves
9. Verify result reflects in standings/results list
10. Verify CPlayer statistics updated
11. Verify match can be replayed/viewed in TIFO viewer

**Expected Results**:
- ✅ Match simulates successfully from start to finish
- ✅ Events display in real-time
- ✅ Score updates correctly
- ✅ Final result accurate and realistic
- ✅ Result saves to database
- ✅ Statistics updated
- ✅ Match viewable afterwards
- ✅ No crashes or errors

**Failure Criteria**:
- ❌ Match doesn't start or crashes
- ❌ Events missing or incorrect
- ❌ Result doesn't save
- ❌ Statistics not updated
- ❌ Unrealistic outcome

---

### TEST-035: Match Simulation Controls - Simulate Other Results
**Priority**: HIGH  
**Test Type**: Functional  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Access to "Simulate Other Results" or similar action
- Current round with multiple unplayed matches

**Test Steps**:
1. Navigate to league/competition
2. Verify "Simulate Other Results" or "Simulate Round" action available
3. Click action
4. Verify all unplayed matches in round simulated (background)
5. Verify results summary displays:
   - Match scores
   - Winners/losers
   - Goals scored
6. Verify standings update with new results
7. Check that CPlayer's CTeam match is NOT simulated (only others)
8. Verify all fixtures show completed status
9. Verify league table updated correctly
10. Check time: simulating should be instant (no waiting for match playback)

**Expected Results**:
- ✅ Other matches simulate successfully
- ✅ Results display in summary
- ✅ Standings update correctly
- ✅ User's match not automatically played
- ✅ Fast execution (instant completion)
- ✅ Results realistic

**Failure Criteria**:
- ❌ Simulation doesn't run
- ❌ Results incorrect
- ❌ User's match accidentally played
- ❌ Standings don't update
- ❌ Crashes or errors


---

## UI/UX & RESPONSIVE DESIGN

### TEST-036: Responsive Design - Desktop (1920px)
**Priority**: HIGH  
**Test Type**: UI/UX  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Large desktop monitor (1920px+) or emulated
- User logged in
- Navigate through multiple pages

**Test Steps**:
1. Open dashboard on desktop (1920px+ width)
2. Verify full sidebar visible on left
3. Verify top navigation menu spans full width
4. Verify main content area uses available space
5. Navigate to different pages (Club, Training, League, etc.)
6. Verify each page displays optimally at wide width
7. Check tables display all columns without horizontal scroll
8. Verify card layouts use multi-column grid
9. Check that content is not stretched or too spread out
10. Verify font sizes readable
11. Test that layout adapts gracefully (still readable at 1440px, 2560px)

**Expected Results**:
- ✅ Full layout visible without scroll
- ✅ Sidebar and content well-proportioned
- ✅ Tables and data readable without scroll
- ✅ Multi-column layouts utilized effectively
- ✅ Content centered and organized
- ✅ Professional appearance

**Failure Criteria**:
- ❌ Horizontal scroll required
- ❌ Content cramped or stretched
- ❌ Layout breaks at large sizes
- ❌ Content misaligned

---

### TEST-037: Responsive Design - Tablet (768px)
**Priority**: HIGH  
**Test Type**: UI/UX  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- Tablet size screen (750-1024px) or emulated
- User logged in
- DevTools tablet emulation

**Test Steps**:
1. Emulate tablet view (iPad size, 768px width)
2. Login and view dashboard
3. Verify sidebar is visible but potentially smaller
4. Verify navigation menu adapts
5. Navigate to various pages
6. Check tables have either:
   - Horizontal scroll with frozen first column, OR
   - Responsive card layout
7. Verify images scale appropriately
8. Check grid layouts (e.g., stat cards) display in 2-column or single-column
9. Verify buttons are touch-friendly (at least 44px tall)
10. Test on both portrait and landscape orientation
11. Verify no content is cut off

**Expected Results**:
- ✅ Layout adapts to tablet size
- ✅ Touch targets at least 44px tall
- ✅ No horizontal scroll (or minimal with frozen column)
- ✅ Content readable and accessible
- ✅ Works in both portrait and landscape
- ✅ Professional appearance

**Failure Criteria**:
- ❌ Horizontal scroll required
- ❌ Touch targets too small
- ❌ Content cut off or misaligned
- ❌ Layout breaks

---

### TEST-038: Responsive Design - Mobile (375px)
**Priority**: CRITICAL  
**Test Type**: UI/UX  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- Mobile size screen (<768px) or emulated
- User logged in
- DevTools mobile emulation (iPhone 6/7/8 or similar)

**Test Steps**:
1. Emulate mobile view (375px width - iPhone size)
2. Login and view dashboard
3. Verify hamburger menu visible and functional
4. Verify sidebar hidden (collapsed)
5. Verify main content spans full width
6. Verify no horizontal scroll required
7. Navigate to each page (Club, Training, League, etc.)
8. Verify each page is readable on mobile
9. Check data tables convert to card layout or scrollable
10. Verify buttons are touch-friendly (44px+ tall)
11. Test hamburger menu open/close
12. Test menu item selection and navigation
13. Verify overlay closes sidebar when tapped
14. Check font sizes readable (no less than 14px)
15. Verify images scale appropriately
16. Test orientation change (portrait <-> landscape)

**Expected Results**:
- ✅ Hamburger menu functional
- ✅ Sidebar collapses appropriately
- ✅ No horizontal scroll
- ✅ Touch targets 44px+ tall
- ✅ Readable font sizes
- ✅ Images scale properly
- ✅ All pages accessible and readable
- ✅ Works in portrait and landscape

**Failure Criteria**:
- ❌ Hamburger menu broken
- ❌ Horizontal scroll present
- ❌ Touch targets too small
- ❌ Text too small to read
- ❌ Layout breaks on orientation change
- ❌ Content inaccessible

---

### TEST-039: Dark Theme & Color Contrast
**Priority**: MEDIUM  
**Test Type**: UI/UX  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Access to multiple pages with various content types

**Test Steps**:
1. Browse dashboard and various pages
2. Verify dark background colors:
   - Primary background: dark blue/black (#0a0e18 approx)
   - Secondary background: darker tone
3. Verify text contrast:
   - Primary text (#eef5ff approx): white/off-white
   - Secondary text (#9aa7bc approx): muted blue
4. Verify accent colors used strategically:
   - Green (#4CAF50) for positive actions/highlights
   - Red for danger/errors
   - Orange for warnings
5. Check that color is not sole method of indicating status
   - Use icons/text in addition to color
6. Verify buttons have clear visual feedback:
   - Hover state (color shift, slight lift)
   - Active state (clearly different from inactive)
   - Disabled state (reduced opacity, no hover)
7. Test forms:
   - Normal input state
   - Focus state (color change)
   - Error state (red border + error text)
   - Disabled state
8. Verify modals and overlays:
   - Backdrop dimmed/blurred
   - Content stands out
   - Close button clearly visible
9. Check accessibility contrast:
   - Text should meet WCAG AA standard (4.5:1 ratio)
   - Use accessibility checker if available

**Expected Results**:
- ✅ Consistent dark theme throughout
- ✅ Text highly readable against background (high contrast)
- ✅ Accent colors used strategically
- ✅ Visual feedback clear (hover, active, disabled, focus)
- ✅ Meets WCAG AA contrast ratio (4.5:1)
- ✅ Professional appearance

**Failure Criteria**:
- ❌ Low contrast (hard to read)
- ❌ Inconsistent theming
- ❌ Insufficient visual feedback
- ❌ Fails accessibility contrast check

---

### TEST-040: Form Input & Validation
**Priority**: MEDIUM  
**Test Type**: Functional  
**Estimated Duration**: 20 minutes  

**Prerequisites**:
- User logged in
- Access to pages with forms (lineups, training setup, etc.)

**Test Steps**:
1. Navigate to a form page (e.g., Training Setup)
2. Verify form elements display properly:
   - Text inputs with placeholder text
   - Dropdowns with default selection
   - Checkboxes/radio buttons
   - Buttons
3. Verify labels associated with inputs (accessibility)
4. Test input validation:
   - Try submitting empty form
   - Verify required field errors appear
   - Clear errors and try again
5. Test dropdown selections
6. Test checkbox selections
7. Fill out form with valid data
8. Verify success message on submission
9. Test form on mobile:
   - Keyboard appears appropriately
   - Touch targets easy to tap
   - Mobile select dropdowns work
10. Verify error messages are clear and helpful
11. Check that form data persists on validation error (doesn't clear)

**Expected Results**:
- ✅ Form displays and functions correctly
- ✅ Input validation works as expected
- ✅ Clear error messages displayed
- ✅ Success feedback provided
- ✅ Mobile form inputs work smoothly
- ✅ Accessibility (labels, tab navigation)

**Failure Criteria**:
- ❌ Form doesn't submit
- ❌ Validation doesn't work
- ❌ Unclear error messages
- ❌ Data cleared unexpectedly
- ❌ Mobile inputs difficult to use

---

### TEST-041: Data Table Responsiveness
**Priority**: HIGH  
**Test Type**: UI/UX  
**Estimated Duration**: 15 minutes  

**Prerequisites**:
- User logged in
- Navigate to page with data table (Squad, Standings, Results, etc.)

**Test Steps**:
1. View data table on desktop (1024px+)
2. Verify all columns visible
3. Verify table has proper spacing and alignment
4. Verify sorting works (click column headers)
5. View same table on tablet (768px)
6. Verify table adapts:
   - Key columns remain visible
   - Less important columns may scroll
   - OR convert to card layout
7. View on mobile (375px)
8. Verify table converts to mobile-friendly format:
   - Card layout per row, OR
   - Key columns frozen, others scroll
9. Verify mobile table headers clear and readable
10. Test sorting/filtering on mobile (if available)
11. Verify pagination works on all sizes
12. Check that data is not cut off

**Expected Results**:
- ✅ Table displays properly at all breakpoints
- ✅ Data readable without excessive scrolling
- ✅ Mobile format intuitive and accessible
- ✅ Sorting/filtering functional on mobile
- ✅ No data truncation

**Failure Criteria**:
- ❌ Table unreadable on mobile
- ❌ Excessive horizontal scroll required
- ❌ Data cut off or missing
- ❌ Sorting broken on mobile

---

## CROSS-FUNCTIONAL TESTING

### TEST-042: Full User Journey - New Game
**Priority**: CRITICAL  
**Test Type**: Integration  
**Estimated Duration**: 60+ minutes  

**Prerequisites**:
- Fresh application (or test account)
- Ability to play through full game flow

**Test Steps**:
1. **Registration & Login**:
   - Register new account with valid credentials
   - Login with new account
   - Verify dashboard loads
   
2. **Team Inspection**:
   - Navigate to Club > First Team
   - Review squad composition
   - Check CPlayer ratings and skills
   
3. **Initial Setup**:
   - Navigate to Training > Training Setup
   - Configure initial training pools
   - Save configuration
   
4. **Match Preparation**:
   - Navigate to next fixture
   - Set lineup for first match
   - Review opponent
   
5. **Play Match**:
   - Play first match
   - Monitor events and score
   - Wait for completion
   
6. **Post-Match Analysis**:
   - View match result
   - Review statistics
   - Check CPlayer performances
   
7. **League Interaction**:
   - View league standings
   - Check CSPosition improvement/decline
   - Review other results
   
8. **Training Progression**:
   - Navigate to Training Reports
   - Review skill progression
   - Adjust training focus based on results
   
9. **Transfers (if applicable)**:
   - Navigate to Transfers
   - Review available CPlayers
   - Attempt a transfer
   
10. **Analytics Review**:
    - Check analytics dashboard
    - Review CTeam statistics
    - Verify recommendations

**Expected Results**:
- ✅ Complete user flow works without errors
- ✅ All major features accessible and functional
- ✅ Data consistency (match results appear in standings, stats, etc.)
- ✅ Navigation smooth and logical
- ✅ Performance acceptable throughout
- ✅ No missing features or broken functionality

**Failure Criteria**:
- ❌ Any critical feature broken
- ❌ Data inconsistency (match result not in standings, etc.)
- ❌ Crashes or major errors
- ❌ Logical flow broken
- ❌ Performance issues

---

### TEST-043: Cross-Browser Compatibility
**Priority**: HIGH  
**Test Type**: Compatibility  
**Estimated Duration**: 30 minutes  

**Prerequisites**:
- User logged in
- Access to multiple browsers
- Test account available

**Test Steps**:
1. Test on Chrome (latest version):
   - Login and navigate through pages
   - Play a match
   - Verify functionality
   
2. Test on Firefox (latest):
   - Repeat login and navigation
   - Verify styles render correctly
   - Check form inputs work
   
3. Test on Safari (if on macOS):
   - Verify layout and styling
   - Check JavaScript functionality
   - Test mobile version
   
4. Test on Edge (if on Windows):
   - Verify compatibility
   - Check performance
   
5. For each browser, verify:
   - Login/logout works
   - Navigation functions
   - Forms submit correctly
   - Tables and data display properly
   - Styling consistent
   - No console errors
   - Performance acceptable

**Expected Results**:
- ✅ Application works identically on all major browsers
- ✅ Styling consistent across browsers
- ✅ No console errors (warnings acceptable)
- ✅ Form inputs work identically
- ✅ Performance acceptable on all browsers

**Failure Criteria**:
- ❌ Significant visual differences between browsers
- ❌ Features broken on any browser
- ❌ Critical console errors
- ❌ Form submission fails
- ❌ Major performance issues

---

## TEST SUMMARY

### Test Coverage by Category
- **Authentication**: 5 tests
- **Dashboard & Navigation**: 6 tests
- **Club Management**: 5 tests
- **Match System**: 6 tests
- **Training System**: 3 tests
- **League & Competition**: 3 tests
- **Analytics**: 4 tests
- **Community**: 2 tests
- **UI/UX & Responsive**: 6 tests
- **Cross-Functional**: 2 tests

**Total: 42 Manual Testing Tickets**

### Test Execution Recommendations
1. **Priority Order**: Execute CRITICAL tests first, then HIGH, then MEDIUM
2. **Environment**: Use dedicated test environment with test data
3. **Test Account**: Use `test@primer.rs` / `A12345!` or create fresh account
4. **Devices**: Test on desktop, tablet, and mobile devices
5. **Browsers**: Test on Chrome, Firefox, Safari, and Edge
6. **Reporting**: Document results with screenshots and notes
7. **Regression**: Re-run critical tests after bug fixes
8. **Frequency**: Run full suite before each release

### Known Testing Challenges
1. **Match Simulation Timing**: Some tests require waiting for match to complete (~30 min real-time)
2. **Season Progression**: Training tests benefit from multiple weeks of data
3. **Multi-User Testing**: Chat and social features benefit from multiple accounts
4. **Performance Testing**: Not covered in this suite (separate performance tests needed)
5. **Load Testing**: Not covered (separate load testing needed)

### Notes for Testers
- Pay attention to both functionality AND appearance (UI/UX)
- Document any inconsistencies between devices/browsers
- Test edge cases (empty lists, max values, special characters)
- Verify error messages are helpful and clear
- Check mobile experience thoroughly (critical for modern apps)
- Use accessibility checkers for color contrast and WCAG compliance
- Take screenshots of any failures for bug reports
- Note performance issues (lag, delays, freezes)

---

**Document Generated**: March 24, 2026  
**Application**: TIFO Football Manager  
**Version**: 1.0-SNAPSHOT  
**Total Manual Test Cases**: 42  
**Estimated Total Duration**: 400-500 minutes (6-8 hours)
