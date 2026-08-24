// Shared fetch wrapper: injects the JWT, handles 401s, and centralizes the API base.
const API_BASE = ''; // same origin as the static files

function getToken() {
    return localStorage.getItem('jwt_token');
}

function getUser() {
    const raw = localStorage.getItem('jwt_user');
    return raw ? JSON.parse(raw) : null;
}

function setSession(authResponse) {
    localStorage.setItem('jwt_token', authResponse.token);
    localStorage.setItem('jwt_user', JSON.stringify({
        userId: authResponse.userId,
        name: authResponse.name,
        email: authResponse.email
    }));
}

function clearSession() {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('jwt_user');
}

function logout() {
    clearSession();
    window.location.href = '/index.html';
}

// Redirect to login if there's no token. Call at the top of every protected page.
function requireAuth() {
    if (!getToken()) {
        window.location.href = '/index.html';
    }
}

// Wraps fetch(): adds Authorization header, parses JSON, throws on non-2xx,
// and bounces to login on 401 (expired/invalid token).
async function apiFetch(path, options = {}) {
    const headers = Object.assign(
        { 'Content-Type': 'application/json' },
        options.headers || {}
    );
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const res = await fetch(API_BASE + path, Object.assign({}, options, { headers }));

    if (res.status === 401) {
        clearSession();
        window.location.href = '/index.html';
        throw new Error('Unauthorized');
    }

    // Some endpoints (e.g. pause/resume) return the updated resource; 204s return nothing.
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;

    if (!res.ok) {
        const message = (data && (data.message || data.error)) || `Request failed: ${res.status}`;
        throw new Error(message);
    }
    return data;
}

async function login(email, password) {
    const data = await apiFetch('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password })
    });
    setSession(data);
    return data;
}

async function register(name, email, password) {
    const data = await apiFetch('/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({ name, email, password })
    });
    setSession(data);
    return data;
}