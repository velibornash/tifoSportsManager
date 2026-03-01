// demoApp.js
import { initCanvas } from './canvasRenderer.js';
import { initSockets } from './sockets.js';
import { initEventProcessor } from './eventProcessor.js';
import { initScoreboard } from './scoreboard.js';
import { cleanupDemo } from './demoCleanup.js';

document.addEventListener("DOMContentLoaded", () => {
    initCanvas();         // ← ovo pokreće loop – teren i igrači će se pojaviti kad stignu podaci
    initScoreboard();
    initEventProcessor();
    initSockets();        // ← otvara WS-ove i registruje ih za cleanup
});

// Dugme Nazad – globalno dostupno
window.cleanupAndGoBack = function() {
    cleanupDemo();
    window.location.href = '/dashboard.html';
};

// Cleanup na refresh/zatvaranje taba
window.addEventListener('beforeunload', cleanupDemo);