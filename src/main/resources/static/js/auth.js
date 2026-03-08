// auth.js
function isAuthPage() {
    const path = window.location.pathname || '';
    return path === '/' || path.endsWith('/login.html') || path.endsWith('/register.html');
}

function redirectToLogin(reason) {
    if (reason) console.warn(reason);
    localStorage.removeItem('token');
    if (!isAuthPage()) {
        window.location.replace('/login.html');
    }
}

export async function authFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) {
        redirectToLogin('No token found - redirecting to login');
        throw new Error('No token');
    }

    const headers = {
        ...options.headers,
        Authorization: `Bearer ${token}`,
        'X-Requested-With': 'XMLHttpRequest'
    };

    const hasBody = options.body != null && !(options.body instanceof FormData);
    if (hasBody && headers['Content-Type'] == null && headers['content-type'] == null) {
        headers['Content-Type'] = 'application/json';
    }

    const response = await fetch(url, { ...options, headers });
    const contentType = response.headers.get('content-type') || '';

    if (response.redirected && response.url.includes('/login.html')) {
        redirectToLogin('Session expired - redirecting to login');
        throw new Error('Authentication required');
    }

    if (response.ok && contentType.includes('text/html')) {
        throw new Error(`Server returned HTML instead of API payload: ${response.status}`);
    }

    if (!response.ok) {
        const text = await response.text();

        if (response.status === 401 || response.status === 403) {
            redirectToLogin(`Auth error ${response.status} - logging out`);
            throw new Error(`Authentication error: ${response.status}`);
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
