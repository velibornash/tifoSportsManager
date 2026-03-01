// demoCleanup.js – nema kružnih importa!
let positionSocket = null;
let eventSocket = null;

export function registerSockets(posSock, evtSock) {
    positionSocket = posSock;
    eventSocket = evtSock;
}

export function cleanupDemo() {
    console.log("🧹 Cleanup demo stranice – gasimo sve!");

    if (positionSocket) {
        positionSocket.close();
        positionSocket = null;
        console.log("Position WS zatvoren");
    }
    if (eventSocket) {
        eventSocket.close();
        eventSocket = null;
        console.log("Event WS zatvoren");
    }

    // Zaustavi canvas loop
    import('./canvasRenderer.js').then(module => {
        module.stopCanvasLoop();
    }).catch(err => console.error("Ne mogu da zaustavim canvas:", err));

    // Čišćenje event queue-a (ako su globalni)
    window.eventQueue = [];
    window.isProcessing = false;

    // Reset stanja
    window.players = {};
    window.ball = { x:50, y:50, startX:50, startY:50, targetX:50, targetY:50, moveStartTime:performance.now() };
    window.currentMinute = 0;

    console.log("Cleanup završen – sve ugašeno.");
}