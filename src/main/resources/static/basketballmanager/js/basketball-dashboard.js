// basketball-dashboard.js
// Main entry point for basketball manager dashboard

async function bballStart() {
    await bbInit();
    bbRenderDashboard();

    bballUpdateClock();
    setInterval(bballUpdateClock, 1000);

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

function bballUpdateClock() {
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

document.addEventListener('DOMContentLoaded', bballStart);

window.bballStart = bballStart;
window.bballUpdateClock = bballUpdateClock;
