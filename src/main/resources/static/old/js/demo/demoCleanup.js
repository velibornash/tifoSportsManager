// demoCleanup.js
let positionSocket = null;
let eventSocket = null;

export function registerSockets(posSock, evtSock) {
    positionSocket = posSock;
    eventSocket = evtSock;
}

export function cleanupDemo() {
    console.log('Cleaning demo page - shutting down resources');

    if (positionSocket) {
        positionSocket.close();
        positionSocket = null;
        console.log('Position WebSocket closed');
    }
    if (eventSocket) {
        eventSocket.close();
        eventSocket = null;
        console.log('Event WebSocket closed');
    }

    import('./canvasRenderer.js')
        .then(module => module.stopCanvasLoop())
        .catch(err => console.error('Failed to stop canvas loop:', err));

    window.eventQueue = [];
    window.isProcessing = false;
    window.players = {};
    window.ball = { x: 50, y: 50, startX: 50, startY: 50, targetX: 50, targetY: 50, moveStartTime: performance.now() };
    window.currentMinute = 0;

    console.log('Demo cleanup finished');
}
