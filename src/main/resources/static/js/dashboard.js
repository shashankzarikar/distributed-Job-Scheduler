requireAuth();

let currentProjectId = null;
let currentProjectRole = null;

// ---------- Init ----------
document.addEventListener('DOMContentLoaded', () => {
    const user = getUser();
    document.getElementById('user-email').textContent = user ? user.email : '';
    loadProjects();
});

// ---------- Projects ----------
async function loadProjects() {
    const container = document.getElementById('projects-list');
    container.innerHTML = '<div class="empty-state">Loading projects…</div>';
    try {
        const projects = await apiFetch('/api/projects');
        renderProjects(projects);
    } catch (err) {
        container.innerHTML = `<div class="error-box">${escapeHtml(err.message)}</div>`;
    }
}

function renderProjects(projects) {
    const container = document.getElementById('projects-list');
    if (!projects.length) {
        container.innerHTML = '<div class="empty-state">No projects yet — create your first one above.</div>';
        return;
    }
    container.innerHTML = projects.map(p => `
        <div class="card" style="cursor:pointer;" onclick="selectProject(${p.id}, '${escapeHtml(p.name)}', '${p.yourRole}')">
            <div class="card-header">
                <h3>${escapeHtml(p.name)}</h3>
                <span class="badge badge-${p.yourRole.toLowerCase() === 'owner' ? 'completed' : 'queued'}">${p.yourRole}</span>
            </div>
            <div class="meta">Created ${formatDate(p.createdAt)}</div>
        </div>
    `).join('');
}

function toggleCreateProject() {
    const form = document.getElementById('create-project-form');
    form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

document.getElementById('create-project-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = document.getElementById('new-project-name').value;
    const errBox = document.getElementById('create-project-error');
    errBox.style.display = 'none';
    try {
        await apiFetch('/api/projects', { method: 'POST', body: JSON.stringify({ name }) });
        document.getElementById('new-project-name').value = '';
        toggleCreateProject();
        loadProjects();
    } catch (err) {
        errBox.textContent = err.message;
        errBox.style.display = 'block';
    }
});

// ---------- Project detail (queues + members) ----------
function selectProject(id, name, role) {
    currentProjectId = id;
    currentProjectRole = role;
    document.getElementById('project-detail').style.display = 'block';
    document.getElementById('project-detail-name').textContent = name;
    document.getElementById('create-queue-btn').style.display = (role === 'OWNER' || role === 'MEMBER') ? 'inline-block' : 'none';
    document.getElementById('add-member-btn').style.display = role === 'OWNER' ? 'inline-block' : 'none';
    showTabPanel('queues');
    loadQueues(id);
    document.getElementById('project-detail').scrollIntoView({ behavior: 'smooth' });
}

function showTabPanel(tab) {
    document.getElementById('tab-queues').classList.toggle('active', tab === 'queues');
    document.getElementById('tab-members').classList.toggle('active', tab === 'members');
    document.getElementById('queues-panel').style.display = tab === 'queues' ? 'block' : 'none';
    document.getElementById('members-panel').style.display = tab === 'members' ? 'block' : 'none';
    if (tab === 'members') loadMembers(currentProjectId);
}

// ---------- Queues ----------
async function loadQueues(projectId) {
    const container = document.getElementById('queues-list');
    container.innerHTML = '<div class="empty-state">Loading queues…</div>';
    try {
        const queues = await apiFetch(`/api/projects/${projectId}/queues`);
        renderQueues(queues);
    } catch (err) {
        container.innerHTML = `<div class="error-box">${escapeHtml(err.message)}</div>`;
    }
}

function renderQueues(queues) {
    const container = document.getElementById('queues-list');
    if (!queues.length) {
        container.innerHTML = '<div class="empty-state">No queues in this project yet.</div>';
        return;
    }
    container.innerHTML = queues.map(q => `
        <div class="card" id="queue-card-${q.id}">
            <div class="card-header">
                <h3>${escapeHtml(q.name)}</h3>
                <span class="badge badge-${q.status.toLowerCase()}">${q.status}</span>
            </div>
            <div class="meta">Priority ${q.priority} · Concurrency ${q.concurrencyLimit}</div>
            <div id="queue-stats-${q.id}" class="stat-row" style="margin:10px 0 6px;"></div>
            <div style="display:flex; gap:8px; margin-top:10px;">
                <button class="secondary" onclick="openQueue(${q.id}, '${escapeHtml(q.name)}')">Open</button>
                ${q.status === 'ACTIVE'
                    ? `<button class="secondary" onclick="toggleQueueStatus(${q.id}, 'pause')">Pause</button>`
                    : `<button class="secondary" onclick="toggleQueueStatus(${q.id}, 'resume')">Resume</button>`}
            </div>
        </div>
    `).join('');

    queues.forEach(q => loadQueueStats(q.id));
}

async function loadQueueStats(queueId) {
    const el = document.getElementById(`queue-stats-${queueId}`);
    if (!el) return;
    try {
        const stats = await apiFetch(`/api/queues/${queueId}/stats`);
        el.innerHTML = `
            <div class="stat-box" style="padding:6px 10px; min-width:auto;"><span class="num" style="font-size:14px;">${stats.queuedCount}</span> <span class="label">Queued</span></div>
            <div class="stat-box" style="padding:6px 10px; min-width:auto;"><span class="num" style="font-size:14px;">${stats.runningCount}</span> <span class="label">Running</span></div>
            <div class="stat-box" style="padding:6px 10px; min-width:auto;"><span class="num" style="font-size:14px;">${stats.completedCount}</span> <span class="label">Done</span></div>
            <div class="stat-box" style="padding:6px 10px; min-width:auto;"><span class="num" style="font-size:14px;">${stats.deadLetterCount}</span> <span class="label">DLQ</span></div>
        `;
    } catch (err) {
        el.innerHTML = `<span class="small">stats unavailable</span>`;
    }
}

async function toggleQueueStatus(queueId, action) {
    try {
        await apiFetch(`/api/queues/${queueId}/${action}`, { method: 'PATCH' });
        loadQueues(currentProjectId);
    } catch (err) {
        alert(err.message);
    }
}

function openQueue(queueId, queueName) {
    window.location.href = `/queue.html?queueId=${queueId}&queueName=${encodeURIComponent(queueName)}&projectId=${currentProjectId}`;
}

function toggleCreateQueue() {
    const form = document.getElementById('create-queue-form');
    form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

document.getElementById('create-queue-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const errBox = document.getElementById('create-queue-error');
    errBox.style.display = 'none';
    const body = {
        name: document.getElementById('new-queue-name').value,
        priority: parseInt(document.getElementById('new-queue-priority').value || '0', 10),
        concurrencyLimit: parseInt(document.getElementById('new-queue-concurrency').value || '5', 10)
    };
    try {
        await apiFetch(`/api/projects/${currentProjectId}/queues`, { method: 'POST', body: JSON.stringify(body) });
        document.getElementById('create-queue-form').reset();
        toggleCreateQueue();
        loadQueues(currentProjectId);
    } catch (err) {
        errBox.textContent = err.message;
        errBox.style.display = 'block';
    }
});

// ---------- Members ----------
async function loadMembers(projectId) {
    const container = document.getElementById('members-list');
    container.innerHTML = '<div class="empty-state">Loading members…</div>';
    try {
        const members = await apiFetch(`/api/projects/${projectId}/members`);
        renderMembers(members);
    } catch (err) {
        container.innerHTML = `<div class="error-box">${escapeHtml(err.message)}</div>`;
    }
}

function renderMembers(members) {
    const container = document.getElementById('members-list');
    container.innerHTML = `
        <table>
            <thead><tr><th>Name</th><th>Email</th><th>Role</th></tr></thead>
            <tbody>
                ${members.map(m => `
                    <tr>
                        <td>${escapeHtml(m.name)}</td>
                        <td>${escapeHtml(m.email)}</td>
                        <td><span class="badge badge-${m.role.toLowerCase() === 'owner' ? 'completed' : 'queued'}">${m.role}</span></td>
                    </tr>
                `).join('')}
            </tbody>
        </table>
    `;
}

function toggleAddMember() {
    const form = document.getElementById('add-member-form');
    form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

document.getElementById('add-member-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const errBox = document.getElementById('add-member-error');
    errBox.style.display = 'none';
    const body = {
        email: document.getElementById('new-member-email').value,
        role: document.getElementById('new-member-role').value
    };
    try {
        await apiFetch(`/api/projects/${currentProjectId}/members`, { method: 'POST', body: JSON.stringify(body) });
        document.getElementById('add-member-form').reset();
        toggleAddMember();
        loadMembers(currentProjectId);
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