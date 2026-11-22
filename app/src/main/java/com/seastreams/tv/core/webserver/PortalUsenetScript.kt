package com.seastreams.tv.core.webserver

/**
 * The portal's `Usenet sources` section (PRD §8 item 4, §12), appended after [PortalScript] and
 * reusing its `h`, `byId`, `toast` and `api` helpers. Forms are built once, so a refresh never wipes
 * what is being typed; lists and values are re-rendered from `GET /api/usenet`. Passwords and API
 * keys are write-only: the TV only ever answers whether one is stored.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalUsenetScript {

    val JS = """
const U = { st: null, provider: null, indexer: null };
const PERF = [
  ['Performance'], ['readAheadSegments', 'Read-ahead (segments, 1–256)'], ['maxConcurrentDownloads', 'Max concurrent downloads (0 = auto)'],
  ['segmentDiskCacheMb', 'Segment disk cache (MB, 0 = off, max 4096)'], ['warmConnections', 'Warm connections (0, 2 or 4)'],
  ['Connections & timeouts'], ['streamingPriorityPercent', 'Streaming priority share (%, 50–100)'],
  ['segmentTimeoutSeconds', 'Segment timeout (s, 0 = never)'], ['segmentStallTimeoutSeconds', 'Segment stall timeout (s)'],
  ['dialTimeoutSeconds', 'Dial timeout (s)'], ['idleConnectionTtlSeconds', 'Idle connection TTL (s)'],
  ['streamIdleTimeoutSeconds', 'Stream idle timeout (s, 0 = off)'],
  ['Reliability'], ['circuitBreakerThreshold', 'Circuit breaker threshold'], ['circuitBreakerCooldownSeconds', 'Circuit breaker cooldown (s)'],
  ['Archive handling'], ['lazyRarResolution', 'Lazy RAR resolution', 'bool'], ['strictArchiveMembership', 'Strict archive membership', 'bool'],
  ['Verification'], ['verifyMode', 'Verify mode', ['CENSUS', 'NONE']], ['verifyBudgetMillis', 'Verify budget (ms)'],
  ['verifyFileContents', 'Verify file contents', 'bool'], ['verifyArticleChecksums', 'Verify article checksums', 'bool'],
  ['damagePolicy', 'Damage policy', ['STRICT', 'TOLERANT']], ['matroskaHoleRepair', 'Matroska hole repair', 'bool'],
  ['censusConcurrency', 'Census background concurrency'], ['censusMaxLifetimeMinutes', 'Census max lifetime (min)'],
  ['Import'], ['maxNzbSizeMb', 'Max NZB size (MB)'], ['maxConcurrentImports', 'Max concurrent imports (0 = unlimited)'],
];

function field(label, input) { return h('label', { class: 'field' }, label, input); }
function check(label, input) { return h('label', { class: 'check' }, input, ' ' + label); }
function textInput(id, extra) { return h('input', Object.assign({ type: 'text', id: id, autocapitalize: 'off', spellcheck: false }, extra || {})); }
function numberInput(id, min, max) { return h('input', { type: 'number', id: id, min: String(min), max: String(max), inputmode: 'numeric' }); }
function checkbox(id) { return h('input', { type: 'checkbox', id: id }); }
function selectOf(id, options) { return h('select', { id: id }, options.map((o) => h('option', { value: o[0] }, o[1]))); }

async function loadUsenet() {
  try {
    const r = await fetch('/api/usenet', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    U.st = await r.json();
    renderUsenet();
  } catch (e) {
    toast('Could not load Usenet sources from the TV', false);
  }
}

async function uApi(path, body, button) {
  const ok = await api(path, body, button);
  if (ok) await loadUsenet();
  return ok;
}

function sourceRow(item, kind, sub) {
  const path = '/usenet/' + kind;
  return h('li', { class: item.enabled ? '' : 'disabled' },
    h('div', null, h('div', { class: 'title' }, item.name), h('div', { class: 'sub' }, sub)),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'icon', onclick: (e) => uApi(path + '/enabled', { id: item.id, enabled: !item.enabled }, e.currentTarget) }, item.enabled ? 'Disable' : 'Enable'),
      h('button', { type: 'button', class: 'icon', onclick: (e) => uApi(path + '/move', { id: item.id, offset: -1 }, e.currentTarget) }, '↑'),
      h('button', { type: 'button', class: 'icon', onclick: (e) => uApi(path + '/move', { id: item.id, offset: 1 }, e.currentTarget) }, '↓'),
      kind === 'providers' ? h('button', { type: 'button', class: 'icon', onclick: (e) => api('/usenet/providers/test', Object.assign({}, item, { password: null }), e.currentTarget) }, 'Test') : null,
      h('button', { type: 'button', class: 'icon', onclick: () => openForm(kind, item) }, 'Edit'),
      h('button', { type: 'button', class: 'icon danger', onclick: (e) => removeSource(kind, item, e.currentTarget) }, 'Remove')));
}

// Removing a source always returns its form to a blank "Add" state, so no name, server or credentials of the
// removed row linger (and a later Save can never target the deleted id).
async function removeSource(kind, item, button) {
  if (!confirm('Remove ' + item.name + '?')) return;
  if (await uApi('/usenet/' + kind + '/remove', { id: item.id }, button)) {
    if (kind === 'providers') editProvider(null); else editIndexer(null);
  }
}

// Edit (item) or Add (null): fills the form from scratch and brings it into view.
function openForm(kind, item) {
  if (kind === 'providers') editProvider(item); else editIndexer(item);
  const form = byId(kind === 'providers' ? 'up-form' : 'ui-form');
  form.scrollIntoView({ behavior: 'smooth', block: 'start' });
  byId(kind === 'providers' ? 'up-name' : 'ui-name').focus({ preventScroll: true });
}

function setupUsenet() {
  const providerPreset = h('select', { id: 'up-preset' }, h('option', { value: '' }, 'Start from a preset…'));
  const indexerPreset = h('select', { id: 'ui-preset' }, h('option', { value: '' }, 'Start from a preset…'));
  const perf = h('div', { class: 'perf-grid' });
  PERF.forEach((f) => {
    if (f.length === 1) { perf.append(h('h4', { class: 'sub', style: 'margin:10px 0 0' }, f[0])); return; }
    const id = 'perf-' + f[0];
    if (f[2] === 'bool') perf.append(check(f[1], checkbox(id)));
    else if (Array.isArray(f[2])) perf.append(field(f[1], selectOf(id, f[2].map((v) => [v, v.charAt(0) + v.slice(1).toLowerCase()]))));
    else perf.append(field(f[1], numberInput(id, 0, 14400)));
  });
  byId('sources').append(
    h('h2', null, 'Usenet sources'),
    h('p', { class: 'hint' }, 'Shared by every profile. Passwords and API keys are stored encrypted on the TV and are never shown again; leave them blank when editing to keep them.'),
    h('div', { class: 'row' }, h('h3', null, 'Providers'), h('button', { type: 'button', id: 'up-new', onclick: () => openForm('providers', null) }, '+ Add provider')),
    h('ul', { class: 'list', id: 'up-list' }),
    h('form', { id: 'up-form', autocomplete: 'off', style: 'margin-top:12px' },
      h('h4', { id: 'up-title', style: 'margin:0' }, 'Add a provider'), providerPreset,
      field('Name', textInput('up-name', { required: true, maxLength: 60 })),
      h('div', { class: 'row' }, field('Server', textInput('up-host', { required: true, placeholder: 'news.example.com' })), field('Port', numberInput('up-port', 1, 65535))),
      check('TLS (recommended)', checkbox('up-tls')),
      h('div', { class: 'row' }, field('Username', textInput('up-user', { name: 'nntp-username', autocomplete: 'off' })),
        field('Password', h('input', { type: 'password', id: 'up-pass', name: 'nntp-password', autocomplete: 'new-password' }))),
      h('div', { class: 'row' }, field('Max connections (1–100)', numberInput('up-conns', 1, 100)), field('Pipeline depth (1–16)', numberInput('up-depth', 1, 16)),
        field('Priority (0 = first)', numberInput('up-prio', 0, 99))),
      check('Backup (only for articles the others are missing)', checkbox('up-backup')),
      h('div', { class: 'row' }, h('button', { class: 'primary', type: 'submit', id: 'up-save' }, 'Add provider'),
        h('button', { type: 'button', onclick: (e) => api('/usenet/providers/test', providerBody(), e.currentTarget) }, 'Test'),
        h('button', { type: 'button', onclick: () => editProvider(null) }, 'Cancel'))),
    h('div', { class: 'row' }, h('h3', null, 'Indexers'), h('button', { type: 'button', id: 'ui-new', onclick: () => openForm('indexers', null) }, '+ Add indexer')),
    h('ul', { class: 'list', id: 'ui-list' }),
    h('form', { id: 'ui-form', autocomplete: 'off', style: 'margin-top:12px' },
      h('h4', { id: 'ui-title', style: 'margin:0' }, 'Add an indexer'), indexerPreset,
      field('Name', textInput('ui-name', { required: true, maxLength: 60 })),
      h('div', { class: 'row' }, field('Address', textInput('ui-url', { required: true, placeholder: 'https://indexer.example', inputMode: 'url' })), field('API path', textInput('ui-path'))),
      field('API key', h('input', { type: 'password', id: 'ui-key', autocomplete: 'off' })),
      field('Priority (0 = first)', numberInput('ui-prio', 0, 99)),
      check('Backup only (searched when the others find nothing)', checkbox('ui-backup')),
      h('div', { class: 'row' }, h('button', { class: 'primary', type: 'submit', id: 'ui-save' }, 'Add indexer'), h('button', { type: 'button', onclick: () => editIndexer(null) }, 'Cancel'))),
    h('h3', null, 'Request headers'),
    h('div', { class: 'row' }, field('Search', h('select', { id: 'uh-search' })), field('Download', h('select', { id: 'uh-download' }))),
    h('p', { class: 'sub', id: 'uh-checked' }),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'primary', onclick: (e) => uApi('/usenet/headers', { search: byId('uh-search').value, download: byId('uh-download').value }, e.currentTarget) }, 'Save headers'),
      h('button', { type: 'button', onclick: (e) => uApi('/usenet/headers/update', {}, e.currentTarget) }, 'Update now')),
    h('h3', null, 'Usenet performance'),
    h('div', { class: 'row' },
      field('Profile', selectOf('uperf-profile', [['CONSERVATIVE', 'Conservative'], ['BALANCED', 'Balanced'], ['HIGH', 'High'], ['CUSTOM', 'Custom']])),
      h('button', { type: 'button', class: 'primary', onclick: (e) => uApi('/usenet/performance', { profile: byId('uperf-profile').value }, e.currentTarget) }, 'Apply profile')),
    h('details', null, h('summary', null, 'Edit individual values (switches to Custom)'), perf,
      h('button', { type: 'button', class: 'primary', style: 'margin-top:10px', onclick: (e) => uApi('/usenet/performance', { values: perfValues() }, e.currentTarget) }, 'Save values')));
  providerPreset.addEventListener('change', () => {
    const p = U.st && U.st.providerPresets.find((x) => x.name === providerPreset.value);
    if (!p) return;
    // A preset always starts a new provider: it never rewrites the one being edited, nor keeps its credentials.
    editProvider(null);
    providerPreset.value = p.name;
    byId('up-name').value = p.name; byId('up-host').value = p.host; byId('up-port').value = p.port;
    byId('up-tls').checked = p.tls; byId('up-conns').value = p.maxConnections;
  });
  indexerPreset.addEventListener('change', () => {
    const p = U.st && U.st.indexerPresets.find((x) => x.name === indexerPreset.value);
    if (!p) return;
    editIndexer(null);
    indexerPreset.value = p.name;
    byId('ui-name').value = p.name; byId('ui-url').value = p.baseUrl; byId('ui-path').value = p.apiPath;
  });
  byId('up-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (await uApi('/usenet/providers', providerBody(), submitButton(e))) editProvider(null);
  });
  byId('ui-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const body = {
      id: U.indexer ? U.indexer.id : null, name: byId('ui-name').value, baseUrl: byId('ui-url').value,
      apiPath: byId('ui-path').value || '/api', apiKey: byId('ui-key').value || null, priority: Number(byId('ui-prio').value) || 0,
      backupOnly: byId('ui-backup').checked, enabled: U.indexer ? U.indexer.enabled : true,
    };
    if (await uApi('/usenet/indexers', body, submitButton(e))) editIndexer(null);
  });
  editProvider(null);
  editIndexer(null);
}

function providerBody() {
  return {
    id: U.provider ? U.provider.id : null, name: byId('up-name').value, host: byId('up-host').value,
    port: Number(byId('up-port').value) || 563, tls: byId('up-tls').checked, username: byId('up-user').value,
    password: byId('up-pass').value || null, maxConnections: Number(byId('up-conns').value) || 20,
    pipelineDepth: Number(byId('up-depth').value) || 2, priority: Number(byId('up-prio').value) || 0,
    isBackup: byId('up-backup').checked, enabled: U.provider ? U.provider.enabled : true,
  };
}

// Every field (and the stored-password state) is set on both paths, so switching rows or back to Add never
// carries a value over.
function editProvider(p) {
  U.provider = p;
  byId('up-title').textContent = p ? 'Edit ' + p.name : 'Add a provider';
  byId('up-save').textContent = p ? 'Save changes' : 'Add provider';
  byId('up-name').value = p ? p.name : ''; byId('up-host').value = p ? p.host : ''; byId('up-port').value = p ? p.port : 563;
  byId('up-tls').checked = p ? p.tls : true; byId('up-user').value = p ? p.username : ''; byId('up-pass').value = '';
  byId('up-pass').placeholder = p && p.hasPassword ? 'Stored — leave blank to keep' : '';
  byId('up-conns').value = p ? p.maxConnections : 20; byId('up-depth').value = p ? p.pipelineDepth : 2;
  byId('up-prio').value = p ? p.priority : 0; byId('up-backup').checked = p ? p.isBackup : false;
  byId('up-preset').value = '';
}

function editIndexer(x) {
  U.indexer = x;
  byId('ui-title').textContent = x ? 'Edit ' + x.name : 'Add an indexer';
  byId('ui-save').textContent = x ? 'Save changes' : 'Add indexer';
  byId('ui-name').value = x ? x.name : ''; byId('ui-url').value = x ? x.baseUrl : ''; byId('ui-path').value = x ? x.apiPath : '/api';
  byId('ui-key').value = ''; byId('ui-key').placeholder = x && x.hasApiKey ? 'Stored — leave blank to keep' : '';
  byId('ui-prio').value = x ? x.priority : 0; byId('ui-backup').checked = x ? x.backupOnly : false;
  byId('ui-preset').value = '';
}

function perfValues() {
  const v = Object.assign({}, U.st.performance);
  PERF.forEach((f) => {
    if (f.length === 1) return;
    const el = byId('perf-' + f[0]);
    v[f[0]] = f[2] === 'bool' ? el.checked : (Array.isArray(f[2]) ? el.value : Number(el.value));
  });
  return v;
}

// A row being edited that is gone from the TV (removed elsewhere) returns its form to Add; one still there keeps
// the latest copy so enabled / stored-password state stay current. Typed values are left alone.
function keepEditing(current, list) {
  if (!current) return null;
  return list.find((x) => x.id === current.id) || undefined;
}

function renderUsenet() {
  const st = U.st;
  const provider = keepEditing(U.provider, st.providers);
  if (provider === undefined) editProvider(null); else if (provider) U.provider = provider;
  const indexer = keepEditing(U.indexer, st.indexers);
  if (indexer === undefined) editIndexer(null); else if (indexer) U.indexer = indexer;
  const presetOptions = (select, items, label) => {
    if (select.options.length > 1) return;
    items.forEach((p) => select.append(h('option', { value: p.name }, label(p))));
  };
  presetOptions(byId('up-preset'), st.providerPresets, (p) => p.name + ' — ' + p.host);
  presetOptions(byId('ui-preset'), st.indexerPresets, (p) => p.name);
  byId('up-list').replaceChildren(...(st.providers.length ? st.providers.map((p) => sourceRow(p, 'providers',
    p.host + ':' + p.port + (p.tls ? ' · TLS' : ' · plain') + ' · ' + p.maxConnections + ' connections · depth ' + p.pipelineDepth +
    (p.isBackup ? ' · backup' : '') + (p.passwordNeeded ? ' · password needed' : (p.hasPassword ? ' · password stored' : '')))) : [h('li', { class: 'sub' }, 'No providers yet')]));
  byId('ui-list').replaceChildren(...(st.indexers.length ? st.indexers.map((x) => sourceRow(x, 'indexers',
    x.baseUrl + x.apiPath + (x.backupOnly ? ' · backup only' : '') + (x.apiKeyNeeded ? ' · API key needed' : (x.hasApiKey ? ' · API key stored' : '')))) : [h('li', { class: 'sub' }, 'No indexers yet')]));
  ['uh-search', 'uh-download'].forEach((id) => {
    byId(id).replaceChildren(...st.headerPresets.map((p) => h('option', { value: p.name }, p.label)));
  });
  byId('uh-search').value = st.searchHeaders;
  byId('uh-download').value = st.downloadHeaders;
  byId('uh-checked').textContent = st.headersCheckedAtMillis
    ? 'Versions last checked ' + new Date(st.headersCheckedAtMillis).toLocaleString() : 'Versions not checked yet';
  byId('uperf-profile').value = st.performance.profile;
  PERF.forEach((f) => {
    if (f.length === 1) return;
    const el = byId('perf-' + f[0]);
    if (f[2] === 'bool') el.checked = !!st.performance[f[0]]; else el.value = st.performance[f[0]];
  });
}

(function initUsenet() {
  setupUsenet();
  loadUsenet();
  document.addEventListener('visibilitychange', () => { if (!document.hidden) loadUsenet(); });
})();
""".trimIndent()
}
