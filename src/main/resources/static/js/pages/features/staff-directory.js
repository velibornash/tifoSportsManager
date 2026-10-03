export function createStaffDirectoryFeature(deps) {
    const { authFetch, getTeamId, escapeHtml, buildClubActionsHtml, formatBudget } = deps;
    const cache = new Map();

    /**
     * Which department a role belongs to. Drives the tabs, so a new role cannot silently vanish:
     * an unmapped role falls into "other", which always renders.
     */
    const DEPARTMENT_OF = {
        HEAD_COACH: 'coaching',
        ASSISTANT: 'coaching',
        GK_COACH: 'coaching',
        SCOUT: 'scouting',
        PHYSIO: 'medical',
        YOUTH_COACH: 'academy'
    };
    const DEPARTMENT_LABEL = {
        coaching: 'Coaching',
        scouting: 'Scouting',
        medical: 'Medical',
        academy: 'Academy',
        other: 'Other'
    };

    const SLOT_LABEL = {
        HEAD_COACH: 'Head coach',
        ASSISTANT: 'Assistant coach',
        GK_COACH: 'Goalkeeping coach',
        PHYSIO: 'Physio',
        SCOUT: 'Chief scout',
        YOUTH_COACH: 'Youth coach'
    };

    /** A readable focus line, built from the attributes that are actually stored. */
    function focusFor(member) {
        const attrs = [
            ['development', 'Player development'],
            ['tactical', 'Tactical preparation'],
            ['motivation', 'Man-management'],
            ['goalkeeping', 'Goalkeeping'],
            ['fitness', 'Fitness and injury prevention'],
            ['scouting', 'Recruitment']
        ];
        return attrs
            .map(([key, label]) => ({ label, value: Number(member[key] || 0) }))
            .sort((a, b) => b.value - a.value)
            .slice(0, 2)
            .map(a => `${a.label} (${a.value})`)
            .join(' · ');
    }

    function formatWage(wage) {
        const n = Number(wage || 0);
        return `${n.toLocaleString('en-GB', { maximumFractionDigits: 0 })} € / week`;
    }

    function contractLabel(season) {
        return season ? `Jun ${season}` : '—';
    }

    /** 1-20 attributes, grouped the way the profile page shows them. */
    function sectionsFor(member) {
        return [
            ['Coaching', [
                ['Development', member.development],
                ['Tactical', member.tactical],
                ['Motivation', member.motivation]
            ]],
            ['Specialist', [
                ['Goalkeeping', member.goalkeeping],
                ['Fitness', member.fitness],
                ['Scouting', member.scouting]
            ]]
        ];
    }

    const tableRows = (members) => members.map(member => `
        <tr class="fm-squad-row cs-clickable" data-open-staff="${member.id}">
            <td class="sq-name">${escapeHtml(member.name || '—')}</td>
            <td>${escapeHtml(member.slot)}</td>
            <td>${member.age ?? '—'}</td>
            <td>${escapeHtml(member.focus)}</td>
            <td>${escapeHtml(member.contractEnd)}</td>
            <td>${escapeHtml(member.wage)}</td>
        </tr>`).join('');

    /**
     * Loads the real staff directory.
     *
     * This used to build four coaches and five support staff out of literal strings and read them
     * from `/demo/teams/{id}/coaches`, which returned a constant. Every club in the country had the
     * same staff with the same ages and the same contracts, and the wages on screen were the wages
     * of nobody. Everything now comes from the staff entities.
     */
    async function loadStaff() {
        const teamId = getTeamId();
        const [staffRes, sponsorRes, profileRes] = await Promise.all([
            authFetch(`/api/teams/${teamId}/staff`),
            authFetch(`/api/teams/${teamId}/sponsors`),
            authFetch(`/teams/${teamId}/profile`)
        ]);

        const payload = staffRes.ok ? await staffRes.json() : null;
        const sponsorPayload = sponsorRes.ok ? await sponsorRes.json() : null;
        const profile = profileRes.ok ? await profileRes.json() : {};
        const mainContent = document.getElementById('main-content');
        cache.clear();

        if (!payload) {
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-eyebrow">Club staff</div>
                        <h2>${escapeHtml(profile.name || 'Staff')}</h2>
                        <p class="fm-subtle">The staff directory is not available. It is never filled
                        with placeholders — a club with no staff shows no staff.</p>
                    </section>
                </div>`;
            return;
        }

        const members = (payload.staff || []).map(m => ({
            ...m,
            department: DEPARTMENT_OF[m.role] || 'other',
            slot: SLOT_LABEL[m.role] || m.role || '—',
            focus: focusFor(m),
            contractEnd: contractLabel(m.contractEndSeason),
            wage: formatWage(m.weeklyWage)
        }));
        members.forEach(m => cache.set(String(m.id), m));

        // Tabs are derived from the data, so a department can never be hidden by a hardcoded list.
        const departments = [...new Set(members.map(m => m.department))];
        const sponsors = (sponsorPayload && sponsorPayload.sponsors) || [];
        const activeSponsors = sponsors.filter(s => s.active);
        const vacancies = payload.vacancies || [];

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club staff</div>
                            <h2>${escapeHtml(payload.teamName || profile.name || 'Staff')}</h2>
                            <p class="fm-subtle">Real staff on the books, with the wages the club
                            actually pays them.</p>
                        </div>
                        ${buildClubActionsHtml('staff')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${members.length}</strong><span>Staff members</span></div>
                        <div><strong>${departments.length}</strong><span>Departments</span></div>
                        <div><strong>${escapeHtml(formatBudget(payload.weeklyWageTotal))}</strong><span>Weekly staff bill</span></div>
                        <div><strong>${activeSponsors.length}</strong><span>Active sponsors</span></div>
                    </div>
                    ${vacancies.length ? `<p class="fm-subtle">Vacant: ${escapeHtml(vacancies.map(v => SLOT_LABEL[v] || v).join(', '))}.</p>` : ''}
                </section>

                ${sponsors.length ? `
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div><h3>Sponsors</h3><p class="fm-subtle">Contracts on the books, and what
                        they pay this season.</p></div>
                        <span class="fm-panel-action">${escapeHtml(formatBudget(sponsorPayload.weeklyIncome))} / week</span>
                    </div>
                    <div class="fm-squad-wrap">
                        <table class="fm-squad fm-staff-table">
                            <thead><tr><th class="sq-name">Sponsor</th><th>Tier</th><th>Annual value</th><th>From</th><th>To</th><th>Weekly</th><th>Status</th></tr></thead>
                            <tbody>
                                ${sponsors.map(s => `
                                    <tr class="fm-squad-row">
                                        <td class="sq-name">${escapeHtml(s.name || '—')}</td>
                                        <td>${escapeHtml(s.tier || '—')}</td>
                                        <td>${escapeHtml(formatBudget(s.annualValue))}</td>
                                        <td>${escapeHtml(String(s.startSeason ?? '—'))}</td>
                                        <td>${escapeHtml(String(s.endSeason ?? '—'))}</td>
                                        <td>${escapeHtml(formatBudget(s.weeklyIncome))}</td>
                                        <td>${s.active ? 'Active' : 'Expired'}</td>
                                    </tr>`).join('')}
                            </tbody>
                        </table>
                    </div>
                </section>` : ''}

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div><h3>Staff departments</h3></div>
                        <span class="fm-panel-action">Directory</span>
                    </div>
                    <section class="fm-panel fm-player-tabs-panel" style="margin-top:0;">
                        <div class="fm-player-tabs">
                            ${departments.map((d, i) => `<button type="button" class="fm-player-tab ${i === 0 ? 'is-active' : ''}" data-staff-tab="${escapeHtml(d)}">${escapeHtml(DEPARTMENT_LABEL[d] || d)}</button>`).join('')}
                        </div>
                    </section>
                    ${departments.map((d, i) => `
                        <div class="fm-staff-panel" data-staff-panel="${escapeHtml(d)}" style="display:${i === 0 ? 'block' : 'none'};">
                            <div class="fm-squad-wrap">
                                <table class="fm-squad fm-staff-table">
                                    <thead><tr><th class="sq-name">Name</th><th>Role</th><th>Age</th><th>Strengths</th><th>Contract</th><th>Wage</th></tr></thead>
                                    <tbody>${tableRows(members.filter(m => m.department === d))}</tbody>
                                </table>
                            </div>
                        </div>`).join('')}
                </section>
            </div>`;

        mainContent.querySelectorAll('[data-staff-tab]').forEach(tab => tab.addEventListener('click', () => {
            const target = tab.dataset.staffTab;
            mainContent.querySelectorAll('[data-staff-tab]').forEach(b => b.classList.toggle('is-active', b === tab));
            mainContent.querySelectorAll('[data-staff-panel]').forEach(panel => { panel.style.display = panel.dataset.staffPanel === target ? 'block' : 'none'; });
        }));
        mainContent.querySelectorAll('[data-open-staff]').forEach(row => row.addEventListener('click', () => loadStaffMember(row.dataset.openStaff)));
    }

    async function loadStaffMember(memberId) {
        const member = cache.get(String(memberId));
        if (!member) return loadStaff();
        const mainContent = document.getElementById('main-content');
        const tabs = ['Overview', 'Contract', 'Attributes'];
        const sectionHtml = sectionsFor(member).map(([title, items]) => `
            <div class="fm-skill-col">
                <h4>${escapeHtml(title)}</h4>
                <table class="fm-skills"><tbody>${items.map(([label, value]) => `<tr><td>${escapeHtml(label)}</td><td>${escapeHtml(String(value ?? '—'))}</td></tr>`).join('')}</tbody></table>
            </div>`).join('');

        mainContent.innerHTML = `
            <div class="fm-page fm-player-page">
                <section class="fm-panel fm-player-hero">
                    <button id="staff-back-button" class="back-to-dashboard">Back</button>
                    <div class="fm-player-hero-main">
                        <div class="fm-player-cardline">
                            <div class="fm-player-avatar" style="width:108px;height:108px;flex:0 0 108px;"><img src="/images/player.jpg" alt="${escapeHtml(member.name || '')}" style="width:100%;height:100%;object-fit:cover;"></div>
                            <div>
                                <div class="fm-eyebrow">Staff profile</div>
                                <h2>${escapeHtml(member.name || '—')}</h2>
                                <div class="fm-player-submeta">${escapeHtml(member.slot)} · ${escapeHtml(DEPARTMENT_LABEL[member.department] || member.department)}</div>
                                <div class="fm-player-badges">
                                    <span class="fm-player-chip">Overall ${member.overall ?? '—'}</span>
                                    <span class="fm-player-chip secondary">${member.age ?? '—'} years old</span>
                                </div>
                            </div>
                        </div>
                        <div class="fm-player-summary-strip">
                            <div><strong>${escapeHtml(member.slot)}</strong><span>Role</span></div>
                            <div><strong>${escapeHtml(member.contractEnd)}</strong><span>Contract</span></div>
                            <div><strong>${escapeHtml(member.wage)}</strong><span>Wage</span></div>
                            <div><strong>${escapeHtml(member.focus)}</strong><span>Strengths</span></div>
                        </div>
                    </div>
                </section>
                <section class="fm-panel fm-player-tabs-panel"><div class="fm-player-tabs">${tabs.map((tab, idx) => `<button type="button" class="fm-player-tab ${idx === 0 ? 'is-active' : ''}" data-staff-profile-tab="${tab.toLowerCase()}">${tab}</button>`).join('')}</div></section>
                <div class="fm-player-grid">
                    <div class="fm-player-grid-left">
                        <section class="fm-panel fm-player-tab-panel is-active" data-staff-profile-panel="overview">
                            <div class="fm-panel-head"><h3>Role summary</h3></div>
                            <div class="club-profile-detail-list">
                                <div class="club-profile-detail-row"><span>Role</span><strong>${escapeHtml(member.slot)}</strong></div>
                                <div class="club-profile-detail-row"><span>Department</span><strong>${escapeHtml(DEPARTMENT_LABEL[member.department] || member.department)}</strong></div>
                                <div class="club-profile-detail-row"><span>Age</span><strong>${member.age ?? '—'}</strong></div>
                                <div class="club-profile-detail-row"><span>Overall</span><strong>${member.overall ?? '—'}</strong></div>
                            </div>
                        </section>
                        <section class="fm-panel fm-player-tab-panel" data-staff-profile-panel="contract">
                            <div class="fm-panel-head"><h3>Contract</h3></div>
                            <div class="club-profile-detail-list">
                                <div class="club-profile-detail-row"><span>Wage</span><strong>${escapeHtml(member.wage)}</strong></div>
                                <div class="club-profile-detail-row"><span>Ends</span><strong>${escapeHtml(member.contractEnd)}</strong></div>
                                <div class="club-profile-detail-row"><span>Season number</span><strong>${escapeHtml(String(member.contractEndSeason ?? '—'))}</strong></div>
                            </div>
                        </section>
                        <section class="fm-panel fm-player-tab-panel" data-staff-profile-panel="attributes">
                            <div class="fm-panel-head"><h3>All attributes</h3></div>
                            <div class="club-profile-detail-list">
                                ${[['Development', member.development], ['Tactical', member.tactical],
                                   ['Motivation', member.motivation], ['Goalkeeping', member.goalkeeping],
                                   ['Fitness', member.fitness], ['Scouting', member.scouting]]
                                  .map(([label, value]) => `<div class="club-profile-detail-row"><span>${label}</span><strong>${escapeHtml(String(value ?? '—'))}</strong></div>`).join('')}
                            </div>
                        </section>
                    </div>
                    <div class="fm-player-grid-right">
                        <section class="fm-panel fm-player-tab-panel is-active" data-staff-profile-panel="overview">
                            <div class="fm-panel-head"><h3>Attributes</h3><span class="fm-panel-action">1-20</span></div>
                            <div class="fm-skills-grid">${sectionHtml}</div>
                        </section>
                    </div>
                </div>
            </div>`;

        const backButton = document.getElementById('staff-back-button');
        if (backButton) backButton.addEventListener('click', () => loadStaff());
        mainContent.querySelectorAll('[data-staff-profile-tab]').forEach(tab => tab.addEventListener('click', () => {
            const target = tab.dataset.staffProfileTab;
            mainContent.querySelectorAll('[data-staff-profile-tab]').forEach(b => b.classList.toggle('is-active', b === tab));
            mainContent.querySelectorAll('[data-staff-profile-panel]').forEach(panel => panel.classList.toggle('is-active', panel.dataset.staffProfilePanel === target));
        }));
    }

    return { loadStaff, loadStaffMember };
}
