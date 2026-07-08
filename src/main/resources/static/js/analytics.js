/*
 * Fire-and-forget funnel events to /analytics/event. Requires visitor.js.
 * Analytics must never break the page: every failure path is swallowed.
 */
function trackEvent(eventType, fields, opts) {
    try {
        fetch('/analytics/event', {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(Object.assign(
                { visitorId: window.visitorId, eventType: eventType },
                fields || {}
            )),
            keepalive: !!(opts && opts.keepalive)
        }).catch(function () {});
    } catch (e) {
        /* ignore */
    }
}
