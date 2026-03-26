// auth.js
function isAuthPage() {
    const path = window.location.pathname || '';
    return path === '/' || path.endsWith('/login.html') || path.endsWith('/register.html');
}

export class AuthFetchError extends Error {
    constructor(message, { status = null, code = null, isAuthError = false, shouldLogout = false, details = null } = {}) {
        super(message);
        this.name = 'AuthFetchError';
        this.status = status;
        this.code = code;
        this.isAuthError = isAuthError;
        this.shouldLogout = shouldLogout;
        this.details = details;
    }
}

function redirectToLogin(reason) {
    if (reason) console.warn(reason);
    localStorage.removeItem('token');
    if (!isAuthPage()) {
        window.location.replace('/login.html');
    }
}

export function isAuthFailure(error) {
    return Boolean(error?.isAuthError);
}

export function handleAuthFailure(error, reason = 'Authentication required') {
    if (!isAuthFailure(error)) return false;
    if (error?.shouldLogout !== false) {
        redirectToLogin(reason);
    }
    return true;
}

export async function authFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) {
        const error = new AuthFetchError('No active session.', {
            status: 401,
            code: 'NO_TOKEN',
            isAuthError: true,
            shouldLogout: true
        });
        redirectToLogin('No token found - redirecting to login');
        throw error;
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
        const error = new AuthFetchError('Authentication required.', {
            status: 401,
            code: 'LOGIN_REDIRECT',
            isAuthError: true,
            shouldLogout: true
        });
        redirectToLogin('Session expired - redirecting to login');
        throw error;
    }

    if (response.ok && contentType.includes('text/html')) {
        throw new AuthFetchError(`Server returned HTML instead of API payload: ${response.status}`, {
            status: response.status,
            code: 'INVALID_RESPONSE'
        });
    }

    if (!response.ok) {
        const text = await response.text();
        let json = null;

        if (text.includes('<!DOCTYPE') || text.includes('<html')) {
            throw new AuthFetchError(`Server returned HTML instead of JSON: ${response.status}`, {
                status: response.status,
                code: 'INVALID_RESPONSE'
            });
        }

        try {
            json = JSON.parse(text);
        } catch {
            json = null;
        }

        if (response.status === 401) {
            const error = new AuthFetchError(json?.message || 'Authentication required.', {
                status: response.status,
                code: json?.code || 'UNAUTHORIZED',
                isAuthError: true,
                shouldLogout: true,
                details: json
            });
            redirectToLogin(`Auth error ${response.status} - logging out`);
            throw error;
        }

        if (response.status === 403) {
            throw new AuthFetchError(json?.message || 'Access denied.', {
                status: response.status,
                code: json?.code || 'FORBIDDEN',
                isAuthError: false,
                shouldLogout: false,
                details: json
            });
        }

        throw new AuthFetchError(
            json?.message || `HTTP ${response.status}: ${text.substring(0, 200)}`,
            {
                status: response.status,
                code: json?.code || 'HTTP_ERROR',
                details: json
            }
        );
    }

    return response;
}
