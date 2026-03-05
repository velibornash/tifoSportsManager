// auth.js
export async function authFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn('No token found - redirecting to login');
        localStorage.removeItem('token');
        window.location.href = '/login.html';
        throw new Error('No token');
    }

    const headers = {
        ...options.headers,
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
    };

    const response = await fetch(url, { ...options, headers });

    if (!response.ok) {
        const text = await response.text();

        if (response.status === 401 || response.status === 403) {
            console.warn(`Auth error ${response.status} - logging out`);
            localStorage.removeItem('token');
            window.location.href = '/login.html';
        }

        if (text.includes('<!DOCTYPE') || text.includes('<html')) {
            throw new Error(`Server returned HTML instead of JSON: ${response.status}`);
        }

        try {
            const json = JSON.parse(text);
            throw new Error(json.message || `HTTP ${response.status}`);
        } catch {
            throw new Error(`HTTP ${response.status}: ${text.substring(0, 200)}`);
        }
    }

    return response;
}
