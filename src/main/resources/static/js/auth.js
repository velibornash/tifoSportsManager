//auth.js
export async function authFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn("No token found - redirecting to login");
        //localStorage.removeItem('token');
        //window.location.href = '/login.html';
        throw new Error("No token");
    }

    const headers = {
        ...options.headers,
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
    };

    const response = await fetch(url, { ...options, headers });

    if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
            console.warn(`Auth error ${response.status} - logging out`);
            //localStorage.removeItem('token');
            //window.location.href = '/login.html';
        }
        const errorText = await response.text();
        throw new Error(`API error ${response.status}: ${errorText}`);
    }

    return response;
}