package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction;
import com.github.tomakehurst.wiremock.extension.requestfilter.StubRequestFilterV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * Intercepts GET / and serves a server info page.
 * This runs as a request filter, NOT as a stub — so it never
 * appears in /__admin/mappings or the dashboard GUI.
 */
public class RootRedirectFilter implements StubRequestFilterV2 {

    @Override
    public String getName() {
        return "root-redirect-filter";
    }

    @Override
    public RequestFilterAction filter(Request request, ServeEvent serveEvent) {
        String url = request.getUrl();
        String method = request.getMethod().toString();

        if ("GET".equals(method) && "/".equals(url)) {
            ResponseDefinition response = new ResponseDefinition(
                200,
                null,       // statusMessage
                INFO_PAGE,  // body
                null,       // jsonBody
                null,       // base64Body
                null,       // bodyFileName
                new HttpHeaders(
                    new HttpHeader("Content-Type", "text/html; charset=utf-8"),
                    new HttpHeader("Cache-Control", "no-cache")
                ),
                null,       // additionalProxyRequestHeaders
                null,       // removeProxyRequestHeaders
                null,       // fixedDelayMilliseconds
                null,       // delayDistribution
                null,       // chunkedDribbleDelay
                null,       // proxyBaseUrl
                null,       // proxyUrlPrefixToRemove
                null,       // fault
                null,       // transformers
                null,       // transformerParameters
                null        // wasConfigured
            );
            return RequestFilterAction.stopWith(response);
        }

        // Silently absorb favicon.ico — browsers request it automatically
        if ("GET".equals(method) && "/favicon.ico".equals(url)) {
            ResponseDefinition response = new ResponseDefinitionBuilder()
                .withStatus(204)
                .withHeader("Cache-Control", "max-age=86400")
                .build();
            return RequestFilterAction.stopWith(response);
        }

        return RequestFilterAction.continueWith(request);
    }

    private static final String INFO_PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="description" content="WireMock Mock Server - WireMock service virtualization">
<meta name="theme-color" content="#1E1B4B">
<link rel="icon" type="image/svg+xml" href="/__admin/favicon.svg">
<title>WireMock Mock Server</title>
<style>
:root {
    --color-primary: #4F46E5;
    --color-primary-dark: #3730A3;
    --color-accent: #F97316;
    --color-success: #16A34A;
    --color-font-mono: ui-monospace, 'Courier New', Courier, monospace;
    --transition-base: 250ms cubic-bezier(0.4, 0, 0.2, 1);
    --radius-sm: 4px;
    --radius-base: 10px;
    --radius-card: 14px;
}
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
*:focus-visible { outline: 2px solid #818cf8; outline-offset: 2px; }
body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Inter', Roboto, sans-serif;
    margin: 0;
    background: #0F0E1A;
    color: #c7d2fe;
    font-size: 14px;
    line-height: 1.5;
    display: flex;
    flex-direction: column;
    align-items: center;
    min-height: 100vh;
    padding: 0 0 48px 0;
}
/* ---- Top navbar ---- */
.topbar {
    width: 100%;
    background: linear-gradient(135deg, #1E1B4B 0%, #312E81 100%);
    border-bottom: 1px solid rgba(129,140,248,0.2);
    padding: 0 32px;
    height: 60px;
    display: flex;
    align-items: center;
    gap: 14px;
    box-shadow: 0 4px 24px rgba(0,0,0,0.4);
    margin-bottom: 40px;
    position: sticky;
    top: 0;
    z-index: 100;
}
.topbar-logo {
    width: 34px;
    height: 34px;
    flex-shrink: 0;
    filter: drop-shadow(0 0 8px rgba(249,115,22,0.5));
}
.topbar-title {
    font-size: 16px;
    font-weight: 700;
    color: #fff;
    letter-spacing: -0.2px;
}
.topbar-sub {
    font-size: 11px;
    color: rgba(165,180,252,0.7);
    margin-left: 2px;
    letter-spacing: 0.2px;
}
/* ---- Main content ---- */
.main-container {
    max-width: 860px;
    width: 100%;
    padding: 0 24px;
}
/* ---- Hero ---- */
.hero {
    text-align: center;
    margin-bottom: 40px;
}
.hero-logo {
    width: 72px;
    height: 72px;
    margin: 0 auto 20px;
    filter: drop-shadow(0 0 20px rgba(249,115,22,0.4));
}
.hero h1 {
    font-size: 30px;
    font-weight: 800;
    color: #e0e7ff;
    letter-spacing: -0.5px;
    margin-bottom: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
}
.hero p {
    font-size: 15px;
    color: #64748b;
    max-width: 520px;
    margin: 0 auto;
    line-height: 1.7;
}
.info-btn {
    background: none;
    border: 1px solid rgba(129,140,248,0.3);
    padding: 4px 6px;
    cursor: pointer;
    color: #818cf8;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    transition: all 0.15s ease;
    vertical-align: middle;
}
.info-btn:hover { background: rgba(129,140,248,0.1); border-color: rgba(129,140,248,0.6); color: #c7d2fe; }
/* ---- Section label ---- */
.section-label {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: #475569;
    margin-bottom: 12px;
    margin-top: 32px;
}
.section-label:first-child { margin-top: 0; }
/* ---- Nav cards ---- */
.nav-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(175px, 1fr));
    gap: 14px;
}
.nav-card {
    background: #1A1830;
    border: 1px solid rgba(129,140,248,0.15);
    border-radius: var(--radius-card);
    padding: 22px 16px;
    text-decoration: none;
    text-align: center;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    transition: all var(--transition-base);
    color: inherit;
    position: relative;
    overflow: hidden;
}
.nav-card::before {
    content: '';
    position: absolute;
    top: 0; left: 0; right: 0;
    height: 2px;
    background: linear-gradient(90deg, #4F46E5, #818cf8);
    opacity: 0;
    transition: opacity var(--transition-base);
}
.nav-card:hover {
    border-color: rgba(129,140,248,0.45);
    transform: translateY(-3px);
    box-shadow: 0 12px 28px rgba(0,0,0,0.4);
}
.nav-card:hover::before { opacity: 1; }
.nav-card .card-icon { color: #818cf8; transition: color var(--transition-base); }
.nav-card:hover .card-icon { color: #F97316; }
.nav-card h3 {
    font-size: 14px;
    font-weight: 600;
    color: #e0e7ff;
    margin: 0;
}
.nav-card p {
    font-size: 12px;
    color: #64748b;
    margin: 0;
    line-height: 1.5;
}
/* ---- Info cards ---- */
.info-card {
    background: #1A1830;
    border: 1px solid rgba(129,140,248,0.13);
    border-radius: var(--radius-base);
    overflow: hidden;
    margin-bottom: 14px;
}
.info-card:last-child { margin-bottom: 0; }
.info-card-h {
    padding: 12px 20px;
    border-bottom: 1px solid rgba(129,140,248,0.1);
    font-weight: 600;
    font-size: 14px;
    color: #c7d2fe;
    background: rgba(79,70,229,0.06);
}
.info-card-c {
    padding: 16px 20px;
    font-size: 13px;
    color: #94a3b8;
    line-height: 1.7;
}
.info-card-c p { margin-bottom: 12px; }
.info-card-c p:last-child { margin-bottom: 0; }
.info-card-c ul { list-style: none; padding: 0; margin: 0 0 12px 0; }
.info-card-c ul:last-child { margin-bottom: 0; }
.info-card-c li { padding: 4px 0 4px 16px; position: relative; }
.info-card-c li::before {
    content: "\\2022";
    position: absolute;
    left: 0;
    color: #F97316;
    font-weight: bold;
}
.info-card-c a { color: #818cf8; text-decoration: none; font-weight: 500; }
.info-card-c a:hover { color: #c7d2fe; text-decoration: underline; }
.info-card-c code {
    font-family: var(--color-font-mono);
    font-size: 12px;
    background: rgba(79,70,229,0.12);
    color: #a5b4fc;
    padding: 2px 7px;
    border-radius: 4px;
    border: 1px solid rgba(79,70,229,0.2);
}
/* ---- API table ---- */
.table-scroll { overflow-x: auto; }
.api-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.api-table th {
    text-align: left;
    font-weight: 700;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: #475569;
    padding: 10px 16px;
    border-bottom: 1px solid rgba(129,140,248,0.12);
    background: rgba(79,70,229,0.05);
}
.api-table td {
    padding: 10px 16px;
    border-bottom: 1px solid rgba(129,140,248,0.07);
    vertical-align: middle;
    color: #94a3b8;
}
.api-table tr:last-child td { border-bottom: none; }
.api-table tbody tr:hover { background: rgba(129,140,248,0.04); }
.method {
    display: inline-block;
    font-family: var(--color-font-mono);
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: var(--radius-sm);
    min-width: 52px;
    text-align: center;
}
.method.get  { background: rgba(8,145,178,0.15); color: #67e8f9; }
.method.post { background: rgba(22,163,74,0.13); color: #86efac; }
.method.delete { background: rgba(220,38,38,0.13); color: #fca5a5; }
.path { font-family: var(--color-font-mono); font-size: 12px; color: #6366f1; }
/* ---- Footer ---- */
.footer {
    text-align: center;
    margin-top: 40px;
    font-size: 12px;
    color: #334155;
}
.footer a { color: #6366f1; text-decoration: none; }
.footer a:hover { color: #a5b4fc; }
/* ---- Modal ---- */
.modal-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.65);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
    backdrop-filter: blur(4px);
}
.modal-overlay.hidden { display: none; }
.modal-content {
    background: #1A1830;
    border: 1px solid rgba(129,140,248,0.2);
    max-width: 580px;
    width: 90%;
    max-height: 80vh;
    overflow-y: auto;
    padding: 28px;
    border-radius: 14px;
    box-shadow: 0 25px 50px rgba(0,0,0,0.7);
}
.modal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 18px;
    padding-bottom: 14px;
    border-bottom: 1px solid rgba(129,140,248,0.12);
}
.modal-header h2 { margin: 0; color: #e0e7ff; font-size: 17px; font-weight: 700; }
.modal-close {
    background: none;
    border: 1px solid rgba(129,140,248,0.2);
    font-size: 1.1rem;
    cursor: pointer;
    color: #64748b;
    padding: 4px 8px;
    border-radius: 6px;
    transition: all 0.15s ease;
}
.modal-close:hover { color: #e0e7ff; background: rgba(129,140,248,0.1); border-color: rgba(129,140,248,0.4); }
.modal-body { font-size: 14px; color: #94a3b8; line-height: 1.7; }
.modal-body h3 { color: #a5b4fc; margin: 18px 0 8px 0; font-size: 14px; font-weight: 600; }
.modal-body ul { margin: 0 0 12px 0; padding-left: 20px; }
.modal-body li { margin-bottom: 5px; line-height: 1.5; }
.modal-body code {
    font-family: var(--color-font-mono);
    font-size: 12px;
    background: rgba(79,70,229,0.12);
    color: #a5b4fc;
    padding: 1px 6px;
    border-radius: 4px;
}
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border-width: 0; }
@keyframes fadeIn { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }
.fade-in { animation: fadeIn 0.35s ease-out; }
@media (max-width: 640px) {
    .nav-grid { grid-template-columns: 1fr 1fr; }
    .hero h1 { font-size: 22px; }
    .topbar { padding: 0 16px; }
    .main-container { padding: 0 16px; }
}
@media (max-width: 420px) {
    .nav-grid { grid-template-columns: 1fr; }
}
</style>
</head>
<body>
<!-- Top navbar -->
<nav class="topbar" role="banner">
    <svg class="topbar-logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" aria-label="WireMock">
        <rect width="100" height="100" fill="#1E1B4B" rx="18"/>
        <line x1="18" y1="25" x2="34" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="34" y1="68" x2="50" y2="45" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="50" y1="45" x2="66" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="66" y1="68" x2="82" y2="25" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="26" y1="46" x2="74" y2="46" stroke="#F97316" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="4 3"/>
        <circle cx="18" cy="25" r="6" fill="#F97316"/>
        <circle cx="34" cy="68" r="5" fill="#A5B4FC"/>
        <circle cx="50" cy="45" r="6.5" fill="#F97316"/>
        <circle cx="66" cy="68" r="5" fill="#A5B4FC"/>
        <circle cx="82" cy="25" r="6" fill="#F97316"/>
    </svg>
    <div>
        <div class="topbar-title">WireMock Mock Server</div>
        <div class="topbar-sub">Service virtualization for testing</div>
    </div>
</nav>

<div class="main-container fade-in" role="main">
    <!-- Hero -->
    <div class="hero">
        <svg class="hero-logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" aria-hidden="true">
            <rect width="100" height="100" fill="#1E1B4B" rx="18"/>
            <line x1="18" y1="25" x2="34" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="34" y1="68" x2="50" y2="45" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="50" y1="45" x2="66" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="66" y1="68" x2="82" y2="25" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="26" y1="46" x2="74" y2="46" stroke="#F97316" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="4 3"/>
            <circle cx="18" cy="25" r="6" fill="#F97316"/>
            <circle cx="34" cy="68" r="5" fill="#A5B4FC"/>
            <circle cx="50" cy="45" r="6.5" fill="#F97316"/>
            <circle cx="66" cy="68" r="5" fill="#A5B4FC"/>
            <circle cx="82" cy="25" r="6" fill="#F97316"/>
        </svg>
        <h1>WireMock Mock Server
            <button class="info-btn" aria-label="Show information" onclick="toggleInfoModal()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
            </button>
        </h1>
        <p>WireMock-based service virtualization for performance and integration testing</p>
    </div>

    <div class="section-label">Quick Links</div>
    <nav class="nav-grid" aria-label="Features navigation">
        <a class="nav-card" href="/__admin/dashboard" aria-label="Open mock management dashboard">
            <span class="card-icon" aria-hidden="true">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
            </span>
            <h3>Dashboard</h3>
            <p>Create, edit and manage mock stubs with a visual interface</p>
        </a>
        <a class="nav-card" href="/__admin/mappings" aria-label="View stub mappings JSON">
            <span class="card-icon" aria-hidden="true">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
            </span>
            <h3>Admin API</h3>
            <p>Raw JSON view of all registered stub mappings</p>
        </a>
        <a class="nav-card" href="/__admin/stub-counter" aria-label="View request statistics">
            <span class="card-icon" aria-hidden="true">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
            </span>
            <h3>Statistics</h3>
            <p>Request counters grouped by stub pattern and URL</p>
        </a>
        <a class="nav-card" href="https://wiremock.org/docs/" target="_blank" rel="noopener" aria-label="WireMock docs (external)">
            <span class="card-icon" aria-hidden="true">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg>
            </span>
            <h3>WireMock Docs</h3>
            <p>Official documentation and API reference</p>
        </a>
    </nav>

    <div class="section-label">About</div>
    <div class="info-card">
        <div class="info-card-h">Service Virtualization for Testing</div>
        <div class="info-card-c">
            <p>WireMock is an HTTP mock server that simulates APIs your application depends on, enabling testing without relying on real external services.</p>
            <ul>
                <li><strong>Performance testing</strong> &mdash; Simulate backend responses with controlled latency</li>
                <li><strong>Integration testing</strong> &mdash; Mock third-party APIs and downstream services</li>
                <li><strong>Fault injection</strong> &mdash; Simulate errors, timeouts, and slow responses</li>
                <li><strong>Development</strong> &mdash; Work against a mock API before the real service is built</li>
            </ul>
        </div>
    </div>

    <div class="info-card">
        <div class="info-card-h">How This Server Works</div>
        <div class="info-card-c">
            <p>Stubs can be managed visually via the <a href="/__admin/dashboard">Dashboard</a>, programmatically via the <a href="/__admin/mappings">REST API</a>, or by placing JSON files in the <code>/mappings</code> directory.</p>
        </div>
    </div>

    <div class="section-label">API Reference</div>
    <div class="info-card" style="padding: 0;">
        <div class="table-scroll">
            <table class="api-table" role="table" aria-label="API endpoints">
                <thead><tr><th scope="col">Method</th><th scope="col">Endpoint</th><th scope="col">Description</th></tr></thead>
                <tbody>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/mappings</td><td>List all stub mappings</td></tr>
                    <tr><td><span class="method post">POST</span></td><td class="path">/__admin/mappings</td><td>Create a new stub mapping</td></tr>
                    <tr><td><span class="method post">POST</span></td><td class="path">/__admin/mappings/import</td><td>Bulk import stubs</td></tr>
                    <tr><td><span class="method delete">DELETE</span></td><td class="path">/__admin/mappings</td><td>Delete all stub mappings</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/stub-counter</td><td>Request statistics</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/response-times</td><td>Response time statistics</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/server-metrics</td><td>JVM metrics</td></tr>
                    <tr><td><span class="method post">POST</span></td><td class="path">/__admin/reset-stub-counter</td><td>Reset all counters</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/dashboard</td><td>Open mock dashboard</td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <footer class="footer" role="contentinfo">
        WireMock Mock Server &middot; Powered by <a href="https://wiremock.org" target="_blank" rel="noopener">WireMock</a> &middot; Built by <a href="https://github.com/ostbergjohan" target="_blank" rel="noopener">Johan Ostberg</a>
    </footer>
</div>

<!-- Info Modal -->
<div id="infoModal" class="modal-overlay hidden" onclick="if(event.target===this)toggleInfoModal()" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-content">
        <div class="modal-header">
            <h2 id="modal-title">About WireMock Mock Server</h2>
            <button class="modal-close" onclick="toggleInfoModal()" aria-label="Close">&#10005;</button>
        </div>
        <div class="modal-body">
            <p>A WireMock-based mock server with custom extensions for HTTP stub management and monitoring.</p>
            <h3>Key features</h3>
            <ul>
                <li><strong>Request matching:</strong> URL patterns, headers, query parameters, body matchers</li>
                <li><strong>Response templating:</strong> Dynamic responses using Handlebars templates</li>
                <li><strong>Fault simulation:</strong> Delays, connection resets, chunked responses</li>
                <li><strong>Analytics:</strong> Per-stub and per-URL request counters</li>
            </ul>
        </div>
    </div>
</div>

<script>
(function() {
    'use strict';

    function toggleInfoModal() {
        var modal = document.getElementById('infoModal');
        if (!modal) return;
        modal.classList.toggle('hidden');
    }
    window.toggleInfoModal = toggleInfoModal;

    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') {
            var modal = document.getElementById('infoModal');
            if (modal && !modal.classList.contains('hidden')) {
                toggleInfoModal();
            }
        }
    });
})();
</script>
</body>
</html>
""";
}
