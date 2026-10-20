const form = document.getElementById('registerForm');
const statusBox = document.getElementById('registerStatus');
const submitButton = document.getElementById('registerBtn');

function setStatus(type, message) {
    statusBox.hidden = false;
    statusBox.className = `auth-status ${type}`;
    statusBox.textContent = message;
}

/**
 * Fills the country picker from the server's own list.
 *
 * <p>Served from `CountryCatalog` rather than hardcoded here. An earlier version of this data lived in
 * two places at once - a Java array and a hand-typed `<select>` - and they had already drifted from
 * each other, with three countries carrying non-standard codes in the database.
 *
 * <p>Countries with no clubs are shown but not selectable, and say so. Hiding them would make the
 * game look smaller than it is; offering them would hand a new manager a registration that is
 * guaranteed to be refused.
 */
async function loadCountries() {
    const select = document.getElementById('countrySelect');
    const note = document.getElementById('countryNote');
    if (!select) return;

    try {
        const response = await fetch('/countries/catalog');
        if (!response.ok) throw new Error('countries request failed: ' + response.status);
        const countries = await response.json();

        select.innerHTML = '';
        countries.forEach(country => {
            const option = document.createElement('option');
            option.value = country.code;
            option.textContent = country.name + (country.hasClubs ? '' : ' — not set up yet');
            option.disabled = !country.hasClubs;
            select.appendChild(option);
        });

        const playable = countries.filter(c => c.hasClubs);
        if (playable.length === 0) {
            select.disabled = true;
            if (note) {
                note.hidden = false;
                note.textContent = 'No country has a league system yet, so registration is unavailable.';
            }
        } else if (note) {
            note.hidden = false;
            note.textContent = countries.length + ' countries. ' + playable.length
                + ' can be played right now.';
        }
    } catch (error) {
        select.innerHTML = '<option value="">Could not load countries</option>';
        select.disabled = true;
        if (note) {
            note.hidden = false;
            note.textContent = 'The country list could not be loaded. Check your connection and reload.';
        }
    }
}

loadCountries();

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
    const countryCode = String(formData.get('countryCode') || '').trim();

    if (!countryCode) {
        setStatus('error', 'Choose the country you want to play in.');
        return;
    }

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
            body: JSON.stringify({ username, email, password, countryCode })
        });

        const payload = await readResponsePayload(response);
        const baseMessage = payload?.message || 'Request processed.';

        if (response.ok) {
            const reservedClub = payload?.reservedTeamName ? ` Reserved club: ${payload.reservedTeamName}.` : '';
            const countryName = payload?.countryName ? ` Country: ${payload.countryName}.` : '';
            form.reset();
            setStatus('success', `${baseMessage}${countryName}${reservedClub}`);
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