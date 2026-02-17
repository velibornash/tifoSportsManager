function loadDashboard() {

    document.getElementById("main-content").innerHTML = `
        <div class="team-card">

            <div class="team-header">
                <img src="/images/omladinac.png" class="team-logo">
                <div class="team-name-wrapper">
                    <h1>OFK Omladinac</h1>
                    <p class="team-subtitle">Serbian League Division 2 • Season 2025/26</p>
                </div>
            </div>

            <div class="stats-grid">
                <div class="stat-item">
                    <div class="stat-value">1</div>
                    <div class="stat-label">Position</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">34</div>
                    <div class="stat-label">Points</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">10-4-5</div>
                    <div class="stat-label">W-D-L</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">+12</div>
                    <div class="stat-label">Goal Diff</div>
                </div>
            </div>

            <div class="next-match">
                <h3>Next Match</h3>

                <div class="match-info">
                    <div class="team-away-home">
                        <img src="/images/sremac.jpg" class="match-team-logo small">
                        <span>Sremac Berkasovo</span>
                    </div>

                    <span class="vs">VS</span>

                    <div class="team-away-home">
                        <img src="/images/omladinac.png" class="match-team-logo small">
                        <span>OFK Omladinac</span>
                    </div>
                </div>

                <div class="match-date">
                    15.03.2026 • 17:00 • Stadion Livadice
                </div>
            </div>

            <div class="quick-stats">
                <div>Form: <span class="form-good">W W D L W</span></div>
                <div>Top Scorer: LJ. Ozegovic — 11 goals</div>
            </div>

            <div class="dashboard-actions">
                <button onclick="startDemoTest()">Start Demo Test</button>
            </div>

        </div>
    `;
}
// Desktop toggle: zatvara sve ostale sidebarove
window.toggleSidebar = function(id) {
    const sidebars = document.querySelectorAll('.sidebar');
    sidebars.forEach(sb => {
        if(sb.id === id) {
            sb.classList.toggle('active');
        } else {
            sb.classList.remove('active');
        }
    });
};

// Mobile toggle
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');

    sidebar.classList.toggle('active');
    overlay.classList.toggle('active');
}

function closeMobileMenu() {
    document.getElementById('mobileSidebar').classList.remove('active');
    document.getElementById('mobileOverlay').classList.remove('active');
}

