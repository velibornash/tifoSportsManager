<script>
    function updateLiveClock() {
        const now = new Date();

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

        // Sezona i faza (možeš promeniti logiku)
        const year = now.getFullYear();
        const month = now.getMonth() + 1;
        let season = month >= 7 ? year : year - 1;
        let phase = '';
        if (month >= 7 && month <= 8) phase = 'Predsezona';
        else if (month >= 9 || month <= 5) phase = 'Sezona u toku';
        else phase = 'Letnja pauza';

        // Ažuriraj desktop
        document.getElementById('clock-time').textContent = timeStr;
        document.getElementById('clock-date').textContent = dateStr + ' • Sezona ' + season;
        document.getElementById('clock-phase').textContent = phase;

        // Ažuriraj mobilni (ako postoji)
        if (document.getElementById('clock-time-m')) {
            document.getElementById('clock-time-m').textContent = timeStr;
            document.getElementById('clock-date-m').textContent = dateStr + ' • ' + phase;
        }
    }

    // Pokreni odmah i ažuriraj svake sekunde
    updateLiveClock();
    setInterval(updateLiveClock, 1000);
</script>