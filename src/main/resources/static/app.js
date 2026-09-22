const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
let state = { families: [], selectedFamily: null, selectedFp: null, runs: [], selectedRun: null, sample: null };

function toast(msg, isErr) {
  const t = $('#toast');
  t.textContent = msg;
  t.style.borderColor = isErr ? '#b3414b' : '#4b6daf';
  t.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.add('hidden'), 5000);
}

async function api(path, opts = {}) {
  const res = await fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) {
    const err = new Error(data.message || ('HTTP ' + res.status));
    err.body = data;
    err.status = res.status;
    throw err;
  }
  return data;
}

function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
const fp8 = (fp) => fp ? fp.slice(0, 8) : '';

$$('.tabs button').forEach((btn) => btn.addEventListener('click', () => {
  $$('.tabs button').forEach((b) => b.classList.toggle('active', b === btn));
  $$('.tab').forEach((t) => t.classList.toggle('active', t.id === 'tab-' + btn.dataset.tab));
  refreshAll();
}));

async function refreshAll() {
  await loadFamilies();
  await loadRuns();
  await loadEvidence();
}

// ---------- definitions ----------

async function loadFamilies() {
  state.families = await api('/families');
  $('#family-list').innerHTML = state.families.map((f) => `
    <div class="card ${state.selectedFamily === f.familyId ? 'selected' : ''}" data-fam="${esc(f.familyId)}">
      <div><b>${esc(f.name)}</b></div>
      <div class="small">${esc(f.familyId)} · ${f.revisionCount} 个版本 · head ${fp8(f.headFingerprint)}</div>
    </div>`).join('');
  $$('#family-list .card').forEach((c) => c.addEventListener('click', () => selectFamily(c.dataset.fam)));
  const sel = $('#run-def-select');
  sel.innerHTML = state.families.map((f) =>
    `<option value="${esc(f.headFingerprint)}">${esc(f.name)} (${fp8(f.headFingerprint)})</option>`).join('');
  const ex = $('#export-select');
  ex.innerHTML = state.families.map((f) =>
    `<option value="${esc(f.headFingerprint)}">${esc(f.name)}</option>`).join('');
}

async function selectFamily(familyId) {
  state.selectedFamily = familyId;
  const fam = await api('/families/' + familyId);
  state.selectedFp = fam.headFingerprint;
  $('#editor-title').textContent = fam.name + ' — 版本历史';
  const head = fam.revisions.find((r) => r.fingerprint === fam.headFingerprint);
  $('#def-family').value = fam.familyId;
  $('#def-base').value = fam.headFingerprint;
  $('#def-json').value = JSON.stringify(head.spec, null, 2);
  $('#conflict-box').classList.add('hidden');
  const cards = fam.revisions.slice().reverse().map((r) => `
    <div class="card ${r.fingerprint === fam.headFingerprint ? 'selected' : ''}">
      <div>${r.isHead ? '★ head' : '└ 分支/旧版'} <span class="fp">${fp8(r.fingerprint)}</span></div>
      <div class="small">父 ${fp8(r.parentFingerprint) || '—'} · ${esc(r.createdAt)}</div>
      <button class="secondary" data-load-fp="${esc(r.fingerprint)}">载入此版本</button>
    </div>`).join('');
  $('#family-list').innerHTML = `<button class="secondary" id="btn-back-fams">← 返回家族列表</button>` + cards;
  $('#btn-back-fams').addEventListener('click', async () => {
    state.selectedFamily = null;
    await loadFamilies();
    $('#editor-title').textContent = '定义编辑器';
  });
  $$('[data-load-fp]').forEach((b) => b.addEventListener('click', async (e) => {
    e.stopPropagation();
    const r = await api('/definitions/' + b.dataset.loadFp);
    $('#def-base').value = fam.headFingerprint;
    $('#def-json').value = JSON.stringify(r.spec, null, 2);
    state.selectedFp = r.fingerprint;
  }));
}

$('#btn-submit-def').addEventListener('click', () => submitDef(false));
$('#btn-branch-def').addEventListener('click', () => submitDef(true));
$('#btn-load-sample').addEventListener('click', async () => {
  $('#def-json').value = JSON.stringify(state.sample, null, 2);
});

async function submitDef(forceBranch) {
  $('#conflict-box').classList.add('hidden');
  setMsg('def', '', false);
  let specObj;
  try {
    specObj = JSON.parse($('#def-json').value);
  } catch (e) {
    setMsg('def', 'JSON 解析失败: ' + e.message, true);
    return;
  }
  try {
    const data = await api('/definitions', {
      method: 'POST',
      body: JSON.stringify({
        specJson: JSON.stringify(specObj),
        familyId: $('#def-family').value || null,
        baseFingerprint: $('#def-base').value || null,
        forceBranch
      })
    });
    setMsg('def', (data.created ? '已创建家族 ' : data.branch ? '已创建分支（head 未移动） ' : '版本已存在/head 已推进 ')
      + data.familyId + ' fp=' + fp8(data.fingerprint), false);
    await loadFamilies();
    if (data.familyId) {
      await selectFamily(data.familyId);
    }
  } catch (e) {
    if (e.status === 409) {
      showConflict(e.body);
    } else {
      setMsg('def', e.message, true);
    }
  }
}

async function showConflict(body) {
  $('#conflict-box').classList.remove('hidden');
  $('#conflict-base').textContent = body.expectedHead;
  $('#conflict-head').textContent = body.actualHead;
  try {
    const current = await api('/definitions/' + body.actualHead);
    $('#conflict-current').textContent = JSON.stringify(current.spec, null, 2);
  } catch (e) {
    $('#conflict-current').textContent = '无法载入对方版本: ' + e.message;
  }
  setMsg('def', '后到提交检测到冲突：你可以对比对方内容，在编辑器中重新合并后再提交（推进 head 或保存分支）', true);
}

function setMsg(kind, text, isErr) {
  const el = kind === 'def' ? $('#def-msg') : $('#evidence-msg');
  el.textContent = text;
  el.className = 'msg ' + (text ? (isErr ? 'err' : 'ok') : '');
}

// ---------- runs ----------

async function loadRuns() {
  state.runs = await api('/runs');
  $('#run-list').innerHTML = state.runs.map((r) => `
    <div class="card ${state.selectedRun === r.runUid ? 'selected' : ''}" data-run="${esc(r.runUid)}">
      <div><span class="badge ${r.status}">${r.status}</span>
        ${r.armedFault ? '💥 ' + esc(r.armedFault) : ''}</div>
      <div class="small">${esc(r.runUid)} · def ${fp8(r.fingerprint)} · ${esc(r.createdAt)}</div>
      ${r.error ? '<div class="small" style="color:#ff9aa8">' + esc(r.error) + '</div>' : ''}
    </div>`).join('') || '<div class="hint">暂无运行</div>';
  $$('#run-list .card').forEach((c) => c.addEventListener('click', () => openRun(c.dataset.run)));
}

let currentDetail = null;

$('#btn-create-run').addEventListener('click', async () => {
  try {
    const r = await api('/runs', {
      method: 'POST',
      body: JSON.stringify({ fingerprint: $('#run-def-select').value, armedFault: $('#run-fault-select').value || null })
    });
    state.selectedRun = r.runUid;
    await loadRuns();
    renderDetail(r);
    toast('已创建隔离副本与快照');
  } catch (e) {
    toast(e.message, true);
  }
});

async function openRun(uid) {
  state.selectedRun = uid;
  await loadRuns();
  const detail = await api('/runs/' + uid);
  currentDetail = detail;
  renderDetail(detail);
}

function renderDetail(r) {
  currentDetail = r;
  const spec = r.spec || {};
  const faults = [];
  (spec.steps || []).forEach((s) => (s.faults || []).forEach((f) =>
    faults.push({ key: s.id + '#' + f.id, label: s.id + ' / ' + f.label + ' [' + f.phase + ']' })));
  if ($('#run-fault-select').dataset.bound !== r.fingerprint) {
    $('#run-fault-select').innerHTML = '<option value="">不注入故障</option>'
      + '<option value="__finalize__">完成判定通过后、写 RUN_COMPLETED 前崩溃</option>'
      + faults.map((f) => `<option value="${esc(f.key)}">${esc(f.label)}</option>`).join('');
    $('#run-fault-select').dataset.bound = r.fingerprint;
  }
  $('#run-title').textContent = '运行 ' + r.runUid;
  const canAdvance = ['CREATED', 'RUNNING'].includes(r.status);
  const canRollback = ['CREATED', 'RUNNING', 'HALTED', 'FAILED'].includes(r.status);
  const halted = r.status === 'HALTED';
  let html = `
    <div class="kv">
      <b>状态</b><span class="badge ${r.status}">${r.status}</span>
      <b>定义指纹</b><span class="fp">${esc(r.fingerprint)}</span>
      <b>故障点</b><span>${esc(r.armedFault || '—')}</span>
      <b>终局事件</b><span>${esc(r.terminalEvent || '—')}</span>
      <b>沙箱</b><span class="small">${esc('独立 H2 schema，生命周期随运行；不影响其它副本')}</span>
    </div>
    ${r.error ? '<div class="msg err">' + esc(r.error) + '</div>' : ''}
    <div class="row">
      <button id="d-advance" ${canAdvance ? '' : 'disabled'}>继续推进</button>
      <button id="d-rollback" class="danger" ${canRollback ? '' : 'disabled'}>从当前点回滚</button>
      <button id="d-refresh" class="secondary">刷新</button>
      <button id="d-rescan" class="secondary">重新扫描恢复</button>
    </div>`;
  if (halted) {
    html += `<div class="conflict">
      <h3>⛔ 无法证明安全，已停住（禁止凭步骤名重跑）</h3>
      <p>请检查日志与数据探针结论，做出人工决定：</p>
      <div class="row">
        <input id="decision-note" placeholder="裁定依据，例如：已在外部系统确认通知未送达 / 已手工撤销">
        <button id="d-commit">确认提交（我已核实副作用生效）</button>
        <button id="d-compensate" class="danger">补偿并回滚</button>
      </div></div>`;
  }
  html += '<h3>步骤</h3>' + r.steps.map(renderStep).join('');
  html += '<h3>每批回填与校验摘要</h3><div>' + renderBatches(r) + '</div>';
  html += '<h3>旧读路径 / 新读路径</h3><div>' + renderReadChecks(r) + '</div>';
  html += '<h3>可恢复日志（WAL）</h3><div class="journal"><table><tr><th>事件</th><th>步骤</th><th>载荷</th></tr>'
    + r.journal.map((j) => `<tr class="${j.event === 'RECOVERY_VERDICT' ? 'verdict-row' : ''}">
        <td>${j.event === 'RECOVERY_VERDICT' ? '<b class="verdict-' + verdictOf(j.payload) + '">' + verdictOf(j.payload) + '</b><br><span class="small">RECOVERY_VERDICT</span>' : esc(j.event)}</td>
        <td>${esc(j.stepId || '')}</td>
        <td><pre class="payload">${esc(pretty(j.payload))}</pre></td></tr>`).join('')
    + '</table></div>';
  $('#run-detail').innerHTML = html;
  $('#d-advance')?.addEventListener('click', () => act('advance'));
  $('#d-rollback')?.addEventListener('click', () => act('rollback'));
  $('#d-refresh')?.addEventListener('click', () => openRun(r.runUid));
  $('#d-rescan')?.addEventListener('click', async () => {
    await api('/recovery/scan', { method: 'POST' });
    await openRun(r.runUid);
  });
  $('#d-commit')?.addEventListener('click', () => decide('COMMIT'));
  $('#d-compensate')?.addEventListener('click', () => decide('COMPENSATE'));
  $$('[data-window-write]').forEach((b) => b.addEventListener('click', () => doWindowWrite(b, r)));
  $$('[data-close-window]').forEach((b) => b.addEventListener('click', async () => {
    await api(`/runs/${r.runUid}/steps/${b.dataset.closeWindow}/close-window`, { method: 'POST' });
    await openRun(r.runUid);
  }));
}

function verdictOf(payload) {
  try { return JSON.parse(payload).status || ''; } catch (e) { return ''; }
}
function pretty(payload) {
  try { return JSON.stringify(JSON.parse(payload), null, 2); } catch (e) { return payload || ''; }
}

function renderStep(s) {
  let extra = '';
  if (s.type === 'BACKFILL') {
    extra = `<div class="small">稳定主键游标：<code>${esc(s.cursor || '0')}</code></div>`;
  }
  if (s.type === 'DUAL_WRITE' && s.status === 'WINDOW_OPEN') {
    extra += `<div class="row">
      <input id="ww-pk" placeholder="主键 id（如 6001）" value="6001" style="width:140px">
      <input id="ww-cid" placeholder="customer_id" value="105" style="width:110px">
      <input id="ww-amt" placeholder="amount" value="88.00" style="width:110px">
      <select id="ww-apply"><option value="BOTH">双写两表</option>
        <option value="OLD">仅旧表（晚到行）</option><option value="NEW">仅新表</option></select>
      <button class="secondary" data-window-write="${esc(s.stepId)}">写入</button>
      <button data-close-window="${esc(s.stepId)}" class="danger">对账并关窗</button>
    </div>`;
  }
  return `<div class="step"><h4><span class="badge ${s.status}">${s.status}</span>
      ${esc(s.stepId)} <span class="small">[${s.type}]</span></h4>${extra}</div>`;
}

function renderBatches(r) {
  const rows = r.steps.filter((s) => s.type === 'BACKFILL').flatMap((s) =>
    s.batches.map((b) => ({ step: s.stepId, ...b })));
  if (!rows.length) return '<div class="hint">尚无批次</div>';
  return '<table><tr><th>步骤</th><th>#</th><th>pk 区间</th><th>行数</th><th>批次校验摘要</th></tr>'
    + rows.map((b) => `<tr><td>${esc(b.step)}</td><td>${b.batchNo}</td>
      <td>${esc(b.pkFrom || '∅')} → ${esc(b.pkTo || '∅')}</td><td>${b.rows}</td>
      <td class="small">${esc(b.checksum || '')}</td></tr>`).join('') + '</table>';
}

function renderReadChecks(r) {
  if (!r.readChecks || !r.readChecks.length) return '<div class="hint">完成判定时产生</div>';
  return r.readChecks.map((c) => `<div class="step"><h4>${c.matched ? '✅' : '❌'} ${esc(c.label)}</h4>
    <div class="grid2" style="grid-template-columns:1fr 1fr">
      <div><div class="small">旧读路径</div><pre class="payload">${esc(c.oldPath)}</pre></div>
      <div><div class="small">新读路径</div><pre class="payload">${esc(c.newPath)}</pre></div>
    </div></div>`).join('');
}

async function act(name) {
  try {
    await api(`/runs/${currentDetail.runUid}/${name}`, { method: 'POST' });
    await openRun(currentDetail.runUid);
  } catch (e) {
    await openRun(currentDetail.runUid);
    toast(e.message, true);
  }
}

async function decide(decision) {
  try {
    await api(`/runs/${currentDetail.runUid}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision, note: $('#decision-note')?.value || '' })
    });
    await openRun(currentDetail.runUid);
  } catch (e) {
    toast(e.message, true);
    await openRun(currentDetail.runUid);
  }
}

async function doWindowWrite(btn, r) {
  const row = {
    id: Number($('#ww-pk').value),
    customer_id: Number($('#ww-cid').value),
    amount: $('#ww-amt').value,
    status: 'PAID'
  };
  try {
    const res = await api(`/runs/${r.runUid}/steps/${btn.dataset.windowWrite}/window-write`, {
      method: 'POST',
      body: JSON.stringify({ applyTo: $('#ww-apply').value, row })
    });
    toast('写入完成，新旧一致=' + res.consistent + ' pk=' + res.pk);
    await openRun(r.runUid);
  } catch (e) {
    toast(e.message, true);
  }
}

// ---------- evidence ----------

async function loadEvidence() {
  const data = await api('/evidence');
  $('#evidence-list').innerHTML = (data.receipts || []).map((r) => `
    <div class="card"><div><b>${fp8(r.fingerprint)}</b> <span class="small">${esc(r.receivedAt)}</span></div>
    <div class="small">entry ${fp8(r.entryHash)} ← prev ${fp8(r.prevHash) || 'GENESIS'}</div></div>`).join('')
    || '<div class="hint">尚未接收证据</div>';
  $('#report-list').innerHTML = (data.reports || []).map((rp) => {
    const fr = (rp.reportJson.faultResults || []).map((f) =>
      `<tr><td>${esc(f.faultKey)}</td><td>${esc(f.expectVerdict)}</td>
      <td class="${f.passed ? '' : 'verdict-UNCERTAIN'}">${esc(f.observed)}</td>
      <td>${f.passed ? '✅' : '❌'}</td></tr>`).join('');
    return `<div class="card"><div>${rp.passed ? '✅ 验证通过' : '❌ 验证失败'}
      <span class="small">规则 ${esc(rp.ruleVersion)} · 来源 ${fp8(rp.sourceFingerprint)} · ${esc(rp.createdAt)}</span></div>
      ${rp.reportJson.problems && rp.reportJson.problems.length
        ? '<pre class="payload">' + esc(rp.reportJson.problems.join('\n')) + '</pre>' : ''}
      ${fr ? '<table><tr><th>故障点</th><th>预期</th><th>观察</th><th></th></tr>' + fr + '</table>' : ''}
    </div>`;
  }).join('') || '<div class="hint">尚无报告</div>';
}

$('#btn-export').addEventListener('click', async () => {
  const fp = $('#export-select').value;
  const text = await api('/evidence/export/' + fp, { headers: {} });
  $('#evidence-in').value = typeof text === 'string' ? text : JSON.stringify(text, null, 2);
  toast('证据已导出到导入框，可保存为文件后在任意实例重新载入');
});

$('#btn-import').addEventListener('click', async () => {
  setMsg('evidence', '', false);
  try {
    const res = await api('/evidence/import', { method: 'POST', body: $('#evidence-in').value });
    setMsg('evidence',
      (res.passed ? '✅ 证据验证通过' : '❌ 证据验证失败（原始证据仍已只追加留档）')
      + (res.duplicate ? '；该证据此前已接收（幂等）' : ''), !res.passed);
    await loadEvidence();
  } catch (e) {
    setMsg('evidence', e.message, true);
  }
});

// ---------- boot ----------

(async function init() {
  try {
    const sampleText = await (await fetch('/sample-definition.json')).text();
    state.sample = JSON.parse(sampleText);
  } catch (e) { /* ignore */ }
  await refreshAll();
  if (state.families.length) {
    state.selectedFp = state.families[0].headFingerprint;
  }
})();
