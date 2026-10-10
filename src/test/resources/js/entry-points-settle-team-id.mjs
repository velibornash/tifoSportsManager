// Every entry point into a view must settle the manager's club id first.
//
// A view asks `getTeamId()` and builds a URL or an ownership test out of it. `getTeamId()` returns a
// variable that is `null` until `/auth/me` answers. `loadPage` has always awaited that, which is why a
// normal click was always safe - and why the bug survived so long: the paths that bypassed `loadPage`
// were the ones nobody clicked from the nav.
//
// The three panels on the match view were the ones that showed it. `loadMatch` calls its view directly,
// `getTeamId()` was still null, the ownership guard returned silently, and the screen showed an empty
// host with no error and no warning. `loadPlayer`, `loadFixture`, `loadFormations` and the rest had the
// same shape and would have produced `/teams/null/players`.
//
// This is a source check rather than a render check on purpose: the defect is about WHICH functions
// settle and in what order, and the only thing that can see twenty entry points at once is the file.
import { readFileSync } from 'node:fs';

const pages = readFileSync('src/main/resources/static/js/pages.js', 'utf8');

const failures = [];
const check = (name, condition, detail) => {
    if (condition) console.log(`ok   ${name}`);
    else { console.log(`FAIL ${name} — ${detail}`); failures.push(name); }
};

// The helper has to exist and has to do the loading. Without the second half it would be a no-op that
// passes every other check on this file.
check('settleTeamId exists and actually loads',
    /async function settleTeamId\(\)\s*\{[^}]*if \(currentUserTeamId\) return currentUserTeamId;[^}]*await loadUserTeamId\(\)/s
        .test(pages),
    'settleTeamId either does not exist or returns the id without loading it when it is missing');

// Every `loadX` that hands straight to a view has bypassed loadPage, and therefore owes a settle.
// Delegation to `somethingView.loadX` is the marker; `return loadPage(...)` is not this shape and is
// already safe, because loadPage settles for itself.
const delegating = [];
const fnPattern = /async function (load[A-Za-z]*)\([^)]*\)\s*\{\n([\s\S]*?)\n    \}/g;
let match;
while ((match = fnPattern.exec(pages)) !== null) {
    const [, name, body] = match;
    // loadPage settles at its top and then dispatches to views from its switch, so it appears in this
    // shape without owing anything. It has its own check below.
    if (name !== 'loadPage' && /\b[a-zA-Z]+View\.load[A-Za-z]*\(/.test(body)) {
        delegating.push({ name, body });
    }
}

check('the sweep found the entry points it was supposed to find',
    delegating.length >= 19,
    `only ${delegating.length} view-delegating entry points were found; this file is meant to check all `
        + 'of them, so a number this low means the pattern no longer matches the source and this check '
        + 'is passing vacuously');

const unguarded = delegating.filter(d => !/await settleTeamId\(\)/.test(d.body));
check('every view-delegating entry point settles the club id first',
    unguarded.length === 0,
    `these reach a view without settling, and would read a null club id on a deep link: `
        + unguarded.map(d => d.name).join(', '));

// loadPage is the one function that settles for itself, and it must keep doing so or the other twenty
// guards are the only thing holding this up.
const loadPageBody = (pages.match(/async function loadPage\([^)]*\)\s*\{([\s\S]*?)\n    \}/) || [])[1] || '';
check('loadPage still settles for itself',
    /currentUserTeamId/.test(loadPageBody) && /loadUserTeamId\(\)|ensureUserTeamId\(\)/.test(loadPageBody),
    'loadPage is the normal-click path; if it stops settling, every page opened by clicking is cold');

// The match view's own three panels are guarded inside the view, not here, and are checked by
// route-to-substitution-plan.mjs. Asserted only so that the two files cannot disagree about it.
const matchView = readFileSync('src/main/resources/static/js/pages/views/match-view.js', 'utf8');
check('the match view resolves rather than reads, in all three places',
    (matchView.match(/await resolveTeamId\(\)/g) || []).length === 3,
    'the substitution, game-plan and lineup mounts share this guard; two out of three means the third '
        + 'still mounts nothing on a cold page');

// Added after making the same mistake twice in a row: inserting the settle *after* a `return` keyword
// leaves `return await settleTeamId();` followed by unreachable delegation. The view is never called,
// the page renders nothing, and every check above still passes because the settle IS there.
//
// `settleTeamId()` must therefore never be the returned expression. It is a wait, not a result.
check('settling is never returned, so the delegation after it is reachable',
    !/return\s+await settleTeamId\(\)/.test(pages),
    'a `return await settleTeamId();` makes the following `return xxxView.load...` unreachable — the '
        + 'view is never called and the page renders nothing, while every other check here passes');

console.log(failures.length ? `\n${failures.length} CHECK(S) FAILED` : '\nALL CHECKS PASSED');
process.exit(failures.length ? 1 : 0);