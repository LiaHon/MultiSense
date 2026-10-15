'use strict';
const $ = id => document.getElementById(id),
  esc = s => String(s ?? '').replace(/[&<>\x22\x27]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '\x22': '&quot;', '\x27': '&#39;' }[c]));
const colors = ['#149689', '#d68a58', '#7b91c7'];

const DEFAULT_LABELS = {
  scenes: ['室内', '室外', '楼梯', '电梯', '其他'],
  actions: ['步行', '静止', '跑步', '乘车', '其他'],
  defaultScene: '室内',
  defaultAction: '步行'
};
let labelConfig = { ...DEFAULT_LABELS };
try {
  const saved = JSON.parse(localStorage.getItem('multisense-labels') || '{}');
  if (Array.isArray(saved.scenes) && saved.scenes.length) labelConfig.scenes = [...new Set(saved.scenes)];
  if (Array.isArray(saved.actions) && saved.actions.length) labelConfig.actions = [...new Set(saved.actions)];
  if (saved.defaultScene && labelConfig.scenes.includes(saved.defaultScene)) labelConfig.defaultScene = saved.defaultScene;
  else labelConfig.defaultScene = labelConfig.scenes[0] || '室内';
  if (saved.defaultAction && labelConfig.actions.includes(saved.defaultAction)) labelConfig.defaultAction = saved.defaultAction;
  else labelConfig.defaultAction = labelConfig.actions[0] || '步行';
} catch {}

function saveLabelsConfig() {
  try { localStorage.setItem('multisense-labels', JSON.stringify(labelConfig)); } catch {}
  updateSettingsDropdowns();
}

let activeScene = labelConfig.defaultScene;
let activeAction = labelConfig.defaultAction;
let pendingScene = labelConfig.defaultScene;
let pendingAction = labelConfig.defaultAction;
let isUserPicking = false;

const STATE_COLORS = [
  '#2a9d8f', '#e76f51', '#457b9d', '#e9c46a', '#8338ec',
  '#3a86ff', '#fb5607', '#06d6a0', '#118ab2', '#073b4c',
  '#6a994e', '#bc6c25', '#d4a373', '#9b5de5', '#00bbf9'
];
function getStateColor(combo) {
  let hash = 0;
  for (let i = 0; i < combo.length; i++) hash = ((hash << 5) - hash + combo.charCodeAt(i)) | 0;
  return STATE_COLORS[Math.abs(hash) % STATE_COLORS.length];
}

let backend = window.MultiSenseNative, state = {}, review = null, tab = 'overview', selected = new Set(), inventoryKey = '', frozen = false, shownRaw = [], pageRows = null, pageOffset = 0, pageHasMore = false, lastSaved = '', lastError = '', previousRecording = false, nextPoll;
let settings = { title: '步行采集', scene: labelConfig.defaultScene, action: labelConfig.defaultAction, pose: '手持固定', hz: 200, gnss: true, wifi: true, ble: true, cell: true };
try {
  settings = { ...settings, ...JSON.parse(localStorage.getItem('multisense-settings') || '{}') };
  if (labelConfig.scenes.includes(settings.scene)) labelConfig.defaultScene = settings.scene;
  if (labelConfig.actions.includes(settings.action)) labelConfig.defaultAction = settings.action;
} catch {}
if (![100,200].includes(Number(settings.hz))) settings.hz=200;

const titles = {
  overview: ['实时总览', '把手机里的每一路信号，看清楚。'],
  sensors: ['传感器', '按设备能力发现，按原始时间记录。'],
  raw: ['原始数据', '每一个测量值，都能追溯到时间和数据源。'],
  history: ['采集记录', '保存一次行走，也保存每一步的证据。']
};

function toast(message) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => $('toast').hidden = true, 4500);
}
window.nativeMessage = toast;

function parse(value, fallback = {}) {
  try { return JSON.parse(value); } catch { return fallback; }
}

function time(s) {
  s = Math.max(0, Math.floor(s || 0));
  return (s >= 3600 ? String(Math.floor(s / 3600)).padStart(2, '0') + ':' : '') +
    String(Math.floor(s / 60) % 60).padStart(2, '0') + ':' +
    String(s % 60).padStart(2, '0');
}

function fmt(n, d = 2) {
  return Number.isFinite(n) ? n.toFixed(d) : '—';
}

function source(type, data = state) {
  const all = data.inventory || [];
  return all.find(o => o.type === type && (data.stats || []).some(s => s.id === o.id && s.count > 0)) || all.find(o => o.type === type);
}

function valuesFor(type, data = state) {
  const src = source(type, data);
  return src ? data.latest?.[src.id]?.values : null;
}

function statFor(type, data = state) {
  const src = source(type, data);
  return src ? (data.stats || []).find(s => s.id === src.id) : null;
}

function displayData() {
  if (!review) return state;
  const m = review.manifest || {}, latest = {};
  for (const [id, points] of Object.entries(review.streams || {}))
    if (points.length) latest[id] = { values: points.at(-1).values };
  return {
    inventory: m.sensors || [],
    stats: m.statistics || [],
    latest,
    streams: review.streams,
    route: review.route,
    raw: review.raw,
    markers: review.markers || [],
    networkStatus: m.networkStatus || {},
    networkCounts: m.networkCounts || {},
    initialState: m.initialState || m.action || '静止',
    states: review.states || [],
    scene: m.scene || '室内',
    action: m.action || '步行',
    duration: m.durationS,
    rows: m.rows,
    location: review.route?.at(-1),
    requestedHz: m.requestedHz,
    label: (m.scene || '室内') + '·' + (m.action || '步行'),
    satellites: []
  };
}

function setTab(name) {
  tab = name;
  for (const key of Object.keys(titles)) $('tab-' + key).hidden = key !== name;
  document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
  $('page-title').textContent = titles[name][0];
  $('page-subtitle').textContent = titles[name][1];
  if (name === 'history') renderHistory();
  if (name === 'raw' && review) loadPage(0);
  if (name === 'sensors') renderSensors(displayData(), true);
  render();
}
document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => setTab(b.dataset.tab));

window.handleBack = () => {
  const open = document.querySelector('dialog[open]');
  if (open) open.close();
  else if (review) exitReview();
  else if (tab !== 'overview') setTab('overview');
  else toast('采集中可将应用切到后台；结束请点击“结束并保存”。');
};

document.querySelector('.brand').onclick = e => { e.preventDefault(); setTab('overview'); };
document.querySelectorAll('.close-dialog').forEach(b => b.onclick = () => b.closest('dialog').close());

function updateSettingsDropdowns() {
  $('input-scene').innerHTML = labelConfig.scenes.map(s => `<option value='${esc(s)}' ${s === labelConfig.defaultScene ? 'selected' : ''}>${esc(s)}</option>`).join('');
  $('input-action').innerHTML = labelConfig.actions.map(a => `<option value='${esc(a)}' ${a === labelConfig.defaultAction ? 'selected' : ''}>${esc(a)}</option>`).join('');
}

$('settings-button').onclick = () => {
  if (state.recording || state.busy || review) { toast('请结束本次采集并返回实时页后调整设置'); return; }
  updateSettingsDropdowns();
  $('input-title').value = settings.title;
  $('input-scene').value = labelConfig.defaultScene;
  $('input-action').value = labelConfig.defaultAction;
  $('input-pose').value = settings.pose;
  $('input-hz').value = settings.hz;
  $('input-gnss').checked = settings.gnss;
  for (const k of ['wifi','ble','cell']) $('input-'+k).checked=settings[k]!==false;
  $('settings-dialog').showModal();
};

$('settings-form').onsubmit = e => {
  e.preventDefault();
  labelConfig.defaultScene = $('input-scene').value;
  labelConfig.defaultAction = $('input-action').value;
  saveLabelsConfig();
  settings = {
    title: $('input-title').value.trim() || '步行采集',
    scene: labelConfig.defaultScene,
    action: labelConfig.defaultAction,
    pose: $('input-pose').value,
    hz: Number($('input-hz').value),
    gnss: $('input-gnss').checked,
    wifi: $('input-wifi').checked,
    ble: $('input-ble').checked,
    cell: $('input-cell').checked
  };
  try { localStorage.setItem('multisense-settings', JSON.stringify(settings)); } catch {}
  $('settings-dialog').close();
  if (!state.recording && !review) {
    activeScene = labelConfig.defaultScene;
    activeAction = labelConfig.defaultAction;
    pendingScene = activeScene;
    pendingAction = activeAction;
  }
  render();
  toast('已保存本次采集设置');
};

$('open-labels-btn').onclick = () => {
  renderLabelsManager();
  $('labels-dialog').showModal();
};

function renderLabelsManager() {
  $('scenes-manager-list').innerHTML = labelConfig.scenes.map((sc, i) => `
    <div class='tag-manager-item'>
      <span>${esc(sc)} ${sc === labelConfig.defaultScene ? '<span class=\'tag-default-badge\'>默认</span>' : ''}</span>
      <div class='tag-manager-actions'>
        ${sc !== labelConfig.defaultScene ? `<button type='button' class='tag-action-btn' data-set-default-scene='${esc(sc)}'>设为默认</button>` : ''}
        <button type='button' class='tag-action-btn' data-edit-scene='${i}'>编辑</button>
        <button type='button' class='tag-action-btn danger' data-del-scene='${i}'>删除</button>
      </div>
    </div>
  `).join('');

  $('actions-manager-list').innerHTML = labelConfig.actions.map((ac, i) => `
    <div class='tag-manager-item'>
      <span>${esc(ac)} ${ac === labelConfig.defaultAction ? '<span class=\'tag-default-badge\'>默认</span>' : ''}</span>
      <div class='tag-manager-actions'>
        ${ac !== labelConfig.defaultAction ? `<button type='button' class='tag-action-btn' data-set-default-action='${esc(ac)}'>设为默认</button>` : ''}
        <button type='button' class='tag-action-btn' data-edit-action='${i}'>编辑</button>
        <button type='button' class='tag-action-btn danger' data-del-action='${i}'>删除</button>
      </div>
    </div>
  `).join('');

  document.querySelectorAll('[data-set-default-scene]').forEach(b => {
    b.onclick = () => {
      labelConfig.defaultScene = b.dataset.setDefaultScene;
      saveLabelsConfig();
      renderLabelsManager();
      render();
    };
  });
  document.querySelectorAll('[data-set-default-action]').forEach(b => {
    b.onclick = () => {
      labelConfig.defaultAction = b.dataset.setDefaultAction;
      saveLabelsConfig();
      renderLabelsManager();
      render();
    };
  });
  document.querySelectorAll('[data-edit-scene]').forEach(b => {
    b.onclick = () => {
      const idx = Number(b.dataset.editScene);
      const oldVal = labelConfig.scenes[idx];
      const newVal = prompt('修改场景名称：', oldVal)?.trim();
      if (!newVal || newVal === oldVal) return;
      if (labelConfig.scenes.includes(newVal)) { toast('场景名称已存在'); return; }
      labelConfig.scenes[idx] = newVal;
      if (labelConfig.defaultScene === oldVal) labelConfig.defaultScene = newVal;
      if (activeScene === oldVal) activeScene = newVal;
      if (pendingScene === oldVal) pendingScene = newVal;
      saveLabelsConfig();
      renderLabelsManager();
      render();
    };
  });
  document.querySelectorAll('[data-edit-action]').forEach(b => {
    b.onclick = () => {
      const idx = Number(b.dataset.editAction);
      const oldVal = labelConfig.actions[idx];
      const newVal = prompt('修改动作名称：', oldVal)?.trim();
      if (!newVal || newVal === oldVal) return;
      if (labelConfig.actions.includes(newVal)) { toast('动作名称已存在'); return; }
      labelConfig.actions[idx] = newVal;
      if (labelConfig.defaultAction === oldVal) labelConfig.defaultAction = newVal;
      if (activeAction === oldVal) activeAction = newVal;
      if (pendingAction === oldVal) pendingAction = newVal;
      saveLabelsConfig();
      renderLabelsManager();
      render();
    };
  });
  document.querySelectorAll('[data-del-scene]').forEach(b => {
    b.onclick = () => {
      if (labelConfig.scenes.length <= 1) { toast('至少保留一个场景类型'); return; }
      const idx = Number(b.dataset.delScene);
      const oldVal = labelConfig.scenes[idx];
      labelConfig.scenes.splice(idx, 1);
      if (labelConfig.defaultScene === oldVal) labelConfig.defaultScene = labelConfig.scenes[0];
      if (activeScene === oldVal) activeScene = labelConfig.defaultScene;
      if (pendingScene === oldVal) pendingScene = labelConfig.defaultScene;
      saveLabelsConfig();
      renderLabelsManager();
      render();
    };
  });
  document.querySelectorAll('[data-del-action]').forEach(b => {
    b.onclick = () => {
      if (labelConfig.actions.length <= 1) { toast('至少保留一个动作类型'); return; }
      const idx = Number(b.dataset.delAction);
      const oldVal = labelConfig.actions[idx];
      labelConfig.actions.splice(idx, 1);
      if (labelConfig.defaultAction === oldVal) labelConfig.defaultAction = labelConfig.actions[0];
      if (activeAction === oldVal) activeAction = labelConfig.defaultAction;
      if (pendingAction === oldVal) pendingAction = labelConfig.defaultAction;
      saveLabelsConfig();
      renderLabelsManager();
      render();
    };
  });
}

$('add-scene-btn').onclick = () => {
  const val = $('new-scene-input').value.trim();
  if (!val) { toast('场景名称不能为空'); return; }
  if (labelConfig.scenes.includes(val)) { toast('场景名称已存在'); return; }
  labelConfig.scenes.push(val);
  $('new-scene-input').value = '';
  saveLabelsConfig();
  renderLabelsManager();
  render();
};

$('add-action-btn').onclick = () => {
  const val = $('new-action-input').value.trim();
  if (!val) { toast('动作名称不能为空'); return; }
  if (labelConfig.actions.includes(val)) { toast('动作名称已存在'); return; }
  labelConfig.actions.push(val);
  $('new-action-input').value = '';
  saveLabelsConfig();
  renderLabelsManager();
  render();
};

function renderStateControls() {
  const isRecording = !!state.recording && !review;
  const isDiff = pendingScene !== activeScene || pendingAction !== activeAction;

  $('active-state-display').textContent = activeScene + ' · ' + activeAction;
  $('pending-state-display').textContent = pendingScene + ' · ' + pendingAction;
  $('pending-box').classList.toggle('synced', !isDiff);

  $('confirm-state-btn').disabled = !isRecording || !isDiff;
  $('cancel-state-btn').disabled = !isRecording || !isDiff;

  const scenesHtml = (labelConfig.scenes || []).map(sc => {
    const isAct = sc === activeScene;
    const isPen = sc === pendingScene;
    let cls = 'chip';
    if (isAct && isPen) cls += ' active pending';
    else if (isAct) cls += ' active';
    else if (isPen) cls += ' pending';
    return `<button type='button' class='${cls}' data-select-scene='${esc(sc)}'>${esc(sc)}</button>`;
  }).join('');
  $('scene-chips').innerHTML = scenesHtml;

  const actionsHtml = (labelConfig.actions || []).map(ac => {
    const isAct = ac === activeAction;
    const isPen = ac === pendingAction;
    let cls = 'chip';
    if (isAct && isPen) cls += ' active pending';
    else if (isAct) cls += ' active';
    else if (isPen) cls += ' pending';
    return `<button type='button' class='${cls}' data-select-action='${esc(ac)}'>${esc(ac)}</button>`;
  }).join('');
  $('action-chips').innerHTML = actionsHtml;

  document.querySelectorAll('[data-select-scene]').forEach(btn => {
    btn.onclick = () => {
      pendingScene = btn.dataset.selectScene;
      isUserPicking = (pendingScene !== activeScene || pendingAction !== activeAction);
      renderStateControls();
    };
  });

  document.querySelectorAll('[data-select-action]').forEach(btn => {
    btn.onclick = () => {
      pendingAction = btn.dataset.selectAction;
      isUserPicking = (pendingScene !== activeScene || pendingAction !== activeAction);
      renderStateControls();
    };
  });
}

$('confirm-state-btn').onclick = () => {
  if (!state.recording || review) return;
  if (pendingScene === activeScene && pendingAction === activeAction) return;
  const payload = { scene: pendingScene, action: pendingAction };
  backend.switchState(JSON.stringify(payload));
  toast('已提交切换：' + pendingScene + ' + ' + pendingAction);
  isUserPicking = false;
  poll();
};

$('cancel-state-btn').onclick = () => {
  pendingScene = activeScene;
  pendingAction = activeAction;
  isUserPicking = false;
  renderStateControls();
};

$('capture-button').onclick = () => {
  if (!backend) { toast('设备尚未就绪'); return; }
  if (review) { exitReview(); return; }
  if (state.recording) {
    backend.stop();
    toast('正在结束并保存完整记录…');
  } else {
    if (!selected.size && !['gnss','wifi','ble','cell'].some(k=>settings[k])) { toast('请至少选择一个数据源'); return; }
    activeScene = labelConfig.defaultScene;
    activeAction = labelConfig.defaultAction;
    pendingScene = activeScene;
    pendingAction = activeAction;
    isUserPicking = false;
    backend.start(JSON.stringify({
      ...settings,
      scene: activeScene,
      action: activeAction,
      initialState: activeAction,
      sensorIds: [...selected]
    }));
  }
  poll();
};

document.querySelectorAll('[data-marker]').forEach(b => b.onclick = () => addMarker({ label: b.dataset.marker }));
function addMarker(o) {
  if (!state.recording || review) { toast('开始采集后才能添加打点'); return; }
  backend.marker(JSON.stringify(o));
  toast('已记录打点：' + o.label);
  poll();
}

$('custom-marker').onclick = () => {
  if (!state.recording || review) { toast('开始采集后才能添加打点'); return; }
  $('marker-dialog').showModal();
};

$('marker-form').onsubmit = e => {
  e.preventDefault();
  const o = { label: $('marker-label').value.trim(), note: $('marker-note').value.trim() },
    lat = $('marker-lat').value,
    lon = $('marker-lon').value;
  if (Boolean(lat) !== Boolean(lon)) { toast('参考纬度和经度需要同时填写'); return; }
  if (lat && lon) { o.refLat = Number(lat); o.refLon = Number(lon); }
  addMarker(o);
  $('marker-dialog').close();
};

$('select-all').onclick = () => {
  if (state.recording || review) return;
  selected = new Set((state.inventory || []).map(s => s.id));
  renderSensors(state, true);
};
$('location-settings').onclick = () => backend?.locationSettings();
$('network-settings').onclick = () => backend?.networkSettings?.();
$('freeze-button').onclick = () => {
  frozen = !frozen;
  $('freeze-button').textContent = frozen ? '继续刷新' : '冻结显示';
  if (!frozen) renderRaw(displayData());
};
$('raw-filter').onchange = () => {
  if (review) loadPage(0);
  else renderRaw(displayData(), true);
};
$('raw-prev').onclick = () => loadPage(Math.max(0, pageOffset - 100));
$('raw-next').onclick = () => loadPage(pageOffset + 100);

function loadPage(offset) {
  if (!review) return;
  const result = parse(backend.page(review.manifest.id, offset, $('raw-filter').value));
  if (result.error) { toast(result.error); return; }
  pageRows = result.rows || [];
  pageOffset = offset;
  pageHasMore = result.hasMore;
  renderRaw(displayData(), true);
}

$('refresh-history').onclick = renderHistory;
$('exit-review').onclick = exitReview;
$('review-export').onclick = () => backend.exportSession(review.manifest.id);

function exitReview() {
  review = null;
  pageRows = null;
  pageOffset = 0;
  frozen = false;
  $('freeze-button').textContent = '冻结显示';
  $('review-banner').hidden = true;
  activeScene = labelConfig.defaultScene;
  activeAction = labelConfig.defaultAction;
  pendingScene = activeScene;
  pendingAction = activeAction;
  isUserPicking = false;
  setTab('overview');
  render();
}

function renderHistory() {
  const sessions = parse(backend?.sessions() || '[]', []);
  $('history-list').innerHTML = sessions.length ? sessions.map(s => `
    <article class='panel history-item'>
      <div class='history-icon'>≋</div>
      <div class='history-info'>
        <h3>${esc(s.title)} ${s.status !== 'saved' ? '<span class=\'rate\'>未完整结束</span>' : ''}</h3>
        <p>${esc(new Date(s.startedWallMs).toLocaleString('zh-CN'))} · ${esc(s.scene || '默认场景')}${s.action ? ' + ' + esc(s.action) : ''} · ${esc(s.pose)}</p>
        <p>${time(s.durationS)} · ${Number(s.rows || 0).toLocaleString()} 个事件 · ${fmt((s.bytes || 0) / 1048576, 2)} MB${s.demo ? ' · 模拟记录' : ''}</p>
      </div>
      <div class='history-actions'>
        <button class='text-button' data-review='${esc(s.id)}'>查看记录 ↗</button>
        <button class='text-button' data-export='${esc(s.id)}'>导出 ZIP ↓</button>
      </div>
    </article>
  `).join('') : '<div class=\'panel empty\'>还没有采集记录。开始一次采集，结束后会自动保存在这里。</div>';

  document.querySelectorAll('[data-review]').forEach(b => b.onclick = () => {
    if (state.recording || state.busy) { toast('请先结束并保存本次采集'); return; }
    toast('正在读取记录…');
    setTimeout(() => {
      const data = parse(backend.review(b.dataset.review));
      if (data.error || !data.manifest) { toast(data.error || '无法读取记录'); return; }
      review = data;
      pageRows = null;
      frozen = false;
      $('review-banner').hidden = false;
      $('review-title').textContent = '回看：' + data.manifest.title + ' · ' + time(data.manifest.durationS);
      activeScene = data.manifest.scene || '室内';
      activeAction = data.manifest.action || '步行';
      pendingScene = activeScene;
      pendingAction = activeAction;
      isUserPicking = false;
      setTab('overview');
    }, 20);
  });

  document.querySelectorAll('[data-export]').forEach(b => b.onclick = () => backend.exportSession(b.dataset.export));
}

function render() {
  const d = displayData(), active = !!state.recording && !review;
  const busy = !!state.busy;

  $('session-name').textContent = review ? review.manifest.title : settings.title;
  $('scene-display').textContent = review ?
    ((review.manifest.scene || '室内') + (review.manifest.action ? ' + ' + review.manifest.action : '') + ' · ' + review.manifest.pose) :
    (activeScene + ' · ' + activeAction + ' · ' + settings.pose);

  $('duration').textContent = time(d.duration);
  $('row-count').textContent = Number(d.rows || 0).toLocaleString();
  $('hz-display').innerHTML = Number(d.requestedHz || settings.hz) + ' <small>Hz</small>';

  $('capture-button').classList.toggle('stop', active);
  $('capture-button').disabled = busy;
  $('capture-button').innerHTML = '<span class=\'record-dot\'></span>' + (review ? '返回实时采集' : busy ? '正在处理…' : active ? '结束并保存' : '开始新采集');
  if (!(d.rows || 0) && !active && !review) $('capture-button').innerHTML = '<span class=\'record-dot\'></span>开始采集';

  renderCourse(d);
  $('status-pill').classList.toggle('recording', active);
  $('status-pill').innerHTML = '<i></i>' + (review ? '历史回看' : busy ? '处理中' : active ? '正在采集' : state.savedId ? '已保存' : '准备就绪');
  $('device-name').innerHTML = esc(state.device || '正在识别设备') + '<small>原始数据保存在本机</small>';
  $('current-label').textContent = active ? '生效状态：' + (activeScene + '·' + activeAction) : '';

  renderStateControls();

  document.querySelectorAll('[data-marker],#custom-marker').forEach(b => {
    b.disabled = !active;
  });

  const warnings = review ? (review.manifest.warnings || []) : [...(state.error ? [state.error] : []), ...(state.warnings || [])];
  $('warning-box').hidden = !warnings.length;
  $('warning-box').textContent = warnings.join('；');

  if (tab === 'overview') renderOverview(d);
  if (tab === 'sensors') renderSensors(d);
  if (tab === 'raw') renderRaw(d);
}

function renderOverview(d) {
  document.querySelectorAll('.chart-foot>span:first-child').forEach(s => s.textContent = review ? '全段抽样回看' : '最近 12 秒');
  for (const type of [1, 4]) {
    const src = source(type, d), v = valuesFor(type, d), stat = statFor(type, d);
    $('rate-' + type).textContent = stat?.count ? fmt(stat.sessionHz ?? stat.hz, 1) + ' Hz 实测' : src ? '等待数据' : '设备未提供';
    $('values-' + type).innerHTML = ['X', 'Y', 'Z'].map((axis, i) => `<span>${axis} <b>${fmt(v?.[i])}</b></span>`).join('');
    lineChart($('chart-' + type), src ? d.streams?.[src.id] || [] : [], { window: review ? null : 12 });
  }

  const heading = headingFrom(d), a = valuesFor(1, d) || valuesFor(9, d), mag = valuesFor(2, d), tilt = a ? Math.atan2(Math.hypot(a[0], a[1]), a[2]) * 180 / Math.PI : null;
  $('heading-value').textContent = Number.isFinite(heading) ? fmt(heading, 0) + '°' : '—';
  $('tilt-value').textContent = Number.isFinite(tilt) ? fmt(tilt, 1) + '°' : '—';
  $('mag-value').textContent = mag ? fmt(Math.hypot(...mag.slice(0, 3)), 1) + ' µT' : '—';
  drawCompass($('compass'), heading, tilt);

  const l = d.location;
  $('coordinates').textContent = l ? fmt(l.lat, 5) + ' / ' + fmt(l.lon, 5) : '— / —';
  $('location-accuracy').textContent = l ? fmt(l.accuracy, 1) + ' m' : '—';
  $('location-speed').textContent = l && Number.isFinite(l.speed) ? fmt(l.speed, 2) + ' m/s' : '—';
  $('location-status').textContent = l ? l.provider === 'gps' ? 'GNSS 定位' : '系统位置' : '等待定位';
  drawRoute($('route-chart'), d.route || [], d.markers || []);

  const env = [[5, '光照', 'lx'], [6, '气压', 'hPa'], [13, '环境温度', '°C'], [12, '相对湿度', '%'], [8, '接近距离', 'cm']];
  $('environment-list').innerHTML = env.map(([type, title, unit]) => {
    const src = source(type, d), v = valuesFor(type, d);
    return `<div class='environment-row'><span>${title}</span><strong>${src ? v ? fmt(v[0], type === 5 ? 0 : 1) : '等待数据' : '不可用'}${v ? `<small>${unit}</small>` : ''}</strong></div>`;
  }).join('');
  $('sensor-count').textContent = (d.inventory || []).length + ' 路';

  const s = statFor(1, d), continuous = (d.stats || []).filter(x => (d.inventory || []).find(i => i.id === x.id)?.reportingMode === 0), gaps = continuous.reduce((n, x) => n + (x.nonMonotonic || 0), 0);
  $('quality-grid').innerHTML = [
    ['加速度实测频率', s?.count ? fmt(s.sessionHz ?? s.hz, 1) + ' Hz' : '—', '目标 ' + (d.requestedHz || settings.hz) + ' Hz'],
    ['最大采样间隔', s?.count ? fmt(s.maxGapMs, 1) + ' ms' : '—', '加速度 · 反映间隔抖动'],
    ['时间戳异常', gaps + ' 次', '连续数据 · 重复或倒序'],
    ['加速度 X 轴方差', s?.count ? fmt(s.varianceV0, 4) : '—', '本次全段统计 · (m/s²)²']
  ].map(([title, value, note]) => `<div class='quality-item'><span>${title}</span><strong>${value}</strong><small>${note}</small></div>`).join('');

  // State intervals duration bar
  const duration = Math.max(d.duration || 0, 0.001);
  const rawStates = (d.states && d.states.length) ? d.states : [
    { s: 0, scene: d.scene || (review?.manifest?.scene) || labelConfig.defaultScene, action: d.action || (review?.manifest?.action) || labelConfig.defaultAction, label: d.label || '初始' }
  ];

  $('state-count').textContent = rawStates.length + ' 个状态区间';

  const segments = [];
  for (let i = 0; i < rawStates.length; i++) {
    const cur = rawStates[i];
    const sStart = Math.max(0, cur.s || 0);
    const sEnd = (i < rawStates.length - 1) ? Math.max(sStart, rawStates[i + 1].s || 0) : duration;
    const combo = (cur.scene || '室内') + ' · ' + (cur.action || '步行');
    const dur = Math.max(0, sEnd - sStart);
    segments.push({ sStart, sEnd, scene: cur.scene, action: cur.action, combo, dur });
  }

  $('state-interval-bar').innerHTML = segments.map(seg => {
    const pct = Math.max(0.5, (seg.dur / duration) * 100);
    const bg = getStateColor(seg.combo);
    const showText = pct >= 8;
    return `<div class='state-interval-segment' style='width:${pct.toFixed(2)}%;background:${bg}' title='${esc(seg.combo)} (${time(seg.sStart)} - ${time(seg.sEnd)}, ${seg.dur.toFixed(1)}s)'>${showText ? esc(seg.combo) : ''}</div>`;
  }).join('');

  const ticks = [];
  ticks.push({ s: 0, pct: 0, label: '00:00' });
  for (let i = 1; i < rawStates.length; i++) {
    const s = rawStates[i].s || 0;
    const pct = Math.min(100, Math.max(0, (s / duration) * 100));
    ticks.push({ s, pct, label: time(s) });
  }
  if (duration > 0) {
    ticks.push({ s: duration, pct: 100, label: time(duration) });
  }
  $('state-interval-ticks').innerHTML = ticks.map(t => `<span class='state-tick' style='left:${t.pct.toFixed(2)}%'>${t.label}</span>`).join('');

  // State timeline
  $('state-timeline-count').textContent = rawStates.length + ' 次状态生效';
  const stateTimelineHtml = rawStates.map((st, i) => {
    const combo = (st.scene || '室内') + ' + ' + (st.action || '步行');
    const isInit = (i === 0);
    return `<div class='timeline-item' style='border-left-color:${getStateColor(combo)}'><strong>${esc(combo)} ${isInit ? '<small style=\'color:#0b8174;font-size:9px\'>（初始生效）</small>' : ''}</strong><span>${time(st.s || 0)} (${fmt(st.s, 2)} s)</span></div>`;
  }).concat(
    !state.recording && !review && d.duration ? [] :
    [`<div class='timeline-item' style='border-left-color:#8ca595'><strong>${review || !state.recording ? '采集结束' : '持续采集中…'}</strong><span>${time(duration)}</span></div>`]
  ).join('');
  $('state-timeline').innerHTML = stateTimelineHtml;

  // Event markers timeline
  const markers = d.markers || [];
  $('marker-count').textContent = markers.length + ' 个打点';
  $('marker-timeline').innerHTML = markers.length ? markers.slice(-60).map(m => `
    <div class='timeline-item' style='border-left-color:#d58955'>
      <strong>${esc(m.label || '打点')}</strong>
      <span>${time(m.s)} (${fmt(m.s, 2)} s)${m.note ? ' · ' + esc(m.note) : ''}${m.refLat != null ? ` · WGS84: ${fmt(m.refLat, 4)}, ${fmt(m.refLon, 4)}` : ''}</span>
    </div>
  `).join('') : '暂无打点。采集期间可点击打点按钮记录瞬间参考事件。';
}

function renderCourse(d){
  const duration=d.duration||0;
  const names=new Set((d.states||[]).map(e=>e.action).filter(Boolean));
  const acc=(d.stats||[]).find(x=>(d.inventory||[]).some(s=>s.id===x.id&&s.type===1));
  const hz=acc?(acc.sessionHz??(duration>0?acc.count/duration:0)):0;
  $('course-progress').value=Math.min(duration,1200);
  $('course-duration').textContent=time(duration)+' / 20:00';
  $('course-check').textContent=(duration>=1200?'时长达到20分钟':'剩余 '+time(1200-duration))+' · 动作状态 '+names.size+' / ≥3 · 加速度 '+fmt(hz,1)+' Hz';
}
function headingFrom(d) {
  const v = valuesFor(11, d) || valuesFor(20, d);
  if (v && v.length >= 3) {
    const [x, y, z] = v, w = v.length >= 4 ? v[3] : Math.sqrt(Math.max(0, 1 - x * x - y * y - z * z));
    return (Math.atan2(2 * x * y - 2 * z * w, 1 - 2 * x * x - 2 * z * z) * 180 / Math.PI + 360) % 360;
  }
  const a = valuesFor(1, d) || valuesFor(9, d), m = valuesFor(2, d);
  if (a && m) {
    const norm = Math.hypot(...a.slice(0, 3));
    if (norm < .1) return null;
    const ax = a[0] / norm, ay = a[1] / norm, az = a[2] / norm;
    let hx = m[1] * az - m[2] * ay, hy = m[2] * ax - m[0] * az, hz = m[0] * ay - m[1] * ax;
    const h = Math.hypot(hx, hy, hz);
    if (h < .1) return null;
    hx /= h; hy /= h; hz /= h;
    const my = az * hx - ax * hz;
    return (Math.atan2(hy, my) * 180 / Math.PI + 360) % 360;
  }
  return null;
}

let sensorRenderKey = '';
function renderSensors(d, force = false) {
  const all = d.inventory || [], key = (review ? 'review' : 'live') + all.map(s => s.id).join(',');
  if (force || key !== sensorRenderKey) {
    sensorRenderKey = key;
    $('sensor-list').innerHTML = all.length ? all.map(s => `<article class='panel sensor-item'><input type='checkbox' data-sensor='${esc(s.id)}' aria-label='采集${esc(s.title)}' ${selected.has(s.id) || review ? 'checked' : ''}><div class='sensor-info'><h3>${esc(s.title)} <span class='subtle'>${esc(s.id)}</span></h3><p>${esc(s.name)}</p><p class='sensor-meta'>${esc(s.vendor)} · ${esc(s.unit)} · ${['连续', '变化触发', '单次触发', '事件触发'][s.reportingMode] || '系统模式'}${s.wakeUp ? ' · 唤醒型' : ''}</p><p class='sensor-meta'>量程 ${fmt(s.range, 2)} · 分辨率 ${fmt(s.resolution, 5)} · ${fmt(s.powerMa, 2)} mA</p><div class='sensor-value' id='sensor-value-${esc(s.id)}'>等待数据</div></div><span class='rate' id='sensor-rate-${esc(s.id)}'>待采集</span></article>`).join('') : '<div class=\'panel empty\'>正在获取设备数据源…</div>';
    document.querySelectorAll('[data-sensor]').forEach(c => c.onchange = () => {
      if (c.checked) selected.add(c.dataset.sensor); else selected.delete(c.dataset.sensor);
    });
  }
  for (const s of all) {
    const v = d.latest?.[s.id]?.values, stats = (d.stats || []).find(x => x.id === s.id);
    const value = $('sensor-value-' + s.id), rate = $('sensor-rate-' + s.id);
    if (value) value.textContent = v ? v.map(x => fmt(x, 3)).join(' / ') : s.state || '等待数据';
    if (rate) rate.textContent = stats?.count ? s.reportingMode === 0 ? fmt(stats.hz, 1) + ' Hz' : stats.count + ' 个事件' : '待采集';
  }
  $('network-status').innerHTML=['wifi','ble','cell'].map(k=>`<div class='network-row'><span>${({wifi:'Wi-Fi',ble:'BLE',cell:'蜂窝'})[k]} · ${esc(d.networkStatus?.[k]||'待检查')}</span><strong>${Number(d.networkCounts?.[k]||0)} 条</strong></div>`).join('');
  document.querySelectorAll('[data-sensor]').forEach(c => c.disabled = !!state.recording || !!state.busy || !!review);
  $('select-all').disabled = !!state.recording || !!review;
  drawSatellites($('satellite-chart'), d.satellites || []);
  $('satellite-summary').textContent = d.satellites?.length ? `${d.satellites.length} 颗可见 · ${d.satellites.filter(s => s.used).length} 颗参与定位 · 深绿色为参与定位卫星` : '尚未收到卫星观测。请在开阔地开启系统定位。';
}

function renderRaw(d, force = false) {
  if (frozen && !force) return;
  const filter = $('raw-filter').value, rows = review && pageRows ? pageRows : (d.raw || []).filter(e => !filter || e.kind === filter || e.id === filter).slice().reverse();
  shownRaw = rows;
  $('raw-body').innerHTML = rows.length ? rows.map((e, i) => `<tr class='event-row' data-event='${i}'><td>${fmt(e.s, 3)}</td><td>${esc(e.kind === 'sensor' ? (d.inventory || []).find(s => s.id === e.id)?.title || e.id : { state: '状态切换', location: '位置', network: '射频扫描', satellites: '卫星', gnss_raw: 'GNSS 原始观测', nmea: 'NMEA', marker: '打点' }[e.kind] || e.kind)}</td><td>${esc(eventValue(e))}</td><td>${esc(e.scene && e.action ? e.scene + '·' + e.action : e.label || '—')}</td></tr>`).join('') : '<tr><td colspan=\'4\' class=\'empty\'>尚无数据。开始采集后，点击任一行查看完整事件。</td></tr>';
  document.querySelectorAll('[data-event]').forEach(row => row.onclick = () => {
    $('event-json').textContent = JSON.stringify(shownRaw[Number(row.dataset.event)], null, 2);
    $('event-dialog').showModal();
  });
  $('raw-pagination').hidden = !review;
  $('raw-prev').disabled = pageOffset === 0;
  $('raw-next').disabled = !pageHasMore;
  $('raw-page').textContent = '第 ' + (pageOffset / 100 + 1) + ' 页';
  $('raw-note').textContent = review ? '按每页 100 个事件查看完整记录；点击事件可查看所有字段。' : '展示最近 80 个事件；完整原始记录持续保存，图表刷新不影响采集。';
}

function eventValue(e) {
  switch (e.kind) {
    case 'sensor': return (e.values || []).map(v => fmt(v, 5)).join(', ');
    case 'network': return `${e.source} · ${e.address || ''} · ${e.rssiDbm ?? '—'} dBm`;
    case 'state': return `切换至: ${e.scene || ''} + ${e.action || ''}`;
    case 'location': return `${fmt(e.lat, 7)}, ${fmt(e.lon, 7)} · ±${fmt(e.accuracy, 1)} m`;
    case 'satellites': return (e.satellites?.length || 0) + ' 颗卫星';
    case 'gnss_raw': return (e.measurements?.length || 0) + ' 路原始观测 · 接收机时钟 ' + (e.clock?.timeNanos || '');
    case 'nmea': return e.sentence;
    case 'marker': return e.label + (e.note ? ' · ' + e.note : '');
    default: return JSON.stringify(e);
  }
}

function canvasContext(canvas) {
  const rect = canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  if (canvas.width !== Math.round(rect.width * ratio) || canvas.height !== Math.round(rect.height * ratio)) {
    canvas.width = Math.round(rect.width * ratio);
    canvas.height = Math.round(rect.height * ratio);
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, rect.width, rect.height);
  ctx.font = '9px sans-serif';
  return { ctx, w: rect.width, h: rect.height };
}

function blank(ctx, w, h, message = '等待真实数据') {
  ctx.fillStyle = '#a5b1a7';
  ctx.textAlign = 'center';
  ctx.fillText(message, w / 2, h / 2);
  ctx.textAlign = 'left';
}

function lineChart(canvas, points, { window: win = 12 } = {}) {
  const c = canvasContext(canvas);
  if (!c) return;
  const { ctx, w, h } = c, left = 37, right = 8, top = 9, bottom = 23;
  let p = points;
  if (win && p.length) p = p.filter(s => s.s >= p.at(-1).s - win);
  if (p.length < 2) { blank(ctx, w, h); return; }
  const all = p.flatMap(s => s.values.slice(0, 3)).filter(Number.isFinite);
  if (!all.length) { blank(ctx, w, h); return; }
  let min = Math.min(...all), max = Math.max(...all), range = max - min || 1;
  min -= range * .15; max += range * .15;
  const tMax = p.at(-1).s, tMin = win ? Math.max(0, tMax - win) : p[0].s, span = tMax - tMin || 1;
  ctx.strokeStyle = '#edf1ed';
  ctx.lineWidth = 1;
  for (let i = 0; i < 4; i++) {
    const y = top + (h - top - bottom) * i / 3;
    ctx.beginPath();
    ctx.moveTo(left, y);
    ctx.lineTo(w - right, y);
    ctx.stroke();
    ctx.fillStyle = '#a1ada3';
    ctx.textAlign = 'right';
    ctx.fillText(fmt(max - (max - min) * i / 3, 1), left - 8, y + 3);
  }
  ctx.textAlign = 'left';
  ctx.fillText(fmt(tMin, 1) + ' s', left, h - 5);
  ctx.textAlign = 'right';
  ctx.fillText(fmt(tMax, 1) + ' s', w - right, h - 5);
  ctx.textAlign = 'left';
  for (let axis = 0; axis < Math.min(3, p[0].values.length); axis++) {
    ctx.beginPath();
    let started = false;
    for (const s of p) {
      const value = s.values[axis];
      if (!Number.isFinite(value)) { started = false; continue; }
      const x = left + (s.s - tMin) / span * (w - left - right), y = top + (max - value) / (max - min) * (h - top - bottom);
      if (!started) { ctx.moveTo(x, y); started = true; } else ctx.lineTo(x, y);
    }
    ctx.strokeStyle = colors[axis];
    ctx.lineWidth = 1.5;
    ctx.stroke();
  }
}

function drawCompass(canvas, heading, tilt) {
  const c = canvasContext(canvas);
  if (!c) return;
  const { ctx, w, h } = c, cx = w / 2, cy = h / 2, r = Math.min(w / 2 - 28, h / 2 - 24);
  ctx.strokeStyle = '#e2eae2';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, r * .75, 0, Math.PI * 2);
  ctx.stroke();
  for (let i = 0; i < 24; i++) {
    const a = i * Math.PI / 12;
    ctx.beginPath();
    ctx.moveTo(cx + Math.sin(a) * (r - 4), cy - Math.cos(a) * (r - 4));
    ctx.lineTo(cx + Math.sin(a) * (r - (i % 6 ? 8 : 13)), cy - Math.cos(a) * (r - (i % 6 ? 8 : 13)));
    ctx.stroke();
  }
  ctx.textAlign = 'center';
  ctx.fillStyle = '#8fa293';
  ctx.font = '10px sans-serif';
  for (const [n, a] of [['N', 0], ['E', Math.PI / 2], ['S', Math.PI], ['W', 3 * Math.PI / 2]])
    ctx.fillText(n, cx + Math.sin(a) * (r + 14), cy - Math.cos(a) * (r + 14) + 3);
  if (Number.isFinite(heading)) {
    ctx.save();
    ctx.translate(cx, cy);
    ctx.rotate(heading * Math.PI / 180);
    ctx.fillStyle = '#218971';
    ctx.beginPath();
    ctx.moveTo(0, -r * .65);
    ctx.lineTo(9, r * .35);
    ctx.lineTo(0, r * .18);
    ctx.lineTo(-9, r * .35);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  } else {
    ctx.fillStyle = '#a9b5aa';
    ctx.font = '9px sans-serif';
    ctx.fillText('等待方向数据', cx, cy + 3);
  }
  ctx.textAlign = 'left';
}

function drawRoute(canvas, points, markers) {
  const c = canvasContext(canvas);
  if (!c) return;
  const { ctx, w, h } = c;
  ctx.strokeStyle = '#edf2ec';
  for (let x = 0; x < w; x += 28) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
  for (let y = 0; y < h; y += 28) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
  if (!points.length) { blank(ctx, w, h, '等待位置观测 · 请在开阔地采集'); return; }
  const origin = points[0], cos = Math.cos(origin.lat * Math.PI / 180);
  const project = p => [(p.lon - origin.lon) * 111320 * cos, (p.lat - origin.lat) * 110574];
  const xy = points.map(project), refs = markers.filter(m => Number.isFinite(m.refLat) && Number.isFinite(m.refLon)).map(m => project({ lat: m.refLat, lon: m.refLon }));
  const all = [...xy, ...refs], xs = all.map(p => p[0]), ys = all.map(p => p[1]), minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys), rangeX = Math.max(10, maxX - minX), rangeY = Math.max(10, maxY - minY), scale = Math.min((w - 52) / rangeX, (h - 40) / rangeY), midX = (maxX + minX) / 2, midY = (maxY + minY) / 2, toScreen = ([x, y]) => [w / 2 + (x - midX) * scale, h / 2 - (y - midY) * scale];
  ctx.beginPath();
  xy.forEach((p, i) => { const [x, y] = toScreen(p); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
  ctx.strokeStyle = '#3a9781';
  ctx.lineWidth = 2;
  ctx.stroke();
  const [x, y] = toScreen(xy.at(-1));
  ctx.beginPath();
  ctx.arc(x, y, 5, 0, Math.PI * 2);
  ctx.fillStyle = '#087f72';
  ctx.fill();
  refs.forEach(p => {
    const [x, y] = toScreen(p);
    ctx.strokeStyle = '#d58955';
    ctx.beginPath(); ctx.moveTo(x - 4, y - 4); ctx.lineTo(x + 4, y + 4); ctx.moveTo(x - 4, y + 4); ctx.lineTo(x + 4, y - 4); ctx.stroke();
  });
  ctx.fillStyle = '#8da18f';
  ctx.fillText('N ↑', 8, 15);
  const meters = Math.max(1, Math.round(40 / scale));
  ctx.fillText(meters + ' m', 8, h - 7);
  ctx.strokeStyle = '#9bae9d';
  ctx.beginPath(); ctx.moveTo(8, h - 17); ctx.lineTo(8 + meters * scale, h - 17); ctx.stroke();
}

function drawSatellites(canvas, satellites) {
  const c = canvasContext(canvas);
  if (!c) return;
  const { ctx, w, h } = c;
  if (!satellites.length) { blank(ctx, w, h, '等待 GNSS 卫星数据'); return; }
  const list = satellites.slice(0, 45), gap = w / list.length;
  for (let i = 0; i < list.length; i++) {
    const s = list[i], bar = Math.max(0, Math.min(1, s.cn0 / 60)) * (h - 30);
    ctx.fillStyle = s.used ? '#388d73' : '#cadbcc';
    ctx.fillRect(i * gap + 3, h - 20 - bar, Math.max(3, gap - 6), bar);
    ctx.fillStyle = '#99a99a';
    ctx.textAlign = 'center';
    ctx.fillText(String(s.svid), i * gap + gap / 2, h - 5);
  }
  ctx.textAlign = 'left';
}

function poll() {
  clearTimeout(nextPoll);
  if (backend) {
    state = parse(backend.snapshot());
    const key = (state.inventory || []).map(s => s.id).join(',');
    if (key && key !== inventoryKey) {
      inventoryKey = key;
      selected = new Set((state.inventory || []).map(s => s.id));
    }
    if (state.recording) {
      if (state.scene) activeScene = state.scene;
      if (state.action) activeAction = state.action;
      if (!isUserPicking) {
        pendingScene = activeScene;
        pendingAction = activeAction;
      }
    } else if (!review) {
      activeScene = labelConfig.defaultScene;
      activeAction = labelConfig.defaultAction;
      if (!isUserPicking) {
        pendingScene = activeScene;
        pendingAction = activeAction;
      }
    }
    if (previousRecording && !state.recording && state.savedId) {
      toast('本次记录已保存，可在“采集记录”中回看或导出');
      lastSaved = state.savedId;
      if (tab === 'history') renderHistory();
    }
    previousRecording = !!state.recording;
    if (state.error && state.error !== lastError) {
      toast(state.error);
      lastError = state.error;
    }
    render();
  }
  nextPoll = setTimeout(poll, review ? 1000 : 250);
}

window.addEventListener('resize', render);
document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });

if (!backend) {
  $('preview-banner').hidden = false;
  $('footer-state').textContent = '网页预览 / 模拟数据';
  const script = document.createElement('script');
  script.src = '/preview-adapter.js';
  script.onload = () => {
    backend = window.PreviewBackend;
    $('demo-button').onclick = () => {
      exitReview();
      activeScene = labelConfig.defaultScene;
      activeAction = labelConfig.defaultAction;
      pendingScene = activeScene;
      pendingAction = activeAction;
      isUserPicking = false;
      backend.start(JSON.stringify({
        ...settings,
        scene: activeScene,
        action: activeAction,
        initialState: activeAction,
        sensorIds: [...selected]
      }));
      toast('模拟数据已启动，真机数据请使用 Android APK');
      poll();
    };
    poll();
  };
  script.onerror = () => toast('网页预览需要通过项目预览服务器打开');
  document.head.append(script);
} else {
  poll();
}

render();
