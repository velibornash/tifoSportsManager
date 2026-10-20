// admin-view.js
//
// Admin control room. Reached from the ADMIN tab in the main menu, which is only
// rendered for ADMIN / OWNER / DEV users (see isAdminSession in auth.js).
//
// This is the new home of the DB Tools that used to live inside the Community page.
// The "Season flow controls" block that sat next to those tools was an exact duplicate
// of the dashboard's "Match Week Controls" (same three actions, same handlers) and has
// been removed - the dashboard remains the single entry point for those.
//
// The route is guarded as well as the menu, so typing loadPage('admin') in the console
// as a non-admin still gets a refusal rather than the tools.

import { authFetch, isAdminSession, getSessionRole } from '../../auth.js';
import { buildEmptyState } from './utils.js';
import { escapeHtml } from '../../ui/escape.js';
import { backButtonHtml } from '../../ui/components.js';

export function createAdminView({ getTeamId, getTeamName, getUsername }) {
    /**
     * The roles offered in the account list, in ascending order of privilege.
     *
     * <p>Written out rather than fetched, because it is the same list the server validates against and a
     * dropdown offering a value the backend refuses is a dropdown that lies. STAFF and PLUS are here
     * because they are real {@code UserRole} values; neither grants anything yet, which is a product
     * decision rather than an oversight in this list.
     */
    const ROLE_OPTIONS = ['REGULAR', 'PLUS', 'STAFF', 'MOD', 'ADMIN', 'DEV', 'OWNER'];

    /** Which admin tab is showing. 'tools' is the default because it is where the panel has always been. */
    let adminTab = 'tools';

    function activeAdminTab() {
        return adminTab;
    }

    /**
     * Shows one tab and hides the other.
     *
     * <p>`hidden` rather than a class, so a hidden panel is genuinely not rendered — a display:none
     * panel still fetches, and the Jobs tab would have been reading the server while invisible.
     */
    function showAdminTab(name) {
        adminTab = name;
        document.querySelectorAll('[data-admin-tab]').forEach(button => {
            const active = button.dataset.adminTab === name;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-selected', String(active));
        });
        document.querySelectorAll('[data-admin-panel]').forEach(panel => {
            panel.hidden = panel.dataset.adminPanel !== name;
        });
        if (name === 'jobs') {
            void showJobs();
        }
    }

    function wireAdminTabs() {
        document.querySelectorAll('[data-admin-tab]').forEach(button => {
            button.addEventListener('click', () => showAdminTab(button.dataset.adminTab));
        });
    }

    function guard() {
        if (isAdminSession()) return null;
        return buildEmptyState('Admins only - you do not have permission to view this page.');
    }

    /**
     * Designates THIS club's tactical-editor profile as the world's starting tactic.
     *
     * <p>This used to read the club's saved profile and PUT it straight back, field for field, and then
     * reported success. It was a no-op that stored nothing, behind a button labelled "Save Default
     * Tactics" — and there was nowhere to record the choice anyway, so "Give every club a default
     * tactic" picked a template with findFirst() and could hand out a different one each time.
     *
     * <p>Now it marks one profile, which the seed job reads. The profile's own rules were already being
     * written to var/tactics-editor-profiles.json and restored after a reset; this says which of those
     * profiles is THE one.
     */
    async function saveDefaultTactics(button) {
        const teamId = getTeamId?.();
        if (!teamId) {
            window.alert('No team assigned.');
            return;
        }
        const ok = window.confirm(
            'Use your club\'s current tactic as the default for EVERY club?\n\n'
            + '"Give every club a default tactic" will copy this one everywhere.\n\n'
            + 'Any club that already has its own tactics is left alone.');
        if (!ok) return;

        button.disabled = true;
        try {
            const res = await authFetch(`/admin/tactics/world-default?teamId=${encodeURIComponent(teamId)}`, {
                method: 'POST'
            });
            const saved = await res.json();
            await loadWorldDefaultTactic();
            window.alert(`Default tactics saved from ${saved.team || 'your club'} (${saved.formation}).\n\n`
                + 'They survive a DB reset and are used by "Give every club a default tactic".');
        } catch (err) {
            window.alert(`Could not save default tactics: ${err.message}`);
        } finally {
            button.disabled = false;
        }
    }

    /**
     * Shows which profile is the world default, and says so plainly when there is none.
     *
     * <p>"Give every club a default tactic" behaves differently depending on whether a default has been
     * chosen — a designated one, or the lowest-id fallback. A button whose effect depends on invisible
     * state has to say so on the page.
     */
    async function loadWorldDefaultTactic() {
        const host = document.getElementById('fm-world-default');
        if (!host) return;
        try {
            const res = await authFetch('/admin/tactics/world-default');
            const info = await res.json();
            if (info.designated) {
                host.innerHTML = `<p class="fm-subtle">World default: <strong>${escapeHtml(info.formation)}</strong>`
                    + `${info.style ? ' · ' + escapeHtml(info.style) : ''} · saved from `
                    + `${escapeHtml(info.team || 'a club')}.</p>`
                    + '<p class="fm-subtle">Saved rules live in <code>var/tactics-editor-profiles.json</code> '
                    + 'and are restored after a Reset DB.</p>';
            } else if (info.formation) {
                host.innerHTML = `<p class="fm-subtle">No world default chosen yet — <strong>${escapeHtml(info.formation)}</strong>`
                    + ' from the oldest profile would be used. Press <em>Save Default Tactics</em> to pick one '
                    + 'deliberately.</p>';
            } else {
                host.innerHTML = '<p class="fm-subtle">No tactical profile exists yet. Build one in the '
                    + 'Tactic Editor, save it, then press <em>Save Default Tactics</em>.</p>';
            }
        } catch (err) {
            host.innerHTML = `<p class="fm-subtle">Could not read the world default: ${escapeHtml(err.message)}</p>`;
        }
    }

    /**
     * Asks before doing anything destructive, and says what actually happened afterwards.
     *
     * <p>Every action here can rewrite part of the world, and a silent button that either works or
     * quietly does nothing is the worst of both. So each one confirms first, and then reports the
     * result the server sent - including "nothing to do", which is a normal outcome and not a
     * failure.
     */
    /**
     * Runs one POST, behind a confirmation, and reports what came back.
     *
     * <p>`after` exists because the default refresh is the world-integrity readout, which is right for
     * a world repair and meaningless for a national-ratings reset — a button that refreshes a panel
     * it did not change is a panel that lies about being current. A caller that changed something else
     * names what to re-read.
     */
    async function runRepair(button, { confirmText, path, successNote, after }) {
        if (!window.confirm(confirmText)) return;
        button.disabled = true;
        const original = button.textContent;
        button.textContent = 'Working...';
        try {
            const res = await authFetch(path, { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            const detail = Object.entries(body)
                .filter(([k]) => k !== 'action')
                .map(([k, v]) => `${k}: ${v}`)
                .join('\n');
            window.alert(`${body.action || successNote}\n\n${detail}`);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.textContent = original;
            button.disabled = false;
            if (after) await after();
            else await showWorldIntegrity();
        }
    }

    /**
     * Reads which countries are not on the starting national rating, and says so on the panel.
     *
     * <p>Read-only, so it asks no question and takes nothing. Rendered rather than alerted because the
     * answer is a list: a list behind an "OK" button has to be read, dismissed and remembered, and the
     * count is the part that matters — "no country is off the starting rating" is a finding, and so is
     * "eleven are".
     */
    async function showNationalRatingOffenders(button) {
        const box = document.getElementById('fm-nt-ratings');
        if (!box) return;
        const original = button?.textContent;
        if (button) {
            button.disabled = true;
            button.textContent = 'Reading...';
        }
        try {
            const res = await authFetch('/admin/national-ratings/offenders');
            const body = await res.json();
            const offenders = Array.isArray(body.offenders) ? body.offenders : [];
            const starting = body.startingRating;
            box.innerHTML = offenders.length === 0
                ? `<p class="fm-subtle">Every country is on the starting rating${starting ? ` (${escapeHtml(starting)})` : ''}. Nothing to reset.</p>`
                : `<div class="fm-squad-wrap"><table class="fm-squad fm-league-table">
                       <thead><tr><th>Country</th><th>Senior</th><th>U-21</th></tr></thead>
                       <tbody>${offenders.map(line => {
                           // "Germany: senior 1612, u21 1488" -> three cells, so the two numbers are
                           // readable rather than being one string an admin has to parse.
                           const [name, senior, u21] = String(line).split(/:\s*|,\s*u21\s*/);
                           return `<tr>
                               <td>${escapeHtml(name || line)}</td>
                               <td>${escapeHtml(senior || '—')}</td>
                               <td>${escapeHtml(u21 || '—')}</td>
                           </tr>`;
                       }).join('')}</tbody>
                   </table></div>
                   <p class="fm-subtle">${offenders.length} of them are off the starting rating${starting ? ` (${escapeHtml(starting)})` : ''}.</p>`;
        } catch (err) {
            box.innerHTML = `<p class="fm-subtle">Could not read the national ratings: ${escapeHtml(err.message)}</p>`;
        } finally {
            if (button) {
                button.textContent = original;
                button.disabled = false;
            }
        }
    }

    /**
     * Reads the world's actual state and shows it on the page.
     *
     * <p>Shown up front so an admin can see whether the world is whole before touching it, rather
     * than finding out afterwards from a failure.
     */
    async function showWorldIntegrity() {
        const box = document.getElementById('fm-integrity');
        if (!box) return;
        try {
            const res = await authFetch('/admin/world-integrity');
            const w = await res.json();
            box.innerHTML = `
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div class="fm-stat-card"><span>Countries</span><strong>${w.countries} / ${w.expectedCountries}</strong></div>
                    <div class="fm-stat-card"><span>National sides</span><strong>${w.nationalSides}</strong></div>
                    <div class="fm-stat-card"><span>Sides with a squad</span><strong>${w.seniorSidesWithSquad} / ${w.seniorSides}</strong></div>
                    <div class="fm-stat-card"><span>Legacy rows</span><strong>${w.legacyRows.length}</strong></div>
                </div>
                ${w.healthy
                    ? '<p class="fm-subtle">World is whole.</p>'
                    : `<p class="fm-subtle">World needs repair${w.legacyRows.length ? ` (legacy rows: ${w.legacyRows.join(', ')})` : ''}.</p>`}`;
        } catch (err) {
            box.innerHTML = `<p class="fm-subtle">Could not read the world state: ${escapeHtml(err.message)}</p>`;
        }
    }

    async function handleTool(button) {
        const action = button?.dataset?.adminAction;
        if (action === 'export-tactics') {
            await saveDefaultTactics(button);
            return;
        }
        if (action === 'repair-world') {
            await runRepair(button, {
                confirmText: 'Check the world and rebuild whatever is missing?\n\nThis tops up missing countries and national squads. Existing data is kept.',
                path: '/admin/world-integrity/repair',
                successNote: 'World repaired'
            });
            return;
        }
        if (action === 'reseed-national-teams') {
            await runRepair(button, {
                confirmText: 'Re-seed the national teams?\n\nAny side with no squad gets one. Sides that already have a squad are left alone.',
                path: '/admin/world-reseed?what=national-teams',
                successNote: 'National teams re-seeded'
            });
            return;
        }
        /**
         * Draws the national-team qualifying groups for both levels, creating the four competitions
         * if they are missing.
         *
         * <p>Idempotent: `ensureGroupStage` draws only what is not already drawn, so pressing this on a
         * world whose groups exist reports zero rather than dealing a second set.
         *
         * <p>This replaces a handler called `national-tournaments`, which had never had a button and
         * posted to the same endpoint. Two handlers for one endpoint is one of them dead, and the
         * orphan is indistinguishable from the live one by reading it.
         */
        if (action === 'seed-national-tournaments') {
            await runRepair(button, {
                confirmText: 'Draw the national-team qualifying groups?\n\n'
                    + 'Creates the four competitions if they are missing, then draws the qualifying groups '
                    + 'for the senior and U-21 sides. Groups that are already drawn are left alone, so this '
                    + 'is safe to press on a world that already has them.',
                path: '/admin/national-tournaments',
                successNote: 'National qualifying groups drawn'
            });
            return;
        }
        /**
         * Draws whatever the tournament results so far allow — the round of 16 once the groups are
         * decided, the quarter-finals once the round of 16 is played, and so on.
         *
         * <p>The manual counterpart to the week-12 draw job, for a tournament that stalled and should not
         * wait for the clock to be nudged. It never touches qualifying, so it cannot invalidate a group.
         */
        if (action === 'advance-national-tournaments') {
            await runRepair(button, {
                confirmText: 'Draw the next tournament round?\n\n'
                    + 'Draws whatever the results so far allow, a round at a time, for both the senior and '
                    + 'U-21 tournaments. Rounds that are already drawn are left alone.\n\n'
                    + 'If nothing is drawn, every group is still undecided or the tournament has finished.',
                path: '/admin/national-tournaments/advance',
                successNote: 'Tournament round drawn'
            });
            return;
        }
        if (action === 'redraw-international-cups') {
            // The two seasons, read from the card's own inputs so a manager can reach season 1 — the one
            // season with no previous season to qualify off, and therefore the one the automatic pairing
            // (active -> active+1) can never reach.
            const card = button.closest('section, .fm-panel, div');
            const qualifying = Number(card?.querySelector('[data-cup-qualify-from]')?.value || 0);
            const drawnInto = Number(card?.querySelector('[data-cup-draw-into]')?.value || 0);

            // The "existing fixtures are left alone" in this card's body is TRUE, and it was verified
            // rather than assumed — because it reads exactly like the national re-draw's, which says the
            // same thing and is the opposite. Three links:
            //
            //   1. the endpoint passes the job its own DRAW_WEEK (12), and the knockout weeks are
            //      {7,8,9,10}, so this button can only ever reach the group-draw branch
            //   2. `buildGroupStage` counts the group fixtures already in the season and returns early
            //      on any, so a second run draws nothing
            //   3. the one write a re-run can still make is giving squads to simulated entrants that have
            //      none, and `LazySquadGenerator.needsSquad` skips any club that already has players
            //
            // `InternationalClubCupDrawTest` also pins the idempotence directly. **So do not "correct" this
            // sentence to sound more cautious** — it would become false, and the national one beside it is
            // the one that needed rewriting.
            await runRepair(button, {
                confirmText: 'Re-draw the international club cups?\n\n'
                    + 'The entrants come from one season\'s tables and the groups are drawn into the next.\n\n'
                    + 'Season 1 has no previous season to qualify off, so for the first season of a world the '
                    + 'current tables are used instead. If the group stage is already drawn this draws '
                    + 'nothing, and existing fixtures are left alone.',
                path: `/admin/international-club-cups/redraw?qualifyingFrom=${qualifying}&drawnInto=${drawnInto}`,
                successNote: 'International club-cup draw job run'
            });
            return;
        }
        if (action === 'redraw-national-tournaments') {
            // This one deletes. `NationalTournamentWorldService.forceRedraw()` calls `clearUnplayed` on
            // both levels' qualifiers AND tournaments, so every unplayed fixture for the season goes,
            // then fresh groups are dealt. It also refuses, before deleting anything, once a qualifying
            // tie has been played — a played fixture carries a group code, and a re-draw deals new
            // groups, so the result would sit on a table its two nations are no longer in.
            //
            // The confirmation used to say "Existing fixtures are left alone". It is the exact opposite,
            // and it is the sentence that had to be found by reading the service rather than the button.
            await runRepair(button, {
                confirmText: 'Re-draw the national-team competitions?\n\n'
                    + 'This DELETES every unplayed qualifying and knockout fixture for the season, for both '
                    + 'senior and U-21, and deals the qualifying groups again.\n\n'
                    + 'It refuses if any qualifying tie has already been played — a played result belongs '
                    + 'to the group it was played in, and re-drawing would leave it on a table its nations '
                    + 'are no longer in. That refusal is the point, not a limitation.',
                path: '/admin/national-tournaments/redraw',
                successNote: 'National-team qualifying groups re-drawn'
            });
            return;
        }
        if (action === 'national-ratings-reset') {
            await runRepair(button, {
                confirmText: 'Reset every national rating to 1500?\n\n'
                    + 'This throws away every rating the national sides earned from real results, for all '
                    + 'countries, both levels. It is how you undo a ratings replay that went wrong.',
                path: '/admin/national-ratings/reset',
                successNote: 'National ratings reset to the starting rating',
                after: () => showNationalRatingOffenders(button)
            });
            return;
        }
        /**
         * Which countries are not on the starting rating.
         *
         * <p><b>This handler read a path that does not exist and then read the answer in a shape the
         * real response does not have.</b> It asked `/admin/national-ratings/violations` — the route is
         * `/admin/national-ratings/offenders` — and then read `v.violations || v.length || 'none'`. The
         * endpoint returns `{ startingRating, offenders: [...] }`, so `v.violations` is undefined and
         * `v.length` on an object is undefined: it printed <b>"none"</b> with offenders on the board.
         * A diagnostic that reports all clear while the data says otherwise is worse than none.
         *
         * <p>It also used `alert`, so a list could only ever be read by dismissing it. It renders on
         * the panel now, and it is read-only — it changes nothing, which is why it asks no question.
         */
        if (action === 'national-ratings-violations') {
            await showNationalRatingOffenders(button);
            return;
        }
        if (action === 'refresh-jobs') {
            await showJobs();
            return;
        }
        if (action === 'run-due-jobs') {
            await runDueJobs(button);
            return;
        }
        if (action === 'create-backup') {
            await createBackup(button);
            return;
        }
        if (action === 'restore-backup') {
            await restoreBackup(button.dataset.backupName);
            return;
        }
        if (action === 'seed-other-nations') {
            // A job, not a repair: this one takes minutes, so it goes through the polling job path the
            // reset and initialise buttons use rather than runRepair(), which expects a reply now.
            const confirmSeed = window.confirm(
                'Seed the other nations?\n\n' +
                'This builds every country that is not activated: its divisions, clubs, ratings and a ' +
                'standing table. No players are generated and no matches are played.\n\n' +
                'It takes a few minutes and it is the longest job in this panel. Countries that already ' +
                'exist are left alone, so it is safe to run twice.');
            if (!confirmSeed) return;
            if (typeof window.seedOtherNations === 'function') {
                await window.seedOtherNations();
            } else {
                window.alert('This admin action is not available right now.');
            }
            return;
        }
        if (action === 'seed-tactics') {
            // A job, not a repair: one row per club, so it goes through the polling job path rather than
            // runRepair(), which expects a reply now.
            const confirmSeed = window.confirm(
                'Give every club a default tactic?\n\n' +
                'Every club that has no tactics of its own is given the same default, copied from the club ' +
                'that already has tactics.\n\n' +
                'Clubs that already have tactics are left exactly as they are, so this is safe to run ' +
                'twice. Nothing is deleted and no match is played.');
            if (!confirmSeed) return;
            if (typeof window.seedDefaultTactics === 'function') {
                await window.seedDefaultTactics();
            } else {
                window.alert('This admin action is not available right now.');
            }
            return;
        }
        if (action === 'run-job-offers') {
            // Not runRepair(): that ends by re-reading world integrity, which has nothing to do with job
            // offers, and the count is the whole answer here.
            if (!window.confirm(
                'Offer jobs to managers?\n\n' +
                'A few AI clubs from higher tiers offer their job to a few human managers in lower tiers, ' +
                'in the same country.\n\n' +
                'A manager who is already holding an offer is skipped, so pressing this twice does not ' +
                'stack decisions. Each offer stands for 24 real hours and the manager answers on his ' +
                'Office page.')) return;
            button.disabled = true;
            const original = button.textContent;
            button.textContent = 'Working...';
            try {
                const res = await authFetch('/admin/job-offers/run', { method: 'POST' });
                const body = await res.json().catch(() => ({}));
                window.alert(`${body.offers} offer(s) made.\n\n`
                    + 'They are on the managers\' Office pages and in their notifications.');
            } catch (err) {
                window.alert(`Error: ${err.message}`);
            } finally {
                button.textContent = original;
                button.disabled = false;
            }
            return;
        }
        if (action === 'redraw-cup') {
            await runRepair(button, {
                confirmText: 'Re-draw the cup?\n\nRounds that already have ties are left alone, so this only fills in rounds that never got drawn.',
                path: '/admin/world-reseed?what=cup',
                successNote: 'Cup re-drawn'
            });
            return;
        }
        if (action === 'repair-international-cups') {
            await runRepair(button, {
                confirmText: 'Repair the international club cups?\n\nThis creates the 15 cup rows and fills any missing simulated-country club structures. Existing data is kept.',
                path: '/admin/world-reseed?what=international-cups',
                successNote: 'International club cups repaired'
            });
            return;
        }
        if (action === 'repair-club-links') {
            // Not runRepair(): that ends by re-reading world integrity, which is the wrong thing to
            // refresh after touching accounts. The count comes back in the response instead.
            if (!window.confirm(
                'Repair club links?\n\n' +
                'Fills in the club foreign key for any account created before it existed. ' +
                'Nothing is duplicated and nothing else changes.')) return;
            button.disabled = true;
            try {
                const res = await authFetch('/admin/users/repair-club-links', { method: 'POST' });
                const body = await res.json().catch(() => ({}));
                window.alert(body.repaired === 0
                    ? 'Nothing to repair — every account already has a club link.'
                    : `Repaired ${body.repaired} account(s).`);
            } catch (err) {
                window.alert(`Error: ${err.message}`);
            } finally {
                button.disabled = false;
                await showUserManagement();
            }
            return;
        }
        const handler = action === 'reset' ? window.resetDatabase : window.initializeDatabase;
        if (typeof handler !== 'function') {
            window.alert('This admin action is not available right now.');
            return;
        }
        const warning = action === 'reset'
            ? 'Reset the database?\n\nThis clears all local football data. You will need to Initialize afterwards to rebuild the world.'
            : 'Initialize the database?\n\nThis rebuilds the whole world: 48 countries, 96 national squads, the club pyramid, cups and internationals. It takes about a minute.';
        if (!window.confirm(warning)) return;
        button.disabled = true;
        try {
            await handler();
        } finally {
            button.disabled = false;
            await showWorldIntegrity();
        }
    }

    function toolCard({ title, body, action, label, variant = 'secondary', extra = '' }) {
        return `
            <article class="community-tool-card">
                <h4>${title}</h4>
                <p class="fm-subtle">${body}</p>
                ${extra}
                <button type="button" class="fm-action-btn ${variant}" data-admin-action="${action}">${label}</button>
            </article>`;
    }

    /**
     * The activation list.
     *
     * <p>Loading the countries and activating one are separated deliberately. This panel is the one
     * place in the admin where a click writes 7,750 rows, so the list is cheap to read and the button
     * is the expensive part — with the size stated on the button rather than discovered afterwards.
     */
    /**
     * Dumps the database and reports what was written.
     *
     * <p>Not runRepair(): that ends by re-reading the world, which a dump does not change, and it
     * reports the server's whole payload, which for a dump is one file name and a size.
     */
    async function createBackup(button) {
        button.disabled = true;
        const original = button.textContent;
        button.textContent = 'Dumping...';
        try {
            const res = await authFetch('/admin/backups', { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            const backup = body.backup || {};
            window.alert(`Backup written: ${backup.name}\n\n${backup.bytes} bytes at ${backup.createdAt}`);
        } catch (err) {
            window.alert(`Backup failed: ${err.message}`);
        } finally {
            button.textContent = original;
            button.disabled = false;
            await showBackups();
        }
    }

    /**
     * Puts a dump back, after saying plainly what it costs.
     *
     * <p>A restore replaces the world. The file being restored is named in the confirmation, because
     * "are you sure?" without saying which file is a question nobody can answer.
     */
    async function restoreBackup(name) {
        if (!name) return;
        const confirmed = window.confirm(
            `Replace the database with ${name}?\n\n` +
            'Everything in the current database is destroyed: countries, clubs, players, fixtures and ' +
            'results. The dump has to be a complete backup of this application for the restore to succeed.\n\n' +
            'The application has to be restarted afterwards, because it is connected to the database it ' +
            'just replaced.');
        if (!confirmed) return;
        try {
            const res = await authFetch(`/admin/backups/${encodeURIComponent(name)}/restore`, { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            const restored = body.restore || {};
            window.alert(restored.note || 'Database restored.');
        } catch (err) {
            window.alert(`Restore failed: ${err.message}`);
        } finally {
            await showBackups();
        }
    }

    /**
     * The dumps on the server, newest first.
     *
     * <p>Rendered as a table rather than more tool cards because the count grows without limit and
     * the only question the list answers is "which one do I restore", which is a per-row decision.
     */
    async function showBackups() {
        const host = document.getElementById('fm-backups');
        if (!host) return;
        try {
            const res = await authFetch('/admin/backups');
            const rows = (await res.json()).backups || [];
            if (!rows.length) {
                host.innerHTML = '<p class="fm-subtle">No backups yet. Create one above.</p>';
                return;
            }
            host.innerHTML = `
                <div class="fm-table-wrap"><table class="fm-squad fm-jobs-table">
                    <thead>
                        <tr><th>Backup</th><th>Taken</th><th>Size</th><th></th></tr>
                    </thead>
                    <tbody>
                        ${rows.map(row => `
                            <tr>
                                <td>${escapeHtml(row.name)}</td>
                                <td>${escapeHtml(row.createdAt || '')}</td>
                                <td>${row.bytes} B</td>
                                <td>
                                    <button type="button" class="fm-action-btn"
                                        data-admin-action="restore-backup"
                                        data-backup-name="${escapeHtml(row.name)}">Restore</button>
                                </td>
                            </tr>`).join('')}
                    </tbody>
                </table>`;
            // These rows are rendered after the panel's one-off listener pass, so they bind here.
            host.querySelectorAll('[data-admin-action="restore-backup"]').forEach((button) => {
                button.addEventListener('click', () => handleTool(button));
            });
        } catch (err) {
            host.innerHTML = `<p class="fm-subtle">Could not read the backups: ${escapeHtml(err.message)}</p>`;
        }
    }

    /**
     * The jobs table: trigger, last outcome, next trigger, failure count.
     *
     * <p>The FAILED badge is the point of this panel (owner, 2026-10-07). A job that throws is retried on
     * the next hour and retried again, quietly - which is the owner's rule and it is correct, but it means
     * a permanently broken job looks exactly like a healthy one. Nothing else in the application showed
     * the status at all.
     */
    /**
     * Which column the jobs table is sorted by, and which way.
     *
     * <p>Owner request, 2026-10-13: *"hocu da lista jobova u admin delu moze da se slozi po vremenu
     * zadnjeg izvrsenja, vremenu narednog izvrsenja"*. Module-level so the choice survives the panel
     * being re-rendered, which is what a user expects of a sort.
     */
    let jobSort = 'last';
    let jobSortDir = 'desc';

    const sortArrow = (column, active, dir) =>
        active !== column ? '' : `<span aria-hidden="true"> ${dir === 'asc' ? '▲' : '▼'}</span>`;

    /** "next" sorts on hours-until-fire; a job with no known next trigger sorts last either way. */
    function sortJobs(jobs) {
        const dir = jobSortDir === 'asc' ? 1 : -1;
        return [...jobs].sort((a, b) => {
            if (jobSort === 'next') {
                const left = typeof a.nextInHours === 'number' ? a.nextInHours : Number.MAX_SAFE_INTEGER;
                const right = typeof b.nextInHours === 'number' ? b.nextInHours : Number.MAX_SAFE_INTEGER;
                if (left !== right) return (left - right) * dir;
            } else {
                // A job that has never run has no timestamp and belongs at the end in both directions -
                // otherwise "oldest first" would put the never-run jobs at the top, which reads as
                // "these are the ones that have waited longest" when they have never run at all.
                const aTime = a.lastRunAt ? new Date(a.lastRunAt).getTime() : null;
                const bTime = b.lastRunAt ? new Date(b.lastRunAt).getTime() : null;
                if (aTime === null && bTime === null) return String(a.key).localeCompare(String(b.key));
                if (aTime === null) return 1;
                if (bTime === null) return -1;
                if (aTime !== bTime) return (aTime - bTime) * dir;
            }
            return String(a.key).localeCompare(String(b.key));
        });
    }

    const ariaDir = () => (jobSortDir === 'asc' ? 'ascending' : 'descending');

    function wireJobSorting(host) {
        host.querySelectorAll('[data-sort-jobs]').forEach(th => {
            const apply = () => {
                const column = th.dataset.sortJobs;
                // Same column again flips the direction; a new column starts descending, which is what
                // "sort by last run" means to somebody looking for a job that just failed.
                if (jobSort === column) {
                    jobSortDir = jobSortDir === 'asc' ? 'desc' : 'asc';
                } else {
                    jobSort = column;
                    jobSortDir = 'desc';
                }
                void showJobs();
            };
            th.addEventListener('click', apply);
            th.addEventListener('keydown', event => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    apply();
                }
            });
        });
    }

async function showJobs() {
        const host = document.getElementById('fm-jobs');
        if (!host) return;
        try {
            const res = await authFetch('/admin/jobs');
            const data = await res.json();
            const jobs = Array.isArray(data.jobs) ? data.jobs : [];

            const banner = data.failing
                ? `<div class="fm-callout fm-callout--warning">${data.failureCount} job run(s) have
                   FAILED. A failed job is retried on the next hour; a permanently failing one will keep
                   failing quietly.</div>`
                : '';

            host.innerHTML = banner + `
                <p class="fm-subtle">Season ${escapeHtml(data.season)} · week ${escapeHtml(data.week)}
                    · ${escapeHtml(data.dayLabel || ('day ' + data.day))} · ${escapeHtml(data.hour)}:00</p>
                <div class="fm-table-wrap">
                    <table class="fm-squad fm-jobs-table">
                        <thead><tr>
                            <th class="sq-name">Job</th>
                            <th>Trigger</th>
                            <th class="fm-sortable${jobSort === 'last' ? ' is-sorted' : ''}"
                                data-sort-jobs="last" role="button" tabindex="0"
                                aria-sort="${jobSort === 'last' ? ariaDir() : 'none'}">
                                Last run ${sortArrow('last', jobSort, jobSortDir)}
                            </th>
                            <th class="fm-sortable${jobSort === 'next' ? ' is-sorted' : ''}"
                                data-sort-jobs="next" role="button" tabindex="0"
                                aria-sort="${jobSort === 'next' ? ariaDir() : 'none'}">
                                Next trigger ${sortArrow('next', jobSort, jobSortDir)}
                            </th>
                            <th>Season</th>
                        </tr></thead>
                        <tbody>${sortJobs(jobs).map(job => `
                            <tr${job.lastStatus === 'FAILED' ? ' class="fm-job-row--failed"' : ''}>
                                <td class="sq-name"><strong>${escapeHtml(job.key)}</strong></td>
                                <td>${escapeHtml(job.trigger || '')}</td>
                                <td>${statusCell(job)}</td>
                                <td>${nextTriggerCell(job)}</td>
                                <td>${seasonCell(job)}</td>
                            </tr>`).join('')}
                        </tbody>
                    </table>
                </div>
                <p class="fm-subtle">Times are Belgrade. Press a heading to sort.</p>`;
            wireJobSorting(host);
        } catch (err) {
            // **The message used to say "Could not read the jobs" for anything that went wrong here,
            // and that is how a render bug presented as a server fault for a session.**
            //
            // The read was fine — `/admin/jobs` answers 200 with 44 rows — and the template threw on a
            // `sort` that was never declared, because the state variable is called `jobSort`. The catch
            // discarded the error, so the panel said the read had failed. Two hours later the useful
            // question ("is the server down?") has no answer on the screen.
            //
            // So the reason is shown, and the two cases are told apart: a bad response says so with its
            // status, and anything else says what actually threw. An owner reading this can act; the
            // old sentence could not.
            const status = err && typeof err.status === 'number' ? err.status : null;
            const reason = err && err.message ? err.message : 'unknown error';
            host.innerHTML = `<div class="fm-callout fm-callout--warning">
                <strong>The jobs table could not be shown.</strong>
                ${status != null
                    ? `The server answered ${escapeHtml(String(status))} for <code>/admin/jobs</code>.`
                    : `The jobs were read but the panel failed to build: ${escapeHtml(reason)}`}
                </div>`;
            console.error('Admin jobs panel failed:', err);
        }
    }

    /**
     * Runs whatever is due, and says what it did.
     *
     * <p>The outcome is reported because "pressed the button" and "it worked" are different claims, and
     * this is the only place a manager can see which of the two happened.
     */
    async function runDueJobs(button) {
        button.disabled = true;
        const original = button.textContent;
        button.textContent = 'Running...';
        try {
            const res = await authFetch('/api/jobs/run-due', { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            const ran = body.ran ?? 0;
            const failed = body.failed ?? 0;
            window.alert(`Ran ${ran} job(s), skipped ${body.skipped ?? 0}, failed ${failed}.`
                + (failed ? '\n\nFailed: ' + (body.jobs || []).filter(j => j.status === 'FAILED')
                    .map(j => j.key).join(', ') : ''));
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.textContent = original;
            button.disabled = false;
            await showJobs();
        }
    }

    /** Status badge, timestamp and the failure message - the three things that answer "did it run". */
    /**
     * An instant, written in Belgrade time.
     *
     * <p>Owner request, 2026-10-13: *"hocu da vremena jobova u admin delu pisu Belgrade time"*.
     *
     * <p>The panel was printing the raw ISO string the server sends — `2026-10-13T12:56:42.578205Z` — so
     * every timestamp was UTC with a trailing Z, while the log file next to it was in local time and the
     * owner read the two as contradicting each other. They were the same instant and neither said which
     * clock it was on.
     *
     * <p><b>Named zone, not a fixed offset.</b> Belgrade is UTC+2 in summer and UTC+1 in winter, so
     * hard-coding +02:00 would be wrong for half the year and silently so. An unparseable value is
     * passed through rather than replaced with "—", because losing a timestamp is worse than showing an
     * ugly one.
     */
    function belgradeTime(iso) {
        if (!iso) return '';
        const when = new Date(iso);
        if (Number.isNaN(when.getTime())) return String(iso);
        try {
            return new Intl.DateTimeFormat('en-GB', {
                timeZone: 'Europe/Belgrade',
                day: '2-digit', month: '2-digit', year: 'numeric',
                hour: '2-digit', minute: '2-digit', second: '2-digit',
                hour12: false,
            }).format(when) + ' Belgrade';
        } catch (err) {
            // A runtime without the zone database still gets a usable local time rather than nothing.
            return when.toLocaleString();
        }
    }

    function statusCell(job) {
        if (!job.lastStatus) {
            return '<span class="fm-subtle">never</span>';
        }
        const statusIcon = job.lastStatus === 'DONE' ? '✅' : job.lastStatus === 'FAILED' ? '❌' : '⏳';
        const badge = `<span class="fm-job-status fm-job-status--${escapeHtml(String(job.lastStatus).toLowerCase())}">${statusIcon} ${escapeHtml(job.lastStatus)}</span>`;
        const when = job.lastRunAt
            ? `<div class="fm-subtle">${escapeHtml(belgradeTime(job.lastRunAt))}</div>`
            : '';
        const message = job.lastMessage ? `<div class="fm-job-message">${escapeHtml(job.lastMessage)}</div>` : '';
        return badge + when + message;
    }

    function nextTriggerCell(job) {
        const when = escapeHtml(job.nextTrigger || '—');
        const ahead = job.nextInHours >= 0 ? `<div class="fm-subtle">in ${job.nextInHours} h</div>` : '';
        return when + ahead;
    }

    function seasonCell(job) {
        const runs = `<span>${job.runsThisSeason || 0} run(s)</span>`;
        const failed = job.failuresThisSeason
            ? `<div><span class="fm-job-status fm-job-status--failed">${job.failuresThisSeason} failed</span></div>`
            : '';
        return runs + failed;
    }

    async function showCountryActivation() {
        const host = document.getElementById('fm-activation');
        if (!host) return;
        try {
            const res = await authFetch('/admin/countries');
            const rows = await res.json();
            if (!Array.isArray(rows) || rows.length === 0) {
                host.innerHTML = '<p class="fm-subtle">No countries found.</p>';
                return;
            }
            const active = rows.filter(row => row.active);
            const dormant = rows.filter(row => !row.active);
            host.innerHTML = `
                <p class="fm-subtle">${active.length} active, ${dormant.length} represented.
                    A represented country plays its national sides only.</p>
                <div class="fm-activation-list">
                    ${dormant.map(row => activationRow(row)).join('')}
                </div>`;
            host.querySelectorAll('.js-activate-country').forEach(button => {
                button.addEventListener('click', () => activateCountry(button));
            });
            renderNamePools(host, rows);
        } catch (err) {
            host.innerHTML = `<p style="color:#f44336;">Could not load the countries: ${escapeHtml(err.message)}</p>`;
        }
    }

    /**
     * The name pools, per country (redesign.md R-23).
     *
     * <p>The owner asked for these to be settable per country - the club names, the cities and the
     * players' first and last names, from which the seeder draws at random - and for the boxes to
     * take the names as a person would type them: **separated by commas**.
     */
    function renderNamePools(host, rows) {
        const section = document.createElement('section');
        section.className = 'fm-name-pools';
        section.innerHTML = `
            <div class="fm-panel-head">
                <div>
                    <h3>Names per country</h3>
                    <p class="fm-subtle">The club names, the cities and the players' names the seeder
                        draws from, one country at a time. Separate the names with commas.</p>
                </div>
            </div>
            <div class="fm-name-pools-bar">
                <label class="fm-field-label" for="fm-name-pool-country">Country</label>
                <select id="fm-name-pool-country" class="fm-season-select">
                    ${rows.map(row => `<option value="${escapeHtml(row.isoCode)}">${escapeHtml(row.name)} (${escapeHtml(row.isoCode)})</option>`).join('')}
                </select>
                <span class="fm-subtle" id="fm-name-pool-source"></span>
            </div>
            <div class="fm-name-pools-grid">
                <label class="fm-name-pool-box">
                    <span>Club names</span>
                    <textarea id="fm-name-pool-clubs" rows="4" placeholder="Crvena zvezda, Partizan, Vojvodina"></textarea>
                </label>
                <label class="fm-name-pool-box">
                    <span>Cities</span>
                    <textarea id="fm-name-pool-cities" rows="4" placeholder="Beograd, Novi Sad, Niš"></textarea>
                </label>
                <label class="fm-name-pool-box">
                    <span>First names</span>
                    <textarea id="fm-name-pool-first" rows="4" placeholder="Aleksandar, Nikola, Marko"></textarea>
                </label>
                <label class="fm-name-pool-box">
                    <span>Last names</span>
                    <textarea id="fm-name-pool-last" rows="4" placeholder="Jovanović, Petrović, Nikolić"></textarea>
                </label>
            </div>
            <div class="fm-name-pools-actions">
                <button type="button" class="fm-action-btn" id="fm-name-pool-save">Save names</button>
                <button type="button" class="fm-action-btn secondary" id="fm-name-pool-export">Export as file</button>
                <span class="fm-subtle" id="fm-name-pool-status"></span>
            </div>`;
        host.appendChild(section);

        const select = section.querySelector('#fm-name-pool-country');
        const status = section.querySelector('#fm-name-pool-status');
        const source = section.querySelector('#fm-name-pool-source');
        const fields = {
            clubs: section.querySelector('#fm-name-pool-clubs'),
            cities: section.querySelector('#fm-name-pool-cities'),
            firstNames: section.querySelector('#fm-name-pool-first'),
            lastNames: section.querySelector('#fm-name-pool-last')
        };

        const load = async () => {
            const iso = select.value;
            if (!iso) return;
            status.textContent = 'Loading…';
            try {
                const res = await authFetch(`/admin/countries/${encodeURIComponent(iso)}/names`);
                const pool = await res.json();
                fields.clubs.value = (pool.clubs || []).join(', ');
                fields.cities.value = (pool.cities || []).join(', ');
                fields.firstNames.value = (pool.firstNames || []).join(', ');
                fields.lastNames.value = (pool.lastNames || []).join(', ');
                source.textContent = pool.source === 'database'
                    ? 'Saved names.'
                    : pool.source === 'file'
                        ? 'The file\'s names - save to keep them.'
                        : 'No names set for this country yet.';
                status.textContent = '';
            } catch (err) {
                status.textContent = 'The names could not be read: ' + err.message;
            }
        };

        const split = text => String(text || '')
            .split(',')
            .map(value => value.trim())
            .filter(Boolean);

        section.querySelector('#fm-name-pool-save').addEventListener('click', async () => {
            const iso = select.value;
            if (!iso) return;
            status.textContent = 'Saving…';
            try {
                const res = await authFetch(`/admin/countries/${encodeURIComponent(iso)}/names`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        clubs: split(fields.clubs.value),
                        cities: split(fields.cities.value),
                        firstNames: split(fields.firstNames.value),
                        lastNames: split(fields.lastNames.value)
                    })
                });
                const body = await res.json().catch(() => ({}));
                if (!res.ok) {
                    status.textContent = body.message || 'The names were refused.';
                    return;
                }
                status.textContent = `Saved ${body.written || 0} names.`;
                source.textContent = 'Saved names.';
            } catch (err) {
                status.textContent = 'The names could not be saved: ' + err.message;
            }
        });

        section.querySelector('#fm-name-pool-export').addEventListener('click', () => {
            const iso = select.value;
            const payload = {
                iso,
                clubs: split(fields.clubs.value),
                cities: split(fields.cities.value),
                firstNames: split(fields.firstNames.value),
                lastNames: split(fields.lastNames.value)
            };
            const blob = new Blob([JSON.stringify(payload, null, 2) + '\n'], { type: 'application/json' });
            const link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = `${iso || 'country'}.json`;
            document.body.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(link.href);
        });

        select.addEventListener('change', load);
        load();
    }

    function activationRow(row) {
        const hasFootball = row.divisions > 0;
        return `
            <div class="fm-activation-row${row.active ? ' is-active' : ''}">
                <span class="fm-activation-name">${escapeHtml(row.name)}</span>
                <span class="fm-subtle">${escapeHtml(row.isoCode)}</span>
                <span class="fm-subtle">${hasFootball ? `${row.divisions} divisions` : 'no divisions'}</span>
                <button type="button" class="fm-action-btn ${row.active ? 'secondary' : ''} js-activate-country"
                        data-iso="${escapeHtml(row.isoCode)}"
                        title="${row.active
                            ? 'Already active. This tops up anything missing and changes nothing else.'
                            : 'Builds 31 divisions, 310 clubs and about 7,750 players.'}">
                    ${row.active ? 'Top up' : 'Activate'}
                </button>
            </div>`;
    }

    async function activateCountry(button) {
        const iso = button?.dataset?.iso;
        if (!iso) return;
        const warning = `Activate ${iso.toUpperCase()}?\n\nThis builds a five-tier pyramid: 31 divisions, 310 clubs and about 7,750 players. It takes a while.`;
        if (!window.confirm(warning)) return;
        button.disabled = true;
        const original = button.textContent;
        button.textContent = 'Building...';
        try {
            const res = await authFetch(`/admin/countries/${encodeURIComponent(iso)}/activate`, { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            const what = body.alreadyBuilt
                ? `${body.name} already had a pyramid - topped up, nothing duplicated.`
                : `${body.name}: ${body.divisions} divisions, ${body.clubs} clubs.`;
            window.alert(what);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            button.textContent = original;
            await showCountryActivation();
        }
    }

    /**
     * The account list: roles and forum write bans.
     *
     * <p>This panel is the only way a MOD, ADMIN or DEV account can be appointed. Before it, those three
     * roles were enum constants with no writer anywhere in the codebase — the forum's own moderator
     * office could not be handed to a person through the product at all.
     *
     * <p>The ban is deliberately described as a <i>forum</i> ban on the button and in the prompt, because
     * that is exactly what it does. A moderator reading "ban" and expecting the manager's account to
     * disappear would be wrong, and the difference matters to the person being banned.
     */
    async function showUserManagement() {
        const host = document.getElementById('fm-users');
        if (!host) return;
        try {
            const res = await authFetch('/admin/users');
            const rows = await res.json();
            if (!Array.isArray(rows) || rows.length === 0) {
                host.innerHTML = '<p class="fm-subtle">No accounts.</p>';
                return;
            }
            host.innerHTML = `
                <p class="fm-subtle">${rows.length} accounts. A forum ban stops a manager posting and
                    replying. He can still read the forum, message anyone, and play.</p>
                <div class="fm-activation-list">
                    ${rows.map(userRow).join('')}
                </div>`;
            host.querySelectorAll('.js-ban-user').forEach(button => {
                button.addEventListener('click', () => banUser(button));
            });
            host.querySelectorAll('.js-lift-ban').forEach(button => {
                button.addEventListener('click', () => liftBan(button));
            });
            host.querySelectorAll('.js-change-role').forEach(select => {
                select.addEventListener('change', () => changeRole(select));
            });
            host.querySelectorAll('.js-open-user-profile').forEach(button => {
                button.addEventListener('click', () => {
                    const id = button.dataset.userId;
                    if (id && typeof window.openUserProfile === 'function') window.openUserProfile(id);
                });
            });
        } catch (err) {
            host.innerHTML = `<p style="color:#f44336;">Could not load accounts: ${escapeHtml(err.message)}</p>`;
        }
    }

    function userRow(user) {
        const who = user.displayName || user.username || `Account ${user.id}`;
        const banned = user.forumBanned === true;
        return `
            <div class="fm-activation-row${banned ? ' is-active' : ''}">
                <!-- The name is a link to that manager's profile (owner, 2026-10-20): an admin reading
                     the account list wants to see the manager, not only to act on him. -->
                <button type="button" class="fm-activation-name js-open-user-profile"
                        data-user-id="${escapeHtml(user.id)}">${escapeHtml(who)}</button>
                <span class="fm-subtle">${escapeHtml(user.clubName || 'No club')}</span>
                <select class="js-change-role" data-user-id="${escapeHtml(user.id)}"
                        aria-label="Role for ${escapeHtml(who)}">
                    ${ROLE_OPTIONS.map(role => `
                        <option value="${role}"${user.role === role ? ' selected' : ''}>${escapeHtml(role)}</option>
                    `).join('')}
                </select>
                ${banned
                    ? `<button type="button" class="fm-action-btn secondary js-lift-ban"
                               data-user-id="${escapeHtml(user.id)}">Lift ban</button>`
                    : `<button type="button" class="fm-action-btn js-ban-user"
                               data-user-id="${escapeHtml(user.id)}"
                               data-user-name="${escapeHtml(who)}">Ban from forum</button>`}
            </div>
            ${banned ? `
                <div class="fm-subtle">
                    Banned for ${escapeHtml(user.forumBanDaysLeft)} more day(s) by
                    ${escapeHtml(user.forumBanBy || 'a moderator')}: ${escapeHtml(user.forumBanReason || 'no reason recorded')}
                </div>` : ''}`;
    }

    async function banUser(button) {
        const userId = button?.dataset?.userId;
        const who = button?.dataset?.userName || 'this manager';
        if (!userId) return;
        const days = window.prompt(`Ban ${who} from the forum for how many days?`, '7');
        if (days === null) return;
        const parsed = Number.parseInt(days, 10);
        if (!Number.isFinite(parsed) || parsed < 1) {
            window.alert('Enter a whole number of days.');
            return;
        }
        const reason = window.prompt(`Why? ${who} is told this.`);
        if (reason === null) return;
        button.disabled = true;
        try {
            const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/forum-ban`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ days: parsed, reason })
            });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`Ban failed: ${body.error || body.message || res.status}`);
                return;
            }
            window.alert(`${who} cannot post in the forum for ${parsed} day(s). Reading and messaging are unaffected.`);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            await showUserManagement();
        }
    }

    async function liftBan(button) {
        const userId = button?.dataset?.userId;
        if (!userId) return;
        button.disabled = true;
        try {
            const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/forum-ban/lift`, { method: 'POST' });
            window.alert('Ban lifted. He can post in the forum again.');
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            await showUserManagement();
        }
    }

    async function changeRole(select) {
        const userId = select?.dataset?.userId;
        const role = select?.value;
        if (!userId || !role) return;
        const previous = select.dataset.previousRole;
        try {
            const res = await authFetch(`/admin/users/${encodeURIComponent(userId)}/role`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ role })
            });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`Role change failed: ${body.error || body.message || res.status}`);
                if (previous) select.value = previous;
                return;
            }
            select.dataset.previousRole = role;
        } catch (err) {
            window.alert(`Error: ${err.message}`);
            if (previous) select.value = previous;
        }
    }

    /**
     * The registration approval queue, on its own panel.
     *
     * <p>It lived inside the community chat until P2-20 Phase 6, where the only way to approve a
     * manager was to scroll a shared feed and find the row. Two things were wrong with that beyond the
     * scrolling: <b>the applicant's email travelled through a feed every logged-in manager could read</b>
     * (gated behind an admin check, while the username was not — the exposure P0-17 recorded), and the
     * admin tab said so itself in a "Coming next" panel.
     *
     * <p>Here the queue is reachable only by staff, because the whole page is.
     */
    async function showRegistrationQueue() {
        const host = document.getElementById('fm-registrations');
        if (!host) return;
        try {
            const res = await authFetch('/admin/registration-requests');
            const rows = await res.json();
            if (!Array.isArray(rows) || rows.length === 0) {
                host.innerHTML = '<p class="fm-subtle">No one is waiting. Every application has been decided.</p>';
                return;
            }
            host.innerHTML = `
                <p class="fm-subtle">${rows.length} waiting. Approving creates the account and hands him
                    the reserved club; rejecting closes the application.</p>
                <div class="fm-activation-list">
                    ${rows.map(registrationRow).join('')}
                </div>`;
            host.querySelectorAll('.js-decide-registration').forEach(button => {
                button.addEventListener('click', () => decideRegistration(button));
            });
        } catch (err) {
            host.innerHTML = `<p style="color:#f44336;">Could not load applications: ${escapeHtml(err.message)}</p>`;
        }
    }

    function registrationRow(row) {
        return `
            <div class="fm-activation-row">
                <span class="fm-activation-name">${escapeHtml(row.username || 'Applicant')}</span>
                <span class="fm-subtle">${escapeHtml(row.countryName || row.countryCode || '—')}</span>
                <span class="fm-subtle">${escapeHtml(row.teamName || 'no club reserved')}</span>
                <button type="button" class="fm-action-btn js-decide-registration"
                        data-request-id="${escapeHtml(row.id)}"
                        data-action="approve">Approve</button>
                <button type="button" class="fm-action-btn secondary js-decide-registration"
                        data-request-id="${escapeHtml(row.id)}"
                        data-action="reject">Reject</button>
            </div>`;
    }

    /**
     * Approves or rejects one application.
     *
     * <p>A rejecting reviewer is asked why, and the note is stored on the request. An approval asks for
     * nothing: there is nothing to explain about letting somebody in, and a note field nobody fills in
     * is a field nobody reads.
     */
    async function decideRegistration(button) {
        const requestId = button?.dataset?.requestId;
        const action = button?.dataset?.action;
        if (!requestId || !action) return;

        let note = null;
        if (action === 'reject') {
            note = window.prompt('Why is this application being rejected?', '');
            if (note === null) return;
        } else if (!window.confirm(
            'Approve this application?\n\n'
            + 'It creates the account and hands him the reserved club.')) {
            return;
        }

        button.disabled = true;
        try {
            const res = await authFetch(`/admin/registration-requests/${encodeURIComponent(requestId)}/${action}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ note: note || '' })
            });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) {
                window.alert(`${action === 'approve' ? 'Approval' : 'Rejection'} failed: ${body.error || res.status}`);
                return;
            }
            window.alert(`Done. The applicant has been told by notification.`);
        } catch (err) {
            window.alert(`Error: ${err.message}`);
        } finally {
            button.disabled = false;
            await showRegistrationQueue();
        }
    }

    async function loadAdmin() {
        const mainContent = document.getElementById('main-content');
        const refusal = guard();
        if (refusal) {
            mainContent.innerHTML = refusal;
            return;
        }

        mainContent.innerHTML = `
            <div class="page-container">
                ${backButtonHtml()}
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h2>Admin</h2>
                            <p class="fm-subtle">Operational tools. Visible only to ADMIN, OWNER and DEV accounts.</p>
                        </div>
                        <span class="fm-panel-action">Admin only</span>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div class="fm-stat-card"><span>Role</span><strong>${getSessionRole() || 'ADMIN'}</strong></div>
                        <div class="fm-stat-card"><span>Signed in as</span><strong>${getUsername?.() || 'Manager'}</strong></div>
                        <div class="fm-stat-card"><span>Current club</span><strong>${getTeamName?.() || 'Unassigned'}</strong></div>
                        <div class="fm-stat-card"><span>Tool groups</span><strong data-admin-tool-groups>—</strong></div>
                    </div>
                    <nav class="fm-admin-tabs" role="tablist" aria-label="Admin sections">
                        <button type="button" class="fm-admin-tab is-active" role="tab"
                            aria-selected="true" data-admin-tab="tools">Tools</button>
                        <button type="button" class="fm-admin-tab" role="tab"
                            aria-selected="false" data-admin-tab="jobs">Jobs</button>
                    </nav>
                </section>

                <div class="fm-admin-tabpanel" data-admin-panel="jobs" hidden>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Jobs</h3>
                                <p class="fm-subtle">Every scheduled job: what it is triggered on, when it
                                    last ran, when it runs next, and whether anything failed. A job that ran
                                    and a job that did not used to look identical from here.</p>
                            </div>
                            <span class="fm-panel-action">Scheduler</span>
                        </div>
                        <div class="fm-admin-toolbar">
                            <button type="button" class="fm-action-btn" data-admin-action="run-due-jobs">
                                Run due jobs now
                            </button>
                            <button type="button" class="fm-link-btn" data-admin-action="refresh-jobs">Refresh</button>
                            <span class="fm-subtle">Runs everything whose trigger has been reached, without
                                moving the clock. Safe to press repeatedly.</span>
                        </div>
                        <div id="fm-jobs"><p class="fm-subtle">Reading...</p></div>
                    </section>
                </div>

                <div class="fm-admin-tabpanel" data-admin-panel="tools">

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Database controls</h3>
                            <p class="fm-subtle">Destructive. Use only when you intend to clear or rebuild the local data.</p>
                        </div>
                        <span class="fm-panel-action">Destructive</span>
                    </div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Reset DB',
                            body: 'Clears the football data. Your user accounts and tactic editor setups are kept. It does not rebuild anything — press Initialize DB or Seed other nations afterwards.',
                            action: 'reset',
                            label: 'Reset DB'
                        })}
                        ${toolCard({
                            title: 'Initialize DB',
                            body: 'Builds the Serbian structure: all 31 divisions, the fixture list and the players. Takes a minute or two. The other 46 countries are a separate button.',
                            action: 'initialize',
                            label: 'Initialize DB',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Export Default Tactics',
                            body: 'Makes your club\'s current tactic the default for the whole world. "Give every club a default tactic" then copies this one everywhere, and the rules are restored after a DB reset.',
                            action: 'export-tactics',
                            label: 'Save Default Tactics'
                        })}
                        <div id="fm-world-default"><p class="fm-subtle">Reading the world default...</p></div>
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Database backup</h3>
                            <p class="fm-subtle">Dumps the whole database to a timestamped file on the server,
                                and reads one back. Take a dump when the world is in a state you want to keep
                                — a clean season 1, week 1, day 1 with everything seeded and drawn.</p>
                        </div>
                        <span class="fm-panel-action">Backup</span>
                    </div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Create backup',
                            body: 'Writes the entire database to yyyy-mm-dd-HH-mm-ss.dump. It only reads, so it is safe to run at any time.',
                            action: 'create-backup',
                            label: 'Create backup',
                            variant: ''
                        })}
                    </div>
                    <div id="fm-backups"><p class="fm-subtle">Reading...</p></div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>World integrity</h3>
                            <p class="fm-subtle">What the world actually holds right now. Repair tops up
                                what is missing and keeps what is there.</p>
                        </div>
                        <span class="fm-panel-action">Check and repair</span>
                    </div>
                    <div id="fm-integrity"><p class="fm-subtle">Reading...</p></div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Repair world',
                            body: 'Checks the world and rebuilds anything missing: countries, national squads, legacy rows.',
                            action: 'repair-world',
                            label: 'Repair world'
                        })}
                        ${toolCard({
                            title: 'Re-seed national teams',
                            body: 'Gives a 25-player squad to any national side that has none. Existing squads are untouched.',
                            action: 'reseed-national-teams',
                            label: 'Re-seed national teams',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Seed other nations',
                            body: 'Builds every country that is not activated: divisions, clubs, ratings and a standing table. No players, no matches. Safe to run twice.',
                            action: 'seed-other-nations',
                            label: 'Seed other nations',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Give every club a default tactic',
                            body: 'Copies the club that already has tactics into every club that has none, so all of them have something to pick from. Clubs with tactics are left alone, so it is safe to run twice.',
                            action: 'seed-tactics',
                            label: 'Give every club a default tactic',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Offer jobs to managers',
                            body: 'A few AI clubs from higher tiers offer their job to a few human managers in lower tiers. A manager who is already holding an offer is skipped, so pressing this twice does not stack decisions. Each offer stands for 24 real hours.',
                            action: 'run-job-offers',
                            label: 'Offer jobs to managers',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Re-draw the cup',
                            body: 'Draws any cup round that never got drawn. Rounds that already have ties are left alone.',
                            action: 'redraw-cup',
                            label: 'Re-draw the cup',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Repair international cups',
                            body: 'Creates all 15 international club cup rows and fills missing simulated-country club structures. Existing data is kept.',
                            action: 'repair-international-cups',
                            label: 'Repair international cups',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Re-draw international cups',
                            body: 'Runs the club-cup draw, taking entrants from one season and drawing the groups into another. Season 1 has no previous season, so for a first season the current tables are used. If the group stage is already drawn this draws nothing.',
                            action: 'redraw-international-cups',
                            label: 'Re-draw international cups',
                            variant: '',
                            // Two inputs rather than one implied pairing. Season 1 is the season the
                            // automatic pairing can never reach - it would draw season 2 off tables
                            // nobody has played - and a manager who has just built a world needs to be
                            // able to say "draw season 1" without waiting a season for it.
                            extra: `<div class="fm-admin-season-row">
                                <label class="fm-subtle">Qualify off season
                                    <input class="fm-input" type="number" min="0" max="99"
                                           data-cup-qualify-from value="1"></label>
                                <label class="fm-subtle">Draw into season
                                    <input class="fm-input" type="number" min="1" max="99"
                                           data-cup-draw-into value="1"></label>
                            </div>`
                        })}
                    </div>
                </section>

                <!--
                    National-team competitions, in one panel.

                    These four actions were scattered and invisible: three had handlers and no button, and
                    the fourth had a finished endpoint that nothing called at all. Grouping them here is
                    not decoration - "Re-draw national competitions" sat in World integrity, so the four
                    actions that act on the same subject were split across two panels, and only one of
                    the four was reachable. The draw job runs itself on the clock; these are the manual
                    counterparts, for a world that needs building now or a tournament that has stalled.
                -->
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>National teams</h3>
                            <p class="fm-subtle">The World Cup and U-21 World Cup, for senior and U-21. The
                                draw job runs these on the clock — week 1 day 1 for the qualifying groups,
                                week 12 for the knockouts. These buttons are the manual counterpart, for a
                                world that has to be built now or a tournament that has stalled.</p>
                        </div>
                        <span class="fm-panel-action">Competitions</span>
                    </div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Draw national competitions',
                            body: 'Creates the four competitions if they are missing and draws the qualifying groups for senior and U-21. Groups that are already drawn are left alone.',
                            action: 'seed-national-tournaments',
                            label: 'Draw national competitions',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Advance tournament rounds',
                            body: 'Draws whatever the results so far allow — the round of 16 once the groups are decided, the quarter-finals once the round of 16 is played. Never touches qualifying.',
                            action: 'advance-national-tournaments',
                            label: 'Advance tournament rounds',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Re-draw national competitions',
                            body: 'Deals the qualifying groups again from scratch. It DELETES every unplayed qualifying and knockout fixture for the season, and it refuses once any qualifying tie has been played.',
                            action: 'redraw-national-tournaments',
                            label: 'Re-draw national competitions'
                        })}
                    </div>
                    <div id="fm-nt-ratings"><p class="fm-subtle">The starting rating is what every country
                        begins on. Resetting discards every rating the sides earned from real results.</p></div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Read rating violations',
                            body: 'Lists every country whose national rating is not the starting rating, for both levels. Read-only.',
                            action: 'national-ratings-violations',
                            label: 'Read rating violations',
                            variant: ''
                        })}
                        ${toolCard({
                            title: 'Reset national ratings',
                            body: 'Puts every country back on the starting rating, for both levels. Destroys every rating earned from real results.',
                            action: 'national-ratings-reset',
                            label: 'Reset national ratings'
                        })}
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Activate a country</h3>
                            <p class="fm-subtle">A country is represented by its national sides. Activating
                                one gives it a real five-tier club pyramid: 31 divisions, 310 clubs and
                                about 7,750 players, built to that tier's strength standard.</p>
                        </div>
                        <span class="fm-panel-action">Owner only</span>
                    </div>
                    <div id="fm-activation"><p class="fm-subtle">Reading...</p></div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Accounts</h3>
                            <p class="fm-subtle">Appoint moderators and control forum write bans.
                                A ban stops a manager posting; it does not touch his club or his account.</p>
                        </div>
                        <span class="fm-panel-action">Roles and bans</span>
                    </div>
                    <div id="fm-users"><p class="fm-subtle">Reading...</p></div>
                    <div class="community-tool-grid">
                        ${toolCard({
                            title: 'Repair club links',
                            body: 'Fills in the club foreign key for accounts created before it existed. Idempotent, and only needed once.',
                            action: 'repair-club-links',
                            label: 'Repair club links',
                            variant: ''
                        })}
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Applications</h3>
                            <p class="fm-subtle">Who has asked to play, and in which country. These lived
                                inside the community chat until P2-20 Phase 6, where an applicant's email
                                travelled through a feed every manager could read.</p>
                        </div>
                        <span class="fm-panel-action">Approve or reject</span>
                    </div>
                    <div id="fm-registrations"><p class="fm-subtle">Reading...</p></div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Coming next</h3>
                            <p class="fm-subtle">Further admin tooling lands here.</p>
                        </div>
                        <span class="fm-panel-action">Planned</span>
                    </div>
                    <div class="fm-empty" style="text-align:left;">
                        A moderation history. Bans are held on the account as a current state, so the
                        question "who banned that manager, and how often" cannot be answered yet.
                    </div>
                </section>
            </div>`;

        wireAdminTabs();

        // Counted from the rendered markup, not written down. It said "4" and there were seven panels
        // before this panel was added — the same drift as the academy limit that was hardcoded four
        // times in academy.js. A number nobody recomputes is a number nobody can trust.
        const toolGroups = mainContent.querySelectorAll('[data-admin-panel="tools"] > .fm-panel');
        mainContent.querySelectorAll('[data-admin-tool-groups]').forEach((cell) => {
            cell.textContent = String(toolGroups.length);
        });

        void showCountryActivation();
        void showUserManagement();
        void showRegistrationQueue();
        void showBackups();
        if (activeAdminTab() === 'jobs') void showJobs();

        mainContent.querySelectorAll('[data-admin-action]').forEach((button) => {
            button.addEventListener('click', () => handleTool(button));
        });

        await showWorldIntegrity();
    }

    return { loadAdmin };
}
