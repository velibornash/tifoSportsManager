// login.js
import { clearLastAppError, readLastAppError, setAuthToken } from './auth.js';

function renderLastError() {
    const statusHost = document.getElementById('loginStatus');
    if (!statusHost) return;

    const entry = readLastAppError();
    if (!entry) {
        statusHost.hidden = true;
        statusHost.textContent = '';
        return;
    }

    const parts = [];
    const when = entry.timestamp ? new Date(entry.timestamp).toLocaleString() : null;
    parts.push(entry.reason || 'Last app error');
    if (entry.scope) parts.push(`Scope: ${entry.scope}`);
    if (entry.path) parts.push(`Path: ${entry.path}`);
    if (when) parts.push(`Time: ${when}`);

    const details = entry.details && typeof entry.details === 'object' ? entry.details : null;
    if (details?.status != null) parts.push(`Status: ${details.status}`);
    if (details?.code) parts.push(`Code: ${details.code}`);
    if (details?.message) parts.push(`Message: ${details.message}`);
    if (details?.url) parts.push(`URL: ${details.url}`);
    if (details?.responseText) {
        parts.push(`Response: ${String(details.responseText).slice(0, 240)}`);
    }

    statusHost.textContent = parts.join('\n');
    statusHost.hidden = false;
    statusHost.style.whiteSpace = 'pre-wrap';
}

document.getElementById('loginForm').addEventListener('submit', async function(e) {
    e.preventDefault();

    const email = document.querySelector('input[name="email"]').value;
    const password = document.querySelector('input[name="password"]').value;

    try {
        const response = await fetch('/auth/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username: email, password })
        });

        if (!response.ok) {
            const error = await response.text();
            alert("Greška pri prijavljivanju: " + error);
            return;
        }

        const data = await response.json();
        clearLastAppError();
        setAuthToken(data.token);
        console.log('[login.js] Token stored in sessionStorage. Preview:', data.token.substring(0, 20) + '...');
        console.log('[login.js] sessionStorage token:', sessionStorage.getItem('token') ? 'PRESENT' : 'MISSING');
        console.log('[login.js] Navigating to /home.html');
        window.location.href = '/home.html';

    } catch (err) {
        alert("Greška pri konekciji: " + err.message);
    }
});

renderLastError();
