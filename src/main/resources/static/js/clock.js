let serverOffsetMs = 0;

// Sinhronizuj sa serverom pri učitavanju i svakih 5 minuta
async function syncWithServerTime() {
    try {
        const response = await fetch('/api/server-time');
        if (!response.ok) throw new Error('Greška pri sinhronizaciji');

        const data = await response.json();
        const serverTimestamp = parseInt(data.timestamp);
        serverOffsetMs = Date.now() - serverTimestamp;

        console.log("Sinhronizovano sa server vremenom. Offset (ms):", serverOffsetMs);
    } catch (err) {
        console.warn("Ne može sinhronizovati vreme sa serverom:", err);
        // fallback – koristi lokalno vreme ako server nije dostupan
        serverOffsetMs = 0;
    }
}

// Glavna funkcija za ažuriranje sata
function updateLiveClock() {
    const nowMs = Date.now() - serverOffsetMs;
    const now = new Date(nowMs);

    // Vreme sa sekundama
    const timeStr = now.toLocaleTimeString('sr-RS', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
    });

    // Datum
    const dateStr = now.toLocaleDateString('sr-RS', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric'
    });

    // Sezona i faza (prilagođeno tvojoj logici)
    const year = now.getFullYear();
    const month = now.getMonth() + 1;
    let season = month >= 7 ? year : year - 1;
    let phase = '';
    if (month >= 7 && month <= 8) phase = 'Predsezona';
    else if (month >= 9 || month <= 5) phase = 'Sezona u toku';
    else phase = 'Letnja pauza';

    // Ažuriraj desktop sat
    const timeEl = document.getElementById('clock-time');
    const dateEl = document.getElementById('clock-date');
    const phaseEl = document.getElementById('clock-phase');

    if (timeEl) timeEl.textContent = timeStr;
    if (dateEl) dateEl.textContent = dateStr + ' • Sezona ' + season;
    if (phaseEl) phaseEl.textContent = phase;

    // Ažuriraj mobilni sat (ako postoji)
    const timeMobile = document.getElementById('clock-time-m');
    const dateMobile = document.getElementById('clock-date-m');
    if (timeMobile) timeMobile.textContent = timeStr;
    if (dateMobile) dateMobile.textContent = dateStr + ' • ' + phase;
}

// Pokreni sinhronizaciju i ažuriranje
syncWithServerTime();
setInterval(syncWithServerTime, 5 * 60 * 1000); // sinhronizuj svakih 5 minuta

updateLiveClock();
setInterval(updateLiveClock, 1000); // ažuriraj svake sekunde