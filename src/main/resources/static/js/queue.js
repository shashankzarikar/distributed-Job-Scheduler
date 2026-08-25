requireAuth();

const params = new URLSearchParams(window.location.search);
const queueId = params.get('queueId');
const queueName = params.get('queueName') || `Queue ${queueId}`;
const projectId = params.get('projectId');

let stompClient = null;
let statsPollHandle = null;
const STATUS_ORDER = ['CLAIMED', 'RUNNING', 'QUEUED', 'SCHEDULED', 'COMPLETED', 'FAILED', 'DEAD_LETTER', 'PARTIALLY_FAILED'];

document.addEventListener('DOMContentLoaded', () => {
    if (!queueId) {
        document.body.innerHTML = '<div class="container"><div class="error-box">No queueId provided in URL.</div></div>';
        return;
    }
    document.getElementById('queue-title').textContent = queueName;
    document.getElementById('back-link').href = projectId ? `/dashboard.html` : '/dashboard.html';
    document.getElementById('dlq-link').href = `/dlq.html?queueId=${queueId}&queueName=${encodeURIComponent(queueName)}`;

    loadJobs();
    loadStats();
    statsPollHandle = setInterval(loadStats, 5000);
    connectWebSocket();
});

window.addEventListener('beforeunload', () => {
    if (statsPollHandle) clearInterval(statsPollHandle);
    if (stompClient) stompClient.disconnect();
});

// ---------- Stats (REST polling — see design decision 3.30) ----------
async function loadStats() {
    try {
        const stats = await apiFetch(`/api/queues/${queueId}/stats`);
        document.getElementById('stats-row').innerHTML = `
            <div class="stat-box"><div class="num">${stats.queuedCount}</div><div class="label">Queued</div></div>
            <div class="stat-box"><div class="num">${stats.runningCount}</div><div class="label">Running</div></div>
            <div class="stat-box"><div class="num">${stats.completedCount}</div><div class="label">Completed</div></div>
            <div class="stat-box"><div class="num">${stats.failedCount}</div><div class="label">Failed</div></div>
            <div class="stat-box"><div class="num">${stats.deadLetterCount}</div><div class="label">Dead Letter</div></div>
        `;
    } catch (err) {
        // Non-fatal — stats are supplementary; don't interrupt the job table for a stats blip.
        console.warn('Failed to load queue stats:', err.message);
    }
}

// ---------- Job list ----------
async function loadJobs() {
    const container = document.getElementById('jobs-table-container');
    try {
        const page = await apiFetch(`/api/queues/${queueId}/jobs?size=50`);
        renderJobs(page.content);
    } catch (err) {
        container.innerHTML = `<div class="error-box">${escapeHtml(err.message)}</div>`;
    }
}

function renderJobs(jobs) {
    const container = document.getElementById('jobs-table-container');
    if (!jobs.length) {
        container.innerHTML = '<div class="empty-state">No immediate/batch jobs yet. Delayed/scheduled/cron jobs won\'t appear here until promoted — see docs.</div>';
        return;
    }
    container.innerHTML = `
        <table id="jobs-table">
            <thead>
                <tr>
                    <th>ID</th><th>Type</th><th>Status</th><th>Attempts</th><th>Priority</th><th>Worker</th><th>Updated</th>
                </tr>
            </thead>
            <tbody>
                ${jobs.map(jobRowHtml).join('')}
            </tbody>
        </table>
    `;
}

function jobRowHtml(job) {
    return `
        <tr id="job-row-${job.id}">
            <td>${job.id}</td>
            <td>${job.type}${job.parentJobId ? ' <span class="small">(child)</span>' : ''}</td>
            <td><span class="badge badge-${job.status.toLowerCase()}" id="job-status-${job.id}">${job.status}</span></td>
            <td id="job-attempts-${job.id}">${job.attemptCount}/${job.maxAttempts}</td>
            <td>${job.priority}</td>
            <td id="job-worker-${job.id}">${job.claimedByWorker ? job.claimedByWorker.id ?? '' : '—'}</td>
            <td id="job-updated-${job.id}">${formatDate(job.updatedAt)}</td>
        </tr>
    `;
}

// Patches an existing row live from a WebSocket event, or prepends a new row
// if the job isn't currently in the table (e.g. it was created after page load).
function patchOrInsertJobRow(evt) {
    const existingRow = document.getElementById(`job-row-${evt.jobId}`);
    if (existingRow) {
        document.getElementById(`job-status-${evt.jobId}`).outerHTML =
            `<span class="badge badge-${evt.status.toLowerCase()}" id="job-status-${evt.jobId}">${evt.status}</span>`;
        const attemptsCell = document.getElementById(`job-attempts-${evt.jobId}`);
        if (attemptsCell) attemptsCell.textContent = `${evt.attemptCount}/${evt.maxAttempts}`;
        const workerCell = document.getElementById(`job-worker-${evt.jobId}`);
        if (workerCell) workerCell.textContent = evt.workerId ?? '—';
        const updatedCell = document.getElementById(`job-updated-${evt.jobId}`);
        if (updatedCell) updatedCell.textContent = formatDate(evt.timestamp);

        // Brief highlight flash so the update is visually noticeable.
        existingRow.style.transition = 'background-color 0.2s';
        existingRow.style.backgroundColor = 'rgba(91,140,255,0.15)';
        setTimeout(() => { existingRow.style.backgroundColor = ''; }, 600);
    } else {
        const tbody = document.querySelector('#jobs-table tbody');
        if (!tbody) { loadJobs(); return; } // table not rendered yet (empty state) — just reload
        tbody.insertAdjacentHTML('afterbegin', jobRowHtml({
            id: evt.jobId,
            type: evt.type,
            parentJobId: evt.parentJobId,
            status: evt.status,
            attemptCount: evt.attemptCount,
            maxAttempts: evt.maxAttempts,
            priority: '—',
            claimedByWorker: evt.workerId ? { id: evt.workerId } : null,
            updatedAt: evt.timestamp
        }));
    }
}

// ---------- WebSocket (STOMP over SockJS) — Step G live feed ----------
function connectWebSocket() {
    const sock = new SockJS('/ws');
    stompClient = Stomp.over(sock);
    stompClient.debug = null; // quiet the verbose default console logging

    stompClient.connect({}, () => {
        setLiveStatus(true);
        stompClient.subscribe(`/topic/queues/${queueId}/jobs`, (message) => {
            const evt = JSON.parse(message.body);
            patchOrInsertJobRow(evt);
            loadStats(); // a job transition likely moved the aggregate counts too
        });
    }, () => {
        setLiveStatus(false);
        // Simple reconnect-on-drop; adequate at this project's scale (single dashboard, local dev).
        setTimeout(connectWebSocket, 3000);
    });
}

function setLiveStatus(connected) {
    const dot = document.getElementById('live-dot');
    const label = document.getElementById('live-label');
    dot.classList.toggle('connected', connected);
    label.textContent = connected ? 'Live' : 'Reconnecting…';
}

// ---------- Create immediate job ----------
let jobFieldRowCount = 0;

function toggleCreateJob() {
    const form = document.getElementById('create-job-form');
    form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

function addJobField() {
    jobFieldRowCount++;
    const id = jobFieldRowCount;
    const row = document.createElement('div');
    row.id = `job-field-row-${id}`;
    row.style.cssText = 'display:flex; gap:8px; margin-bottom:8px; align-items:center;';
    row.innerHTML = `
        <input type="text" placeholder="key" class="job-field-key" style="flex:1;">
        <input type="text" placeholder="value" class="job-field-value" style="flex:1;">
        <button type="button" class="secondary" style="padding:8px 10px;" onclick="document.getElementById('job-field-row-${id}').remove()">✕</button>
    `;
    document.getElementById('job-fields-list').appendChild(row);
}

// Builds the payload object: task name (if set) + any key/value rows.
// Values are parsed as JSON where possible (so "42" -> 42, "true" -> true), falling back
// to a plain string — keeps the builder simple while still letting numbers/booleans through.
function buildJobPayload() {
    const payload = {};
    const taskName = document.getElementById('job-task-name').value.trim();
    if (taskName) payload.task = taskName;

    document.querySelectorAll('#job-fields-list > div').forEach(row => {
        const key = row.querySelector('.job-field-key').value.trim();
        const rawValue = row.querySelector('.job-field-value').value.trim();
        if (!key) return;
        try {
            payload[key] = JSON.parse(rawValue);
        } catch {
            payload[key] = rawValue;
        }
    });

    return payload;
}

document.getElementById('create-job-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const errBox = document.getElementById('create-job-error');
    errBox.style.display = 'none';

    const payload = buildJobPayload();
    if (Object.keys(payload).length === 0) {
        errBox.textContent = 'Enter at least a task name or one field';
        errBox.style.display = 'block';
        return;
    }

    const body = { payload };
    const priority = document.getElementById('new-job-priority').value;
    if (priority) body.priority = parseInt(priority, 10);

    try {
        await apiFetch(`/api/queues/${queueId}/jobs/immediate`, { method: 'POST', body: JSON.stringify(body) });
        document.getElementById('create-job-form').reset();
        document.getElementById('job-fields-list').innerHTML = '';
        toggleCreateJob();
        loadJobs();
    } catch (err) {
        errBox.textContent = err.message;
        errBox.style.display = 'block';
    }
});

// ---------- Helpers ----------
function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function formatDate(iso) {
    if (!iso) return '';
    return new Date(iso).toLocaleString();
}