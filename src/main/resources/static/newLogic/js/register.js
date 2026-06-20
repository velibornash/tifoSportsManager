const form = document.getElementById('registerForm');
const statusBox = document.getElementById('registerStatus');
const submitButton = document.getElementById('registerBtn');

function setStatus(type, message) {
    statusBox.hidden = false;
    statusBox.className = `auth-status ${type}`;
    statusBox.textContent = message;
}

async function readResponsePayload(response) {
    const text = await response.text();
    if (!text) return {};
    try {
        return JSON.parse(text);
    } catch {
        return { message: text };
    }
}

form.addEventListener('submit', async event => {
    event.preventDefault();

    const formData = new FormData(form);
    const username = String(formData.get('username') || '').trim();
    const email = String(formData.get('email') || '').trim();
    const password = String(formData.get('password') || '');
    const confirmPassword = String(formData.get('confirmPassword') || '');

    if (password !== confirmPassword) {
        setStatus('error', 'Passwords do not match.');
        return;
    }

    submitButton.disabled = true;
    submitButton.textContent = 'Sending...';
    setStatus('info', 'Submitting registration request...');

    try {
        const response = await fetch('/auth/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username, email, password })
        });

        const payload = await readResponsePayload(response);
        const baseMessage = payload?.message || 'Request processed.';

        if (response.ok) {
            const reservedClub = payload?.reservedTeamName ? ` Reserved club: ${payload.reservedTeamName}.` : '';
            form.reset();
            setStatus('success', `${baseMessage}${reservedClub}`);
            return;
        }

        setStatus('error', baseMessage);
    } catch (error) {
        setStatus('error', `Connection error: ${error.message}`);
    } finally {
        submitButton.disabled = false;
        submitButton.textContent = 'Send registration request';
    }
});