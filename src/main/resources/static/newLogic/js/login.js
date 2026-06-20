// login.js
document.getElementById('loginForm').addEventListener('submit', async function(e) {
    e.preventDefault();

    const email = document.querySelector('input[name="email"]').value;
    const password = document.querySelector('input[name="password"]').value;

    try {
        const response = await fetch('/auth/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username: email, password })
        });

        if (!response.ok) {
            const error = await response.text();
            alert("Greška pri prijavljivanju: " + error);
            return;
        }

        const data = await response.json();
        localStorage.setItem('token', data.token);
        console.log("Token uspešno snimljen:", data.token.substring(0, 20) + "...");
        // Preusmeri na home page za izbor moda
        window.location.href = '/home.html';

    } catch (err) {
        alert("Greška pri konekciji: " + err.message);
    }
});