// pages/views/club-view.js
import { htmlEscape, formatBudget, buildMilestoneBoardHtml } from './utils.js';

export function createClubView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml } = deps;

    async function loadClubProfile() {
        const teamId = getTeamId();
        console.log(`Loading club profile for ${teamId}`);
        const [response, milestones] = await Promise.all([
            authFetch(`/demo/teams/${teamId}/profile`),
            (async () => {
                try {
                    const milestoneResponse = await authFetch(`/teams/${teamId}/milestones`);
                    return milestoneResponse.ok ? await milestoneResponse.json() : null;
                } catch {
                    return null;
                }
            })()
        ]);
        console.log(`Response status: ${response.status}`);
        const profile = await response.json();

        const mainContent = document.getElementById("main-content");

        const stadiumImage = "/images/dunjareal.png";

        mainContent.innerHTML = `
        <div class="fm-page fm-page--club">
            <section class="fm-panel fm-club-hero">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Club overview</div>
                        <h2>${htmlEscape(profile.name || 'Club Profile')}</h2>
                        <p class="fm-subtle">Same club shell as First Team, with profile data, stadium access, budget, and reputation.</p>
                    </div>
                    ${buildClubActionsHtml('profile')}
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${htmlEscape(profile.founded || 'N/A')}</strong><span>Founded</span></div>
                    <div><strong>${htmlEscape(profile.stadium || 'N/A')}</strong><span>Home stadium</span></div>
                    <div><strong>${htmlEscape(profile.reputation || 'N/A')}</strong><span>Reputation</span></div>
                    <div><strong>${htmlEscape(formatBudget(profile.budget))}</strong><span>Budget</span></div>
                </div>
            </section>
            <div class="fm-grid-top fm-grid-top--club-profile">
                <section class="fm-panel club-profile-brand-card">
                    <div class="club-profile-brand-mark">
                        <img src="${profile.logo || '/images/logoside.jpg'}"
                             class="club-logo"
                             alt="${htmlEscape(profile.name)}"
                             onerror="this.src='/images/logoside.jpg'">
                    </div>
                    <h3>${htmlEscape(profile.name || 'Club')}</h3>
                    <p class="fm-subtle">Serbian club profile with open-football-inspired presentation and our existing app data.</p>
                    <button type="button" class="fm-action-btn secondary club-profile-stadium-btn" data-stadium-image="${htmlEscape(stadiumImage)}" data-stadium-name="${htmlEscape(profile.stadium || 'Stadium')}">Open Stadium View</button>
                </section>
                <section class="fm-panel club-profile-detail-card">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Club details</h3>
                            <p class="fm-subtle">Profile data stays concise, wide, and visually aligned with the rest of the club area.</p>
                        </div>
                        <span class="fm-panel-action">Profile</span>
                    </div>
                    <div class="club-profile-detail-list">
                        <div class="club-profile-detail-row"><span>Founded</span><strong>${htmlEscape(profile.founded || 'N/A')}</strong></div>
                        <div class="club-profile-detail-row"><span>Stadium</span><strong>${htmlEscape(profile.stadium || 'N/A')}</strong></div>
                        <div class="club-profile-detail-row"><span>Budget</span><strong>${htmlEscape(formatBudget(profile.budget))}</strong></div>
                        <div class="club-profile-detail-row"><span>Reputation</span><strong>${htmlEscape(profile.reputation || 'N/A')}</strong></div>
                    </div>
                </section>
            </div>
            <section class="fm-panel fm-milestone-board-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Club milestones</h3>
                        <p class="fm-subtle">Current season snapshot for your club, kept alongside the league-wide milestone boards.</p>
                    </div>
                    <span class="fm-panel-action">Team board</span>
                </div>
                ${buildMilestoneBoardHtml(milestones)}
            </section>
        </div>`;

        const stadiumButton = mainContent.querySelector('.club-profile-stadium-btn');
        if (stadiumButton) {
            stadiumButton.addEventListener('click', () => {
                showStadiumModal(stadiumButton.dataset.stadiumImage, stadiumButton.dataset.stadiumName);
            });
        }
    }

    function openStadiumImage(imageUrl) {
        window.open(imageUrl, '_blank');
    }

    function showStadiumModal(imageUrl, stadiumName) {
        const modal = document.createElement('div');
        modal.style.position = 'fixed';
        modal.style.inset = '0';
        modal.style.background = 'rgba(0,0,0,0.85)';
        modal.style.display = 'flex';
        modal.style.alignItems = 'center';
        modal.style.justifyContent = 'center';
        modal.style.zIndex = '9999';
        modal.innerHTML = `
            <div style="position: relative; max-width: 90vw; max-height: 90vh;">
                <button onclick="this.parentElement.parentElement.remove()"
                        style="position: absolute; top: -40px; right: 0; background: #f44336; color: white; border: none; border-radius: 50%; width: 36px; height: 36px; font-size: 1.4em; cursor: pointer;">
                    &times;
                </button>
                <img src="${imageUrl}" alt="${stadiumName}" style="max-width: 100%; max-height: 85vh; border-radius: 12px; box-shadow: 0 10px 40px rgba(0,0,0,0.7);">
                <p style="color: white; text-align: center; margin-top: 12px; font-size: 1.2em;">
                    ${stadiumName}
                </p>
            </div>
        `;
        modal.onclick = (e) => {
            if (e.target === modal) modal.remove();
        };
        document.body.appendChild(modal);
    }

    return { loadClubProfile, showStadiumModal, openStadiumImage };
}
