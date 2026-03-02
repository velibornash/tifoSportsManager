    async function authFetch(url, options = {}) {
        const token = localStorage.getItem('token');
        if (!token) {
            throw new Error("No token found - redirecting to login");
        }

        options.headers = {
            ...options.headers,
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
        };

        const response = await fetch(url, options);
        if (!response.ok) {
            if (response.status === 403 || response.status === 401) {
                //localStorage.removeItem('token');
                //window.location.href = '/login.html';
            }
            throw new Error(`Greška ${response.status}: ${await response.text()}`);
        }
        return response;
    }

    let serverOffsetMs = 0;

    // Sinhronizuj sa serverom pri učitavanju i svakih 5 minuta
    async function syncWithServerTime() {
        try {
            const response = await authFetch('/api/server-time');
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

    function updateLiveClock() {
        const nowMs = Date.now() - serverOffsetMs;
        const now = new Date(nowMs);

        // Vreme u CET
        const timeStr = now.toLocaleTimeString('sr-RS', {
            timeZone: 'Europe/Belgrade',  // ← dodaj ovo
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false
        });

        // Datum u CET
        const dateStr = now.toLocaleDateString('sr-RS', {
            timeZone: 'Europe/Belgrade',  // ← dodaj ovo
            day: '2-digit',
            month: '2-digit',
            year: 'numeric'
        });

        // Sezona i faza – koristi CET month/year
        const cetDate = new Date(now.toLocaleString('en-US', { timeZone: 'Europe/Belgrade' }));
        const year = cetDate.getFullYear();
        const month = cetDate.getMonth() + 1;
        let season = month >= 7 ? year : year - 1;
        let phase = '';
        if (month >= 7 && month <= 8) phase = 'PRE SEASON';
        else if (month >= 9 || month <= 5) phase = 'Season in progress';
        else phase = 'OFF SEASON';

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