// sockets.js

import { registerSockets } from './demoCleanup.js';
import { updatePositionsData } from './canvasRenderer.js';      // ← ovo je falilo za pozicije
import { enqueueEvent } from './eventProcessor.js';             // ← ovo je falilo za evente
import { getQueryParam } from './utils.js';                     // ako imaš utils.js

export function initSockets(){
    const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const base = `${wsProtocol}://${location.host}`;
    const matchId = getQueryParam('matchId');
    const token = localStorage.getItem("token");
    const positionSocket = new WebSocket(
        `${base}/demo-position-updates?matchId=${matchId}&token=${token}`
    );
    const eventSocket = new WebSocket(
        `${base}/demo-match-events?matchId=${matchId}&token=${token}`
    );
    positionSocket.onopen = () => console.log('✅ Position socket connected');
    positionSocket.onmessage = e => {
        try {
            const data = JSON.parse(e.data);
            updatePositionsData(data);  // ← sada radi
        } catch(err){
            console.error('Position parse error:', err, e.data);
        }
    };
    positionSocket.onclose = () => console.log('Position socket closed');
    positionSocket.onerror = err => console.error('Position socket error:', err);

    eventSocket.onopen = () => {
        console.log('✅ Event socket connected');
        const lastEventBox = document.getElementById('lastEventBox');
        if(lastEventBox) lastEventBox.textContent = '🟢 Povezano – čekamo događaje...';
    };
    eventSocket.onmessage = e => {
        try {
            const ev = JSON.parse(e.data);
            enqueueEvent(ev);  // ← sada radi
        } catch(err){
            console.error('Event parse error:', err, e.data);
        }
    };
    eventSocket.onclose = () => console.log('Event socket closed');
    eventSocket.onerror = err => console.error('Event socket error:', err);

    registerSockets(positionSocket, eventSocket);
}

window.initSockets = initSockets;