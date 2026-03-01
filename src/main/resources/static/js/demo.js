// demo.js
async function startDemoTest() {

    const button = document.querySelector('.dashboard-actions button');

    button.disabled = true;
    button.textContent = "Pokrećem demo test...";

    try {
        const response = await fetch('/start-demo');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;

        window.location.href = `/demo.html?matchId=${matchId}`;

    } catch (error) {
        alert("Greška pri pokretanju demo testa.");
        button.disabled = false;
        button.textContent = "Start Demo Test";
    }
}
