requireAuth();

const params = new URLSearchParams(window.location.search);
const queueId = params.get('queueId');
const queueName = params.get('queueName') || `Queue ${queueId}`;

document.addEventListener('DOMContentLoaded', () => {
    if (!queueId) {
        document.body.innerHTML = '<div class="container"><div class="error-box">No queueId provided in URL.</div></div>';
        return;
    }
    document.getElementById('dlq-title').textContent = `Dead Letter Queue — ${queueName}`;
    document.getElementById('back-link').href = `/queue.html?queueId=${queueId}&queueName=${encodeURIComponent(queueName)}`;
    loadDlq();
});

async function loadDlq() {
    const container = document.getElementById('dlq-table-container');
    container.innerHTML = '<div class="empty-state">Loading dead-lettered jobs…</div>';
    try {
        const entries = await apiFetch(`/api/queues/${queueId}/dead-letter-queue`);
        renderDlq(entries);
    } catch (err) {
        container.innerHTML = `<div class="error-box">${escapeHtml(err.message)}</div>`;
    }
}

function renderDlq(entries) {
    const container = document.getElementById('dlq-table-container');
    if (!entries.length) {
        container.innerHTML = '<div class="empty-state">No dead-lettered jobs in this queue. 🎉</div>';
        return;
    }
    container.innerHTML = `
        <table>
            <thead>
                <tr>
                    <th>Job ID</th><th>Type</th><th>Attempts</th><th>Reason</th><th>Moved At</th><th>Retried Before</th><th></th>
                </tr>
            </thead>
            <tbody>
                ${entries.map(dlqRowHtml).join('')}
            </tbody>
        </table>
    `;
}

function dlqRowHtml(entry) {
    return `
        <tr id="dlq-row-${entry.jobId}">
            <td>${entry.jobId}</td>
            <td>${entry.jobType}</td>
            <td>${entry.attemptCount}/${entry.maxAttempts}</td>
            <td class="small" style="max-width:280px;">${escapeHtml(entry.reason || '')}</td>
            <td>${formatDate(entry.movedAt)}</td>
            <td>${entry.retriedManually ? '<span class="badge badge-paused">Yes</span>' : '<span class="small">No</span>'}</td>
            <td><button class="secondary" onclick="retryJob(${entry.jobId}, this)">Retry</button></td>
        </tr>
    `;
}

async function retryJob(jobId, buttonEl) {
    buttonEl.disabled = true;
    buttonEl.textContent = 'Retrying…';
    try {
        await apiFetch(`/api/jobs/${jobId}/retry`, { method: 'POST' });
        const row = document.getElementById(`dlq-row-${jobId}`);
        if (row) {
            row.style.transition = 'opacity 0.3s';
            row.style.opacity = '0';
            setTimeout(() => loadDlq(), 300); // reload so the (now-QUEUED) job cleanly disappears
        }
    } catch (err) {
        alert(`Retry failed: ${err.message}`);
        buttonEl.disabled = false;
        buttonEl.textContent = 'Retry';
    }
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