    // /js/demo/demoApp.js
    import { initCanvas } from './demo/canvasRenderer.js';
    import { initSockets } from './demo/sockets.js';
    import { initEventProcessor } from './demo/eventProcessor.js';
    import { initScoreboard } from './demo/scoreboard.js';
    import { cleanupDemo } from './demo/demoCleanup.js';
    import { authFetch } from './auth.js';

    let currentUserTeamId = null;

    window.addEventListener('load', async () => {
        const token = localStorage.getItem('token');
        if (!token) {
            console.warn("No token on load - redirecting");
            window.location.href = '/login.html';
            return;
        }

        try {
            const res = await authFetch('/auth/me');
            const user = await res.json();

            currentUserTeamId = user.teamId;
            console.log("Ulogovan korisnik:", user.username, "Team ID:", currentUserTeamId);

        } catch (err) {
            console.error("Greška pri učitavanju /auth/me:", err);
            localStorage.removeItem('token');
            window.location.href = '/login.html';
        }
    });

    document.addEventListener("DOMContentLoaded", ()=>{
        initCanvas();         // ovo POKREĆE loop
        initScoreboard();
        initEventProcessor();
        initSockets();        // ovo otvara WS-ove
    });

    window.cleanupAndGoBack = function() {
        cleanupDemo();
        window.location.href = '/dashboard.html';
    };

    // Dodatna sigurnost: cleanup na zatvaranje taba/refresh
    window.addEventListener('beforeunload', cleanupDemo);