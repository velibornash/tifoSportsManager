// af-dashboard.js
// Main entry point for American Football manager dashboard

async function afStart() {
    await afInit();
    afRenderDashboard();

    afUpdateClock();
    setInterval(afUpdateClock, 1000);

    document.querySelectorAll('.sidebar-content > div:not(.accordion)').forEach(el => {
        el.addEventListener('click', function(e) {
            const onclick = this.getAttribute('onclick');
            if (onclick && onclick.includes('loadPage')) {
                const match = onclick.match(/loadPage\('([^']+)'\)/);
                if (match && window.innerWidth > 768) {
                    document.getElementById('clubSidebar')?.classList.remove('active');
                }
            }
        });
    });
}

function afUpdateClock() {
    const now = new Date();
    const timeStr = now.toLocaleTimeString('sr-RS', { hour: '2-digit', minute: '2-digit' });
    const dateStr = now.toLocaleDateString('sr-RS', { day: 'numeric', month: 'short', year: 'numeric' });

    ['clock-time', 'clock-time-m'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.textContent = timeStr;
    });
    ['clock-date', 'clock-date-m'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.textContent = dateStr;
    });
}

document.addEventListener('DOMContentLoaded', afStart);

window.afStart = afStart;
window.afUpdateClock = afUpdateClock;
