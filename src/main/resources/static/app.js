const definitionId = 'customer-email-normalization';
let definition = null;
let runs = [];
let selectedRunId = null;
let parentFingerprint = null;

const $ = (id) => document.getElementById(id);
const api = async (path, options = {}) => {
  const response = await fetch(path, {
    headers: options.body && typeof options.body === 'string'
      ? {'Content-Type': 'application/json'} : undefined,
    ...options
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
};
const toast = (message) => {
  $('toast').textContent = message;
  $('toast').classList.add('show');
  setTimeout(() => $('toast').classList.remove('show'), 3200);
};
const badge = (value) => `<span class="badge ${value}">${value}</span>`;
const pretty = (value) => JSON.stringify(value, null, 2);

async function loadAll() {
  await refreshDefinitions();
  await refreshRuns();
  await refreshEvidence();
  const health = await api('/api/health');
  $('health').textContent = `在线 · ${health.ruleVersion}`;
  $('health').className = 'pill ok';
}

async function refreshDefinitions() {
  const versions = await api(`/api/definitions/${definitionId}/versions`);
  definition = await api(`/api/definitions/${definitionId}`);
  parentFingerprint = definition.fingerprint;
  $('definitionEditor').value = pretty(definition.content);
  $('definitionMeta').innerHTML = `版本 v${definition.version} · 指纹 <code>${definition.fingerprint}</code><br>规则 ${definition.ruleVersion}`;
  $('definitionVersion').innerHTML = versions
    .slice().reverse().map(item => `<option value="${item.fingerprint}" ${item.version === definition.version ? 'selected' : ''}>v${item.version} ${item.fingerprint.slice(0, 12)}</option>`)
    .join('');
  refreshFaultPoints();
}

function refreshFaultPoints() {
  const points = [];
  for (const step of definition.content.steps || []) {
    for (const point of step.failurePoints || []) points.push(point);
  }
  const current = $('faultPoint').value;
  $('faultPoint').innerHTML = '<option value="">无故障，完整迁移</option>' + points
    .map(point => `<option value="${point.id}">${point.id} — ${point.expected}</option>`).join('');
  $('faultPoint').value = current;
}

async function refreshRuns() {
  runs = await api('/api/runs');
  if (!selectedRunId && runs.length) selectedRunId = runs[0].id;
  $('runsBody').innerHTML = runs.map(run => `
    <tr data-id="${run.id}" class="${run.id === selectedRunId ? 'selected' : ''}">
      <td>${escapeHtml(run.name)}</td>
      <td>${badge(run.status)}</td>
      <td>${run.currentStep || '—'}<br><small>${escapeHtml(run.diagnosis || '')}</small></td>
      <td>${run.cursorId ?? '—'}</td>
      <td><code>${run.fingerprint.slice(0, 12)}</code></td>
    </tr>`).join('');
  document.querySelectorAll('#runsBody tr').forEach(row => row.addEventListener('click', async () => {
    selectedRunId = row.dataset.id;
    await loadSelectedRun();
  }));
  if (selectedRunId) await loadSelectedRun();
}

async function loadSelectedRun() {
  const run = await api(`/api/runs/${selectedRunId}`);
  $('runStatus').textContent = pretty(withoutLarge(run));
  $('batches').innerHTML = run.batches.length ? run.batches.map(batch => `
    <div class="item"><strong>#${batch.batch_no ?? batch.batchNo} · ${batch.stepId}</strong>
      源行 ${batch.sourceRows} / 插入 ${batch.insertedRows} · 末位主键 ${batch.lastId} ${batch.recovered ? '· 恢复提交' : ''}
      <br><small>${batch.checksum}</small></div>`).join('') : '<small>尚无批次</small>';
  $('verifications').innerHTML = run.verifications.length ? run.verifications.map(item => `
    <div class="item"><strong>${badge(item.status)} ${item.stepId}</strong>
      ${escapeHtml(item.summary || '')}<br><small>规则 ${item.ruleVersion}<br>来源 ${item.sourceFingerprint}</small></div>`).join('') : '<small>尚无校验派生物</small>';
  $('events').innerHTML = run.events.map(event => `
    <div class="event"><span class="seq">#${event.seq}</span>${event.type}
      ${event.stepId ? '· ' + event.stepId : ''} ${event.phase ? '· ' + event.phase : ''}
      <br><small>${event.checksum}</small></div>`).join('');
  $('evidenceText').value || refreshEvidenceJson();
  refreshFaultPoints();
}

function withoutLarge(run) {
  const copy = {...run};
  delete copy.definition;
  delete copy.events;
  return copy;
}

async function refreshEvidenceJson() {
  if (!selectedRunId) return;
  const evidence = await api(`/api/runs/${selectedRunId}/evidence`);
  $('evidenceText').value = pretty(evidence);
}

async function refreshEvidence() {
  const items = await api('/api/evidence');
  $('evidenceList').innerHTML = items.length ? items.map(item => `
    <div class="item"><strong>${badge(item.analysisStatus)} ${item.kind}</strong>
      来源 <code>${(item.sourceFingerprint || '').slice(0, 16)}</code><br>
      <small>${item.receivedSha256}<br>${escapeHtml(item.analysis?.observedRunStatus || '')}</small></div>`).join('') : '<small>尚无收到的证据</small>';
}

async function selectedFingerprint() {
  const latest = await api(`/api/definitions/${definitionId}`);
  return latest.fingerprint;
}

$('loadDefinition').addEventListener('click', async () => {
  definition = await api(`/api/definitions/by-fingerprint/${$('definitionVersion').value}`);
  parentFingerprint = definition.fingerprint;
  $('definitionEditor').value = pretty(definition.content);
  $('definitionMeta').innerHTML = `版本 v${definition.version} · 指纹 <code>${definition.fingerprint}</code>`;
  refreshFaultPoints();
});

$('newBranch').addEventListener('click', async () => {
  try {
    const created = await api(`/api/definitions/${definitionId}/branches?parentFingerprint=${parentFingerprint}`, {
      method: 'POST', body: $('definitionEditor').value
    });
    toast(`已创建分支 v${created.version}`);
    await refreshDefinitions();
    $('conflictBox').classList.add('hidden');
  } catch (error) {
    $('conflictBox').classList.remove('hidden');
    $('conflictBox').innerHTML = `<strong>检测到冲突</strong><p>${escapeHtml(error.message)}</p>
      <p class="muted">后到的一方必须重新载入新版本，把自己的字段修改合并进去，再基于最新父指纹提交。</p>
      <button onclick="document.getElementById('loadDefinition').click()">重新载入并合并</button>`;
  }
});

$('createRun').addEventListener('click', async () => {
  const run = await api('/api/runs', {method: 'POST', body: JSON.stringify({
    fingerprint: await selectedFingerprint(), name: $('runName').value || null
  })});
  selectedRunId = run.id;
  toast('已创建隔离 schema：' + run.schemaName);
  await refreshRuns();
  await refreshEvidenceJson();
});

$('startRun').addEventListener('click', async () => {
  if (!selectedRunId) return toast('请先创建运行');
  await api(`/api/runs/${selectedRunId}/start`, {method: 'POST', body: JSON.stringify({
    faultPoint: $('faultPoint').value || null, exitMode: $('exitMode').value
  })});
  if ($('exitMode').value === 'halt') toast('真实进程将退出；请重启服务后查看恢复判定');
  else await postCrashRefresh();
});
$('resumeRun').addEventListener('click', async () => {
  await api(`/api/runs/${selectedRunId}/resume`, {method: 'POST'});
  await postCrashRefresh();
});
$('rollbackRun').addEventListener('click', async () => {
  await api(`/api/runs/${selectedRunId}/rollback`, {method: 'POST'});
  await postCrashRefresh();
});
$('recoverRun').addEventListener('click', async () => {
  await api(`/api/runs/${selectedRunId}/recover`, {method: 'POST'});
  await postCrashRefresh();
});
$('insertRow').addEventListener('click', async () => {
  const result = await api(`/api/runs/${selectedRunId}/test-insert`, {method: 'POST', body: '{}'});
  toast(`新行已写入；双写观察=${result.dualWriteObserved}`);
  await postCrashRefresh();
});
$('runSuite').addEventListener('click', async () => {
  const result = await api('/api/rehearsals', {method: 'POST', body: JSON.stringify({
    fingerprint: await selectedFingerprint()
  })});
  $('suiteResult').innerHTML = `<div class="item"><strong>${badge(result.status)} 全套故障点 ${result.results.length} 个决策</strong>
    <small>${result.results.filter(item => item.passed).length}/${result.results.length} 通过</small></div>`;
  await refreshRuns();
});
$('importEvidence').addEventListener('click', async () => {
  const item = await api('/api/evidence', {method: 'POST', body: $('evidenceText').value});
  toast('证据已不可变接收：' + item.analysisStatus);
  await refreshEvidence();
});
$('copyEvidence').addEventListener('click', async () => {
  await refreshEvidenceJson();
  await navigator.clipboard.writeText($('evidenceText').value);
  toast('证据 JSON 已复制');
});

async function postCrashRefresh() {
  await refreshRuns();
  await refreshEvidenceJson();
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));
}
loadAll().catch(error => {
  $('health').textContent = '离线：' + error.message;
});
