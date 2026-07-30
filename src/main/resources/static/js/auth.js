// auth.js
const LAST_APP_ERROR_KEY = 'tifo:last-app-error';
const AUTH_TOKEN_KEY = 'token';

function isAuthPage() {
    const path = window.location.pathname || '';
    return path === '/' || path.endsWith('/login.html') || path.endsWith('/register.html') || path.endsWith('/login.html') || path.endsWith('/register.html');
}

export class AuthFetchError extends Error {
    constructor(message, { status = null, code = null, isAuthError = false, shouldLogout = false, details = null, url = null } = {}) {
        super(message);
        this.name = 'AuthFetchError';
        this.status = status;
        this.code = code;
        this.isAuthError = isAuthError;
        this.shouldLogout = shouldLogout;
        this.details = details;
        this.url = url;
    }
}

export function getAuthToken() {
    return sessionStorage.getItem(AUTH_TOKEN_KEY);
}

export function setAuthToken(token) {
    if (token) {
        sessionStorage.setItem(AUTH_TOKEN_KEY, token);
    } else {
        sessionStorage.removeItem(AUTH_TOKEN_KEY);
    }
    localStorage.removeItem(AUTH_TOKEN_KEY);
}

export function clearAuthToken() {
    sessionStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(AUTH_TOKEN_KEY);
}

function cloneDetails(details) {
    if (details == null || typeof details !== 'object') {
        return details;
    }

    try {
        return JSON.parse(JSON.stringify(details));
    } catch (error) {
        return {
            message: String(details?.message || error?.message || 'Unserializable error details'),
            name: details?.name || 'Error'
        };
    }
}

function storeLastAppError(reason, details = {}, scope = 'app') {
    try {
        sessionStorage.setItem(LAST_APP_ERROR_KEY, JSON.stringify({
            scope,
            reason,
            details: cloneDetails(details),
            url: window.location.href,
            path: window.location.pathname,
            timestamp: new Date().toISOString()
        }));
    } catch (storageError) {
        console.warn('Failed to persist last app error:', storageError);
    }
}

export function readLastAppError() {
    try {
        const raw = sessionStorage.getItem(LAST_APP_ERROR_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (error) {
        console.warn('Failed to read last app error:', error);
        return null;
    }
}

export function clearLastAppError() {
    try {
        sessionStorage.removeItem(LAST_APP_ERROR_KEY);
    } catch (error) {
        console.warn('Failed to clear last app error:', error);
    }
}

function showErrorOverlay(message, details = {}) {
    const overlay = document.createElement('div');
    overlay.id = 'auth-error-overlay';
    overlay.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background: rgba(0, 0, 0, 0.9);
        color: #ff4444;
        display: flex;
        flex-direction: column;
        justify-content: center;
        align-items: center;
        z-index: 99999;
        font-family: monospace;
        padding: 20px;
        box-sizing: border-box;
    `;
    
    const title = document.createElement('h1');
    title.textContent = '⚠️ Authentication Error';
    title.style.cssText = 'font-size: 24px; margin-bottom: 20px; color: #ff6666;';
    
    const messageEl = document.createElement('div');
    messageEl.textContent = message;
    messageEl.style.cssText = 'font-size: 16px; margin-bottom: 20px; text-align: center;';
    
    const detailsEl = document.createElement('pre');
    detailsEl.textContent = JSON.stringify(details, null, 2);
    detailsEl.style.cssText = 'font-size: 12px; color: #aaa; max-width: 80%; overflow: auto;';
    
    const countdown = document.createElement('div');
    countdown.style.cssText = 'margin-top: 20px; color: #888;';
    countdown.textContent = 'Redirecting to login in 3 seconds...';
    
    overlay.appendChild(title);
    overlay.appendChild(messageEl);
    overlay.appendChild(detailsEl);
    overlay.appendChild(countdown);
    
    document.body.appendChild(overlay);
    
    let seconds = 3;
    const interval = setInterval(() => {
        seconds--;
        if (seconds > 0) {
            countdown.textContent = `Redirecting to login in ${seconds} seconds...`;
        } else {
            clearInterval(interval);
        }
    }, 1000);
}

function redirectToLogin(reason, errorDetails = {}) {
    storeLastAppError(reason, errorDetails, 'auth');
    console.error('=== AUTH REDIRECT ===');
    console.error('Reason:', reason);
    console.error('Current URL:', window.location.href);
    console.error('Error Details:', errorDetails);
    console.error('Token exists:', !!getAuthToken());
    console.error('Token preview:', getAuthToken()?.substring(0, 20) + '...');
    console.error('===================');
    
    if (!isAuthPage()) {
        showErrorOverlay(reason, errorDetails);
        setTimeout(() => {
            clearAuthToken();
            window.location.replace('/login.html');
        }, 3000);
    } else {
        clearAuthToken();
    }
}

export function isAuthFailure(error) {
    return Boolean(error?.isAuthError);
}

export function handleAuthFailure(error, reason = 'Authentication required') {
    if (!isAuthFailure(error)) return false;
    if (error?.shouldLogout !== false) {
        redirectToLogin(reason, {
            status: error.status,
            code: error.code,
            url: error.url,
            details: error.details
        });
    }
    return true;
}

export async function authFetch(url, options = {}) {
    const token = getAuthToken();
    if (!token) {
        const error = new AuthFetchError('No active session.', {
            status: 401,
            code: 'NO_TOKEN',
            isAuthError: true,
            shouldLogout: true,
            url: url
        });
        redirectToLogin('No token found - redirecting to login', { url, code: 'NO_TOKEN' });
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

    let response;
    try {
        response = await fetch(url, { ...options, headers });
    } catch (networkError) {
        console.error('Network error during authFetch:', networkError);
        storeLastAppError('Network error during authenticated request', {
            url,
            message: networkError.message,
            stack: networkError.stack
        }, 'network');
        throw new AuthFetchError(`Network error: ${networkError.message}`, {
            status: 0,
            code: 'NETWORK_ERROR',
            isAuthError: false,
            url: url
        });
    }
    
    const contentType = response.headers.get('content-type') || '';

    if (response.redirected && (response.url.includes('/login.html') || response.url.includes('/login.html'))) {
        const error = new AuthFetchError('Authentication required.', {
            status: 401,
            code: 'LOGIN_REDIRECT',
            isAuthError: true,
            shouldLogout: true,
            url: url
        });
        redirectToLogin('Session expired - redirecting to login', {
            url,
            redirectedTo: response.url,
            code: 'LOGIN_REDIRECT'
        });
        throw error;
    }

    if (response.ok && contentType.includes('text/html')) {
        storeLastAppError('Server returned HTML instead of API payload', {
            url,
            status: response.status,
            contentType
        }, 'response');
        throw new AuthFetchError(`Server returned HTML instead of API payload: ${response.status}`, {
            status: response.status,
            code: 'INVALID_RESPONSE',
            url: url
        });
    }

    if (!response.ok) {
        const text = await response.text();
        let json = null;

        if (text.includes('<!DOCTYPE') || text.includes('<html')) {
            storeLastAppError('Server returned HTML instead of JSON', {
                url,
                status: response.status,
                responseText: text.substring(0, 500)
            }, 'response');
            throw new AuthFetchError(`Server returned HTML instead of JSON: ${response.status}`, {
                status: response.status,
                code: 'INVALID_RESPONSE',
                url: url
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
                details: json,
                url: url
            });
            storeLastAppError(`Auth error ${response.status}`, {
                url,
                status: response.status,
                code: json?.code || 'UNAUTHORIZED',
                message: json?.message,
                responseText: text.substring(0, 500)
            }, 'auth');
            redirectToLogin(`Auth error ${response.status} - logging out`, {
                url,
                status: response.status,
                code: json?.code || 'UNAUTHORIZED',
                message: json?.message,
                responseText: text.substring(0, 500)
            });
            throw error;
        }

        if (response.status === 403) {
            storeLastAppError(`Forbidden ${response.status}`, {
                url,
                status: response.status,
                code: json?.code || 'FORBIDDEN',
                message: json?.message
            }, 'auth');
            throw new AuthFetchError(json?.message || 'Access denied.', {
                status: response.status,
                code: json?.code || 'FORBIDDEN',
                isAuthError: false,
                shouldLogout: false,
                details: json,
                url: url
            });
        }

        storeLastAppError(`HTTP ${response.status} for authenticated request`, {
            url,
            status: response.status,
            code: json?.code || 'HTTP_ERROR',
            message: json?.message,
            responseText: text.substring(0, 500)
        }, 'response');
        throw new AuthFetchError(
            json?.message || `HTTP ${response.status}: ${text.substring(0, 200)}`,
            {
                status: response.status,
                code: json?.code || 'HTTP_ERROR',
                details: json,
                url: url
            }
        );
    }

    return response;
}
