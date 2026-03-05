// /js/demo.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;

window.addEventListener('load', async () => {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn('No token on load - redirecting');
        return;
    }

    try {
        const res = await authFetch('/auth/me');
        const user = await res.json();
        currentUserTeamId = user.teamId;
        console.log('Logged in user:', user.username, 'Team ID:', currentUserTeamId);
    } catch (err) {
        console.error('Failed to load /auth/me:', err);
    }
});

async function startDemoTest() {
    const button = document.querySelector('.dashboard-actions button');
    if (!button) {
        console.error('Start demo button not found');
        return;
    }

    button.disabled = true;
    button.textContent = 'Starting demo simulation...';

    try {
        const response = await authFetch('/start-demo');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;
        if (!matchId) throw new Error('Missing matchId in response');

        window.location.href = `/demo.html?matchId=${matchId}`;
    } catch (error) {
        console.error('Failed to start demo:', error);
        alert('Failed to start demo simulation.');
        button.disabled = false;
        button.textContent = 'Start Demo Simulation';
    }
}

window.startDemoTest = startDemoTest;
