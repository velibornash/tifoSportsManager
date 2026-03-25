// sockets.js

import { registerSockets } from './demoCleanup.js';
import { updatePositionsData, setCurrentMinute } from './canvasRenderer.js';
import { enqueueEvent } from './eventProcessor.js';
import { updateMinute } from './scoreboard.js';
import { getQueryParam } from './utils.js';

export function initSockets() {
    const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const base = `${wsProtocol}://${location.host}`;
    const matchId = getQueryParam('matchId');

    if (!matchId) {
        console.error('Missing matchId query parameter.');
        return;
    }

    const token = localStorage.getItem('token');

    const positionSocket = new WebSocket(
        `${base}/demo-position-updates?matchId=${matchId}&token=${token}`
    );
    const eventSocket = new WebSocket(
        `${base}/demo-match-events?matchId=${matchId}&token=${token}`
    );

    positionSocket.onopen = () => console.log('Position socket connected');
    positionSocket.onmessage = e => {
        try {
            const data = JSON.parse(e.data);
            const minute = Number.isFinite(data.second) ? data.second : 0;
            if (minute > 0) {
                setCurrentMinute(minute);
                updateMinute(minute);
            }
            updatePositionsData(data);
        } catch (err) {
            console.error('Position parse error:', err, e.data);
        }
    };
    positionSocket.onclose = () => console.log('Position socket closed');
    positionSocket.onerror = err => console.error('Position socket error:', err);

    eventSocket.onopen = () => {
        console.log('Event socket connected');
        const lastEventBox = document.getElementById('lastEventBox');
        if (lastEventBox) lastEventBox.textContent = 'Connected - waiting for events...';
    };
    eventSocket.onmessage = e => {
        try {
            const ev = JSON.parse(e.data);
            enqueueEvent(ev);
        } catch (err) {
            console.error('Event parse error:', err, e.data);
        }
    };
    eventSocket.onclose = () => console.log('Event socket closed');
    eventSocket.onerror = err => console.error('Event socket error:', err);

    registerSockets(positionSocket, eventSocket);
}

window.initSockets = initSockets;
