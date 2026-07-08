/*
 * Shared visitor identity + CSRF plumbing. Load before any page script that
 * uses window.visitorId, csrfHeaders(), csrfToken(), or csrfHeaderName().
 */
(function () {
    const KEY = 'visitor_id';
    let id = localStorage.getItem(KEY);
    const isNew = !id;
    if (!id && window.crypto && crypto.randomUUID) {
        id = crypto.randomUUID();
        localStorage.setItem(KEY, id);
        const exp = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toUTCString();
        document.cookie = KEY + '=' + id + '; path=/; expires=' + exp + '; SameSite=Lax';
    }
    window.visitorId = id || 'unknown';
    // True exactly once per browser: the page load that minted the ID.
    window.visitorIsNew = isNew && !!id;
}());

function csrfToken() {
    return document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
}

function csrfHeaderName() {
    return document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN';
}

function csrfHeaders(headers) {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    return token && header ? Object.assign({}, headers, { [header]: token }) : headers;
}
