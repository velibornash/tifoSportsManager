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
    const button = document.getElementById('start-demo-btn');
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
        button.textContent = 'Simulate Next Round';
    }
}

async function startKeyEventsTest() {
    const button = document.getElementById('start-key-events-btn');
    if (!button) {
        console.error('Key events button not found');
        return;
    }

    button.disabled = true;
    button.textContent = 'Preparing key events...';

    try {
        const response = await authFetch('/start-demo-key-events');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;
        if (!matchId) throw new Error('Missing matchId in response');

        window.location.href = `/key-events.html?matchId=${matchId}`;
    } catch (error) {
        console.error('Failed to start key events simulation:', error);
        alert('Failed to start key events simulation.');
        button.disabled = false;
        button.textContent = 'Simulate Key Events';
    }
}

async function startRealisticDemoTest() {
    const button = document.getElementById('start-realistic-demo-btn');
    if (!button) {
        console.error('Realistic demo button not found');
        return;
    }

    button.disabled = true;
    button.textContent = 'Starting realistic simulation...';

    try {
        const response = await authFetch('/start-realistic-demo');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;
        if (!matchId) throw new Error('Missing matchId in response');

        window.location.href = `/realisticDemo.html?matchId=${matchId}&mode=live`;
    } catch (error) {
        console.error('Failed to start realistic demo:', error);
        alert('Failed to start realistic match simulation.');
        button.disabled = false;
        button.textContent = '⚽ Realistic Match';
    }
}

window.startDemoTest = startDemoTest;
window.startKeyEventsTest = startKeyEventsTest;
window.startRealisticDemoTest = startRealisticDemoTest;
