requireAuth();

let stompClient = null;

document.addEventListener('DOMContentLoaded', () => {
    loadWorkers();
    connectWebSocket();
});

window.addEventListener('beforeunload', () => {
    if (stompClient) stompClient.disconnect();
});

async function loadWorkers() {
    const container = document.getElementById('workers-list');
    try {
        const workers = await apiFetch('/api/workers');
        renderWorkers(workers);
    } catch (err) {
        container.innerHTML = `<div class="error-box">${escapeHtml(err.message)}</div>`;
    }
}

function renderWorkers(workers) {
    const container = document.getElementById('workers-list');
    if (!workers.length) {
        container.innerHTML = '<div class="empty-state">No workers registered yet.</div>';
        return;
    }
    container.innerHTML = `
        <table>
            <thead><tr><th>ID</th><th>Name</th><th>Status</th><th>Last Heartbeat</th><th>Started</th></tr></thead>
            <tbody>
                ${workers.map(workerRowHtml).join('')}
            </tbody>
        </table>
    `;
}

function workerRowHtml(w) {
    return `
        <tr id="worker-row-${w.id}">
            <td>${w.id}</td>
            <td>${escapeHtml(w.name)}</td>
            <td><span class="badge badge-${w.status.toLowerCase()}" id="worker-status-${w.id}">${w.status}</span></td>
            <td id="worker-heartbeat-${w.id}">${formatDate(w.lastHeartbeatAt)}</td>
            <td>${formatDate(w.startedAt)}</td>
        </tr>
    `;
}

function patchOrInsertWorkerRow(evt) {
    const existingRow = document.getElementById(`worker-row-${evt.workerId}`);
    if (existingRow) {
        document.getElementById(`worker-status-${evt.workerId}`).outerHTML =
            `<span class="badge badge-${evt.status.toLowerCase()}" id="worker-status-${evt.workerId}">${evt.status}</span>`;
        existingRow.style.transition = 'background-color 0.2s';
        existingRow.style.backgroundColor = 'rgba(91,140,255,0.15)';
        setTimeout(() => { existingRow.style.backgroundColor = ''; }, 600);
    } else {
        const tbody = document.querySelector('#workers-list table tbody');
        if (!tbody) { loadWorkers(); return; }
        tbody.insertAdjacentHTML('beforeend', workerRowHtml({
            id: evt.workerId,
            name: evt.workerName,
            status: evt.status,
            lastHeartbeatAt: evt.timestamp,
            startedAt: evt.timestamp
        }));
    }
}

function connectWebSocket() {
    const sock = new SockJS('/ws');
    stompClient = Stomp.over(sock);
    stompClient.debug = null;

    stompClient.connect({}, () => {
        setLiveStatus(true);
        stompClient.subscribe('/topic/workers', (message) => {
            const evt = JSON.parse(message.body);
            patchOrInsertWorkerRow(evt);
        });
    }, () => {
        setLiveStatus(false);
        setTimeout(connectWebSocket, 3000);
    });
}

function setLiveStatus(connected) {
    const dot = document.getElementById('live-dot');
    const label = document.getElementById('live-label');
    dot.classList.toggle('connected', connected);
    label.textContent = connected ? 'Live' : 'Reconnecting…';
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function formatDate(iso) {
    if (!iso) return '—';
    return new Date(iso).toLocaleString();
}