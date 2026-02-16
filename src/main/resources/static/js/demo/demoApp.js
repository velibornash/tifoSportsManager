// demoApp.js
import { initCanvas } from './canvasRenderer.js';
import { initSockets } from './sockets.js';
import { initEventProcessor } from './eventProcessor.js';
import { initScoreboard } from './scoreboard.js';

document.addEventListener("DOMContentLoaded", ()=>{
    initCanvas();
    initScoreboard();
    initEventProcessor();
    initSockets();
});
