// sockets.js
import { updatePositionsData } from './canvasRenderer.js';
import { enqueueEvent } from './eventProcessor.js';

function getQueryParam(name){
    const params = new URLSearchParams(window.location.search);
    return params.get(name);
}

export function initSockets(){
    const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const base = `${wsProtocol}://${location.host}`;
    const matchId = getQueryParam('matchId');

    // ────────── Pozicije ──────────
    const positionSocket = new WebSocket(`${base}/demo-position-updates?matchId=${matchId}`);
    positionSocket.onopen = () => console.log('✅ Position socket connected');
    positionSocket.onmessage = e => {
        try {
            const data = JSON.parse(e.data);
            updatePositionsData(data);
        } catch(err){ console.error('Position parse error:', err, e.data); }
    };
    positionSocket.onclose = () => console.warn('Position socket closed');
    positionSocket.onerror = err => console.error('Position socket error:', err);

    // ────────── Eventi ──────────
    const eventSocket = new WebSocket(`${base}/demo-match-events?matchId=${matchId}`);
    eventSocket.onopen = () => {
        console.log('✅ Event socket connected');
        const lastEventBox = document.getElementById('lastEventBox');
        if(lastEventBox) lastEventBox.textContent = '🟢 Povezano – čekamo događaje...';
    };

    eventSocket.onmessage = e => {
        try {
            const ev = JSON.parse(e.data);
            enqueueEvent(ev);
        } catch(err){ console.error('Event parse error:', err, e.data); }
    };

    eventSocket.onclose = () => console.warn('Event socket closed');
    eventSocket.onerror = err => console.error('Event socket error:', err);
}
