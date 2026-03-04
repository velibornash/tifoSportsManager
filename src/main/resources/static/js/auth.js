//auth.js
export async function authFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn("No token found - redirecting to login");
        localStorage.removeItem('token');
        window.location.href = '/login.html';
        throw new Error("No token");
    }

    const headers = {
        ...options.headers,
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
    };

    const response = await fetch(url, { ...options, headers });

    if (!response.ok) {
    const text = await response.text();
                console.error(`Fetch error ${res.status}: ${text.substring(0, 200)}...`);
                if (text.includes('<!DOCTYPE') || text.includes('<html')) {
                    throw new Error(`Server returned HTML instead of JSON (possible auth issue): ${res.status}`);
                }
                try {
                    const json = JSON.parse(text);
                    throw new Error(json.message || `HTTP ${res.status}`);
                } catch {
                    throw new Error(`HTTP ${res.status}: ${text.substring(0, 100)}...`);
                }
        if (response.status === 401 || response.status === 403) {
            console.warn(`Auth error ${response.status} - logging out`);
            localStorage.removeItem('token');
            window.location.href = '/login.html';
        }
        const errorText = await response.text();
        throw new Error(`API error ${response.status}: ${errorText}`);
    }

    return response;
}