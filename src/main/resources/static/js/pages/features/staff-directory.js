export function createStaffDirectoryFeature(deps) {
    const { authFetch, getTeamId, escapeHtml, buildClubActionsHtml } = deps;
    const cache = new Map();
    const coachSlots = ['Main Coach', 'OFF Coach', 'DEF Coach', 'Junior Coach'];
    const scoutSlots = ['Chief Scout', 'Scout'];
    const medicalSlots = ['Doctor', 'Specialist', 'Physio'];

    const bounded = (value, min = 1, max = 20) => Math.max(min, Math.min(max, Math.round(value)));
    const metric = (base, delta) => bounded(base + delta, 6, 20);

    function buildCoachMember(source, slot, idx) {
        const base = bounded((Number(source?.rating || (7.1 + idx * 0.2)) * 2.35), 10, 19);
        return {
            id: `coach-${idx + 1}`,
            department: 'coaching',
            slot,
            name: source?.name || ['Milan Stojic', 'Nemanja Vasic', 'Luka Ristic', 'Stefan Ilic'][idx],
            age: source?.age || 39 + idx * 3,
            nation: source?.nation || 'SRB',
            contractEnd: `Jun 202${7 + idx}`,
            wage: `${650 + idx * 90} € / week`,
            focus: idx === 0 ? 'Leadership & match prep' : idx === 1 ? 'Attacking patterns' : idx === 2 ? 'Defensive line' : 'Academy pathway',
            rating: (base / 2.5).toFixed(1),
            overview: idx === 0 ? 'Leads the full coaching staff and first-team preparation.' : `${slot} focused on daily development and specialist drills.`,
            sections: [
                ['Ball Skills', [['Technique', metric(base, 1)], ['Passing', metric(base, 0)], ['Shooting', metric(base, idx === 1 ? 2 : -1)]]],
                ['Physical & Tactical', [['Pace', metric(base, -2)], ['Defending', metric(base, idx === 2 ? 2 : 0)], ['Goalkeeper', metric(base, idx === 3 ? 1 : -3)]]],
                ['Mental', [['Leadership', metric(base, 3)], ['Motivation', metric(base, 2)], ['Analysis', metric(base, 1)]]]
            ]
        };
    }

    function buildSupportMember(slot, idx, department) {
        const presets = {
            scouting: [
                ['Marko Vidic', 'Regional network & reports', 'Sep 2028', '710 € / week', [['Recruitment', 17], ['Potential Judgement', 16], ['Data Analysis', 15]], [['Networking', 17], ['Negotiation', 13], ['Discipline', 14]]],
                ['Djordje Kostic', 'Live scouting & opposition notes', 'Jun 2027', '520 € / week', [['Recruitment', 15], ['Potential Judgement', 14], ['Data Analysis', 13]], [['Networking', 15], ['Negotiation', 12], ['Discipline', 13]]]
            ],
            medical: [
                ['Dr. Nikola Savic', 'First diagnosis & recovery planning', 'Jun 2029', '890 € / week', [['Diagnosis', 18], ['Recovery', 17], ['Communication', 15]], [['Prevention', 16], ['Workload Control', 15], ['Pressure Handling', 14]]],
                ['Ana Jovanovic', 'Special treatments & return-to-play', 'Jun 2028', '760 € / week', [['Diagnosis', 16], ['Recovery', 18], ['Communication', 16]], [['Prevention', 15], ['Workload Control', 16], ['Pressure Handling', 15]]],
                ['Milos Petrov', 'Gym floor & daily physio work', 'Jun 2027', '610 € / week', [['Diagnosis', 14], ['Recovery', 16], ['Communication', 15]], [['Prevention', 17], ['Workload Control', 16], ['Pressure Handling', 13]]]
            ]
        };
        const [name, focus, contractEnd, wage, left, right] = presets[department][idx];
        return {
            id: `${department}-${idx + 1}`,
            department,
            slot,
            name,
            age: department === 'scouting' ? 42 + idx * 4 : 36 + idx * 3,
            nation: 'SRB',
            contractEnd,
            wage,
            focus,
            rating: department === 'scouting' ? (7.0 + idx * 0.2).toFixed(1) : (7.5 + idx * 0.1).toFixed(1),
            overview: `${slot} handles ${focus.toLowerCase()} for the club.`,
            sections: [[department === 'scouting' ? 'Scouting Tools' : 'Medical Tools', left], ['Professional Profile', right]]
        };
    }

    function buildStaffDirectory(coaches) {
        const ordered = [...(Array.isArray(coaches) ? coaches : [])].sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0));
        return [
            ...coachSlots.map((slot, idx) => buildCoachMember(ordered[idx], slot, idx)),
            ...scoutSlots.map((slot, idx) => buildSupportMember(slot, idx, 'scouting')),
            ...medicalSlots.map((slot, idx) => buildSupportMember(slot, idx, 'medical'))
        ];
    }

    const tableRows = (members) => members.map(member => `
        <tr class="fm-squad-row cs-clickable" data-open-staff="${member.id}">
            <td class="sq-name">${escapeHtml(member.name)}</td><td>${escapeHtml(member.slot)}</td><td>${member.age}</td>
            <td>${escapeHtml(member.focus)}</td><td>${escapeHtml(member.contractEnd)}</td><td>${escapeHtml(member.rating)}</td>
        </tr>`).join('');

    async function loadStaff() {
        const teamId = getTeamId();
        const [staffRes, profileRes] = await Promise.all([authFetch(`/demo/teams/${teamId}/coaches`), authFetch(`/demo/teams/${teamId}/profile`)]);
        const members = buildStaffDirectory(staffRes.ok ? await staffRes.json() : []);
        const profile = profileRes.ok ? await profileRes.json() : {};
        const mainContent = document.getElementById('main-content');
        cache.clear();
        members.forEach(member => cache.set(member.id, member));

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main"><div><div class="fm-eyebrow">Club staff</div><h2>${escapeHtml(profile.name || 'Staff')}</h2><p class="fm-subtle">Staff structure now follows the same Club shell, with fixed department slots and click-through profile pages.</p></div>${buildClubActionsHtml('staff')}</div>
                    <div class="fm-medical-stat-grid team-summary-grid"><div><strong>${members.length}</strong><span>Staff members</span></div><div><strong>3</strong><span>Departments</span></div><div><strong>9</strong><span>Filled slots</span></div><div><strong>Velja</strong><span>Owner</span></div></div>
                </section>
                <section class="fm-panel"><div class="fm-panel-head"><div><h3>Staff departments</h3><p class="fm-subtle">Open-football style groups below, while each member opens a detailed profile view.</p></div><span class="fm-panel-action">Directory</span></div>
                    <section class="fm-panel fm-player-tabs-panel" style="margin-top:0;"><div class="fm-player-tabs"><button type="button" class="fm-player-tab is-active" data-staff-tab="coaching">Coaching</button><button type="button" class="fm-player-tab" data-staff-tab="scouting">Scouting</button><button type="button" class="fm-player-tab" data-staff-tab="medical">Medical</button></div></section>
                    ${['coaching', 'scouting', 'medical'].map((dept, idx) => `<div class="fm-staff-panel" data-staff-panel="${dept}" style="display:${idx === 0 ? 'block' : 'none'};"><div class="fm-squad-wrap"><table class="fm-squad fm-staff-table"><thead><tr><th class="sq-name">Name</th><th>Slot</th><th>Age</th><th>Focus</th><th>Contract</th><th>Rating</th></tr></thead><tbody>${tableRows(members.filter(member => member.department === dept))}</tbody></table></div></div>`).join('')}
                </section>
            </div>`;

        mainContent.querySelectorAll('[data-staff-tab]').forEach(tab => tab.addEventListener('click', () => {
            const target = tab.dataset.staffTab;
            mainContent.querySelectorAll('[data-staff-tab]').forEach(button => button.classList.toggle('is-active', button === tab));
            mainContent.querySelectorAll('[data-staff-panel]').forEach(panel => { panel.style.display = panel.dataset.staffPanel === target ? 'block' : 'none'; });
        }));
        mainContent.querySelectorAll('[data-open-staff]').forEach(row => row.addEventListener('click', () => loadStaffMember(row.dataset.openStaff)));
    }

    async function loadStaffMember(memberId) {
        const member = cache.get(String(memberId));
        if (!member) return loadStaff();
        const mainContent = document.getElementById('main-content');
        const tabs = ['Overview', 'Contract', 'History'];
        const sectionHtml = member.sections.map(([title, items]) => `<div class="fm-skill-col"><h4>${escapeHtml(title)}</h4><table class="fm-skills"><tbody>${items.map(([label, value]) => `<tr><td>${escapeHtml(label)}</td><td>${escapeHtml(value)}</td></tr>`).join('')}</tbody></table></div>`).join('');

        mainContent.innerHTML = `
            <div class="fm-page fm-player-page">
                <section class="fm-panel fm-player-hero"><button id="staff-back-button" class="back-to-dashboard">Back</button><div class="fm-player-hero-main"><div class="fm-player-cardline"><div class="fm-player-avatar" style="width:108px;height:108px;flex:0 0 108px;"><img src="/images/player.jpg" alt="${escapeHtml(member.name)}" style="width:100%;height:100%;object-fit:cover;"></div><div><div class="fm-eyebrow">Staff profile</div><h2>${escapeHtml(member.name)}</h2><div class="fm-player-submeta">${escapeHtml(member.slot)} · ${escapeHtml(member.department)}</div><div class="fm-player-badges"><span class="fm-player-chip">${escapeHtml(member.rating)} rating</span><span class="fm-player-chip secondary">${member.age} years</span><span class="fm-player-chip secondary">${escapeHtml(member.nation)}</span></div></div></div><div class="fm-player-summary-strip"><div><strong>${escapeHtml(member.slot)}</strong><span>Role</span></div><div><strong>${escapeHtml(member.focus)}</strong><span>Focus</span></div><div><strong>${escapeHtml(member.contractEnd)}</strong><span>Contract</span></div><div><strong>${escapeHtml(member.wage)}</strong><span>Wage</span></div></div></div></section>
                <section class="fm-panel fm-player-tabs-panel"><div class="fm-player-tabs">${tabs.map((tab, idx) => `<button type="button" class="fm-player-tab ${idx === 0 ? 'is-active' : ''}" data-staff-profile-tab="${tab.toLowerCase()}">${tab}</button>`).join('')}</div></section>
                <div class="fm-player-grid"><div class="fm-player-grid-left"><section class="fm-panel fm-player-tab-panel is-active" data-staff-profile-panel="overview"><div class="fm-panel-head"><h3>Role summary</h3></div><div class="club-profile-detail-list"><div class="club-profile-detail-row"><span>Department</span><strong>${escapeHtml(member.department)}</strong></div><div class="club-profile-detail-row"><span>Slot</span><strong>${escapeHtml(member.slot)}</strong></div><div class="club-profile-detail-row"><span>Nationality</span><strong>${escapeHtml(member.nation)}</strong></div><div class="club-profile-detail-row"><span>Overview</span><strong>${escapeHtml(member.overview)}</strong></div></div></section><section class="fm-panel fm-player-tab-panel" data-staff-profile-panel="contract"><div class="fm-panel-head"><h3>Contract</h3></div><div class="club-profile-detail-list"><div class="club-profile-detail-row"><span>Wage</span><strong>${escapeHtml(member.wage)}</strong></div><div class="club-profile-detail-row"><span>Ends</span><strong>${escapeHtml(member.contractEnd)}</strong></div><div class="club-profile-detail-row"><span>Status</span><strong>First-team staff</strong></div></div></section><section class="fm-panel fm-player-tab-panel" data-staff-profile-panel="history"><div class="fm-panel-head"><h3>History</h3></div><div class="fm-empty">Staff history timeline UI is ready for later backend expansion.</div></section></div><div class="fm-player-grid-right"><section class="fm-panel fm-player-tab-panel is-active" data-staff-profile-panel="overview"><div class="fm-panel-head"><h3>Attributes</h3><span class="fm-panel-action">${escapeHtml(member.department)}</span></div><div class="fm-skills-grid">${sectionHtml}</div></section></div></div>
            </div>`;

        const backButton = document.getElementById('staff-back-button');
        if (backButton) backButton.addEventListener('click', () => loadStaff());
        mainContent.querySelectorAll('[data-staff-profile-tab]').forEach(tab => tab.addEventListener('click', () => {
            const target = tab.dataset.staffProfileTab;
            mainContent.querySelectorAll('[data-staff-profile-tab]').forEach(button => button.classList.toggle('is-active', button === tab));
            mainContent.querySelectorAll('[data-staff-profile-panel]').forEach(panel => panel.classList.toggle('is-active', panel.dataset.staffProfilePanel === target));
        }));
    }

    return { loadStaff, loadStaffMember };
}