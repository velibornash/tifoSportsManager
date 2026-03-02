// /js/demo.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;

window.addEventListener('load', async () => {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn("No token on load - redirecting");
        //window.location.href = '/login.html';
        return;
    }

    try {
        const res = await authFetch('/auth/me');
        const user = await res.json();

        currentUserTeamId = user.teamId;
        console.log("Ulogovan korisnik:", user.username, "Team ID:", currentUserTeamId);

    } catch (err) {
        console.error("Greška pri učitavanju /auth/me:", err);
        //localStorage.removeItem('token');
        //window.location.href = '/login.html';
    }
});
async function startDemoTest() {
    const button = document.querySelector('.dashboard-actions button');
    button.disabled = true;
    button.textContent = "Pokrećem demo test...";
    try {
        const response = await authFetch('/start-demo');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;
        window.location.href = `/demo.html?matchId=${matchId}`;

    } catch (error) {
        alert("Greška pri pokretanju demo testa.");
        button.disabled = false;
        button.textContent = "Start Demo Test";
    }
}

window.startDemoTest = startDemoTest;