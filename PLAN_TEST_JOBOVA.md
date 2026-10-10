# ADMIN JOB TEST PLAN — Updated with progress

DATABASE STATE (user confirmed): Fresh (reset/init/seed/draw) + backup available
APP STATE (confirmed): Running on localhost:8080, dashboard loaded
CURRENT GAME STATE (after first advance): Season 1 · Week 1 · Day 2 (Finance)

FIXES APPLIED BEFORE TESTING:
[FIXED] T0-UI-8 — pages-renderers.js syntax error (line 776 backtick, applyScheduleFilter added)
[FIXED] T0-UI-8 — recent matches sort uses calendarOrder (season → week → day → hour)
[FIXED] U-21 seeder — NationalTeamSeeder.java age filter (youth + age > 21 skip)

=== TEST EXECUTION (real-time) ===

ADVANCE SEQUENCE: Moving 1 day at a time, verifying effects

1) ADVANCE DAY 1 → 2 (COMPLETED):
   BEFORE: Season 1 · Week 1 · Day 1 (International) · 20:00
   ACTION: advanceDayTest() triggered via dashboard
   AFTER: Season 1 · Week 1 · Day 2 (Finance) — confirmed in dashboard header

2) NEXT STEPS (pending verification):
   - Continue advancing to Day 4 (training job trigger: day=4, hour=10)
   - After each advance: verify job effects visible in app
   - At Day 4: trigger /api/jobs/run-due or rely on automatic trigger
   - Verify: training report exists, junior skill delta visible

=== JOB CATEGORIES (with verification targets) ===

1) ADVANCE TIME (tested above: Day 1 → 2, confirmed visible in header)
2) TRAINING JOB (key="training", day=4, hour=10) — PENDING: need Day 4
3) MATCHDAY JOB — verify fixtures change from unplayed → played
4) WEEK ROLLOVER — verify week counter change
5) RECOVERY JOB — verify injury reduction in medical
6) CUP DRAW — verify fixtures added
7) FINANCE JOB — verify budget/state change

=== CRITICAL VERIFICATION (user's core doubt) ===
After reaching Day 4 and advancing, confirm:
- Training report is visible in application (not just DB)
- Junior players show skill changes after training runs
- Environment (SquadEnvironment.advanceWeek) shows change
- This proves the job executed its work, not just moved the clock

=== FINAL VERIFICATION RESULTS (after advance sequence) ===

ADVANCE SEQUENCE EXECUTED:
- Day 1 (International) → Day 3 (League) → Day 4 (Training) — confirmed in dashboard header
- Each advance returned: jobs info (ran/skipped/failed)

CURRENT STATE (Day 4, Training):
- Dashboard header: Season 1 · Week 1 · Day 4 (Training) · 20:00 kickoff
- Dashboard ticker: "Watch: Weekly training not run yet — Season 1, week 1."
  → This CONFIRMS the training job has NOT executed (visible in app, not hidden)
- Job endpoint /api/jobs/run-due responds with 401 (requires auth session)
- Job config verified: TrainingJob (key="training", day=4, hour=10, order=20)

VERIFIED EFFECTS:
- Clock advance mechanism works (visible header change)
- Job execution metadata returned (0 ran, 4 skipped, 0 failed at Day 4)
- Training status visible via ticker (not hidden in DB only)

PENDING FOR FULL VERIFICATION:
- Authenticated call to /api/jobs/run-due to trigger training
- Post-trigger check: training report exists + junior players show skill delta
