package com.seastreams.tv.core.webserver

/**
 * The portal's `Stream filters, sorting & formatter` section (PRD §13), appended after [PortalScript] and reusing its
 * `h`, `byId`, `toast` and `api` helpers. Per profile, everything the TV offers: the option-backed attribute groups,
 * result limits and deduplicator ([PortalStreamFilterScript]), keywords, release groups, regexes, seeder / age ranges,
 * size and bitrate limits per resolution (movies / series), all four sort lists ([PortalStreamSortScript]), and custom
 * formatter templates with a live preview from `POST /api/streams/format-preview`. Each section starts with its master
 * toggle checkbox (`Enable stream filtering` / `sorting` / `formatting`), saved as soon as it changes by posting only
 * that flag, so typed edits are kept. `Export JSON` downloads `GET /api/streams/export`; `Import JSON` posts a picked
 * file's text to `POST /api/streams/import`. The form is built once per profile switch, so a refresh never wipes typing.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalStreamScript {

    val JS = """
const SP = { st: null, profileId: null, previewTimer: 0, previewSeq: 0 };
const SP_LISTS = [['required', 'Required'], ['excluded', 'Excluded'], ['included', 'Included (kept regardless)'], ['preferred', 'Preferred (in order)']];
const SP_GB = 1e9;
const SP_MBPS = 1e6;
// [checkbox id, state/request field, endpoint, hint while off]
const SP_STAGES = [
  ['sp-on-filters', 'filteringEnabled', '/streams/filters', 'Off: every stream is shown. Your saved filters are kept but not applied.'],
  ['sp-on-sort', 'sortingEnabled', '/streams/sort', 'Off: streams keep the addon order. Your saved sort criteria are kept but not applied.'],
  ['sp-on-formatter', 'formattingEnabled', '/streams/formatter', "Off: streams show the addon's own text. Your saved formatter is kept but not applied."],
];

function spInput(id, extra) { return h('input', Object.assign({ type: 'text', id: id, autocapitalize: 'off', spellcheck: false }, extra || {})); }
function spNumber(id) { return h('input', { type: 'number', id: id, min: '0', step: 'any', inputmode: 'decimal' }); }
function spArea(id, rows, placeholder) { return h('textarea', { id: id, rows: rows, placeholder: placeholder || '', autocapitalize: 'off', spellcheck: false }); }
function spField(label, input) { return h('label', { class: 'field' }, label, input); }
function spCsv(text) { return text.split(',').map((x) => x.trim()).filter((x) => x.length); }
function spLines(text) { return text.split('\n').map((x) => x.trim()).filter((x) => x.length); }
function spNum(id, scale) { const v = byId(id).value.trim(); return v === '' ? null : Math.round(Number(v) * (scale || 1)); }
function spShow(id, value, scale) { byId(id).value = value === null || value === undefined ? '' : String(value / (scale || 1)); }
function spStage(stage, label) {
  return h('div', null, h('label', { class: 'check' }, h('input', { type: 'checkbox', id: stage[0] }), label),
    h('p', { class: 'hint', id: stage[0] + '-hint', hidden: true }, stage[3]));
}
function spStageHint(stage) { byId(stage[0] + '-hint').hidden = SP.st[stage[1]]; }

// A master toggle posts only its own flag, so the rules (and anything typed but not saved) stay as they are.
async function saveStage(stage, box) {
  const body = { profileId: SP.profileId };
  body[stage[1]] = box.checked;
  box.disabled = true;
  const ok = await api(stage[2], body, null);
  box.disabled = false;
  if (ok) SP.st[stage[1]] = box.checked; else box.checked = SP.st[stage[1]];
  spStageHint(stage);
}

function renderStreamProfiles(st) {
  const select = byId('sp-profile');
  if (!select) return;
  const previous = SP.profileId;
  select.replaceChildren(...(st.profiles.length
    ? st.profiles.map((p) => h('option', { value: String(p.id) }, p.name + (p.isKids ? ' (Kids)' : '')))
    : [h('option', { value: '' }, 'Create a profile first')]));
  const keep = st.profiles.find((p) => p.id === previous) || st.profiles.find((p) => p.id === st.activeProfileId) || st.profiles[0];
  select.disabled = !keep;
  if (!keep) { SP.profileId = null; byId('sp-forms').hidden = true; return; }
  select.value = String(keep.id);
  if (keep.id !== previous) loadStreams(keep.id);
}

async function loadStreams(profileId) {
  SP.profileId = profileId;
  try {
    const r = await fetch('/api/streams?profileId=' + profileId, { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    SP.st = await r.json();
    byId('sp-forms').hidden = false;
    fillStreams();
  } catch (e) {
    toast('Could not load stream settings from the TV', false);
  }
}

function listGroup(prefix, title, hint, multiline) {
  return h('details', null, h('summary', null, title),
    h('p', { class: 'hint' }, hint),
    h('div', { class: 'perf-grid' }, SP_LISTS.map((l) => spField(l[1],
      multiline ? spArea(prefix + '-' + l[0], 3, l[0] === 'preferred' ? 'Name = /pattern/i' : '/pattern/i') : spInput(prefix + '-' + l[0])))));
}

function rangeGroup(prefix, title, unit) {
  const cell = (id) => h('td', null, spNumber(id));
  return h('details', null, h('summary', null, title),
    h('table', { class: 'ranges' },
      h('tr', null, h('th', null, ''), h('th', null, 'Min ' + unit), h('th', null, 'Max ' + unit)),
      [['required', 'Keep only'], ['excluded', 'Remove'], ['included', 'Always keep']].map((r) =>
        h('tr', null, h('th', null, r[1]), cell(prefix + '-' + r[0] + '-min'), cell(prefix + '-' + r[0] + '-max')))));
}

function limitTable(prefix, title, unit, resolutions) {
  const cell = (id) => h('td', null, spNumber(id));
  return h('details', null, h('summary', null, title),
    h('p', { class: 'hint' }, 'A resolution row overrides the global row. Movies use the movie columns; series and anime the series columns. Empty = no limit.'),
    h('table', { class: 'ranges' },
      h('tr', null, h('th', null, ''), h('th', null, 'Movie min'), h('th', null, 'Movie max'), h('th', null, 'Series min'), h('th', null, 'Series max')),
      ['global'].concat(resolutions).map((res) => h('tr', null, h('th', null, res === 'global' ? 'Global' : res),
        cell(prefix + '-' + res + '-mmin'), cell(prefix + '-' + res + '-mmax'), cell(prefix + '-' + res + '-smin'), cell(prefix + '-' + res + '-smax')))),
    h('p', { class: 'sub' }, 'Values in ' + unit + '.'));
}

function setupStreams() {
  const sec = byId('streams');
  sec.replaceChildren(
    h('h2', null, 'Stream filters, sorting & formatter'),
    h('p', { class: 'hint' }, 'Each profile has its own. Everything here can also be changed on the TV (Settings → UI); a save here replaces that part for the profile.'),
    h('div', { class: 'row' }, h('label', { for: 'sp-profile' }, 'Editing'), h('select', { class: 'grow', id: 'sp-profile', 'aria-label': 'Profile' })),
    h('div', { id: 'sp-forms', hidden: true },
      h('h3', null, 'Backup'),
      h('p', { class: 'hint' }, "Export saves this profile's filters, sorting and formatter (with their on/off switches) as a JSON file. Import replaces them with a file exported earlier, from this TV or another one."),
      h('div', { class: 'row' },
        h('button', { type: 'button', id: 'sp-export' }, 'Export JSON'),
        h('button', { type: 'button', id: 'sp-import' }, 'Import JSON'),
        h('input', { type: 'file', id: 'sp-import-file', accept: 'application/json,.json', hidden: true })),
      h('h3', null, 'Filters'),
      spStage(SP_STAGES[0], 'Enable stream filtering'),
      h('div', { id: 'sp-attrs' }),
      listGroup('kw', 'Keywords', 'Comma-separated words matched in the file name, release group or indexer (a space also matches . - _).', false),
      listGroup('rg', 'Release groups', 'Comma-separated group names, e.g. FraMeSToR, NTb.', false),
      listGroup('rx', 'Regex', 'One per line, written /pattern/flags. Flags: i ignore case, n negate (matches streams the pattern does NOT match). Preferred lines may start with a name: Remux = /remux/i', true),
      rangeGroup('sd', 'Seeders', ''),
      rangeGroup('ag', 'Age', '(hours)'),
      h('div', { id: 'sp-limits' }),
      h('div', { id: 'sp-extra' }),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', type: 'button', id: 'sp-save-filters' }, 'Save filters')),
      h('h3', null, 'Sorting'),
      spStage(SP_STAGES[1], 'Enable stream sorting'),
      h('div', { id: 'sp-sort' }),
      h('h3', null, 'Formatter'),
      spStage(SP_STAGES[2], 'Enable stream formatting'),
      h('p', { class: 'hint' }, 'Templates use the AIOStreams syntax, e.g. {stream.resolution::=2160p["4K"||""]}, {stream.size::rbytes}, {?…?} groups and {tools.newLine}.'),
      spField('Formatter', h('select', { id: 'sp-fm-id' })),
      spField('Name template', spArea('sp-fm-name', 3)),
      spField('Description template', spArea('sp-fm-desc', 7)),
      spField('Try it on a release name (optional)', spInput('sp-fm-file', { placeholder: 'Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv' })),
      h('div', { class: 'preview', id: 'sp-preview' }, h('div', { class: 'sub' }, 'Torrentio'), h('div', { class: 'pv-name', id: 'sp-pv-name' }), h('div', { class: 'pv-desc', id: 'sp-pv-desc' })),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', type: 'button', id: 'sp-save-formatter' }, 'Save formatter'))));
  byId('sp-profile').addEventListener('change', (e) => { const id = Number(e.currentTarget.value); if (id) loadStreams(id); });
  byId('sp-save-filters').addEventListener('click', (e) => saveStreamFilters(e.currentTarget));
  byId('sp-save-formatter').addEventListener('click', (e) => saveStreamFormatter(e.currentTarget));
  byId('sp-export').addEventListener('click', exportStreams);
  byId('sp-import').addEventListener('click', () => byId('sp-import-file').click());
  byId('sp-import-file').addEventListener('change', (e) => importStreams(e.currentTarget));
  SP_STAGES.forEach((stage) => byId(stage[0]).addEventListener('change', (e) => saveStage(stage, e.currentTarget)));
  byId('sp-fm-id').addEventListener('change', () => { applyFormatterChoice(null); schedulePreview(); });
  ['sp-fm-name', 'sp-fm-desc', 'sp-fm-file'].forEach((id) => byId(id).addEventListener('input', schedulePreview));
}

function fillStreams() {
  const st = SP.st;
  const f = st.filters;
  if (!byId('sp-limits').childElementCount) {
    const res = st.resolutions.filter((r) => r !== 'Unknown');
    byId('sp-limits').replaceChildren(limitTable('sz', 'Size', 'GB', res), limitTable('br', 'Bitrate (estimate: size ÷ runtime)', 'Mbps', res));
    byId('sp-fm-id').replaceChildren(...st.formatters.map((x) => h('option', { value: x.id }, x.label)));
    setupStreamFilters(st);
    setupStreamSort(st);
  }
  fillStreamFilters(f);
  fillStreamSort(st.sort);
  SP_LISTS.forEach((l) => {
    byId('kw-' + l[0]).value = f.keywords[l[0]].join(', ');
    byId('rg-' + l[0]).value = f.releaseGroups[l[0]].join(', ');
    byId('rx-' + l[0]).value = l[0] === 'preferred'
      ? f.regex.preferred.map((p) => (p.name ? p.name + ' = ' : '') + p.pattern).join('\n')
      : f.regex[l[0]].join('\n');
  });
  [['sd', f.seeders], ['ag', f.ageHours]].forEach((pair) => ['required', 'excluded', 'included'].forEach((k) => {
    spShow(pair[0] + '-' + k + '-min', pair[1][k].min);
    spShow(pair[0] + '-' + k + '-max', pair[1][k].max);
  }));
  [['sz', f.size, SP_GB], ['br', f.bitrate, SP_MBPS]].forEach((t) => ['global'].concat(st.resolutions).forEach((res) => {
    if (!byId(t[0] + '-' + res + '-mmin')) return;
    const r = res === 'global' ? t[1].global : (t[1].byResolution[res] || { movies: {}, series: {} });
    spShow(t[0] + '-' + res + '-mmin', r.movies.min, t[2]);
    spShow(t[0] + '-' + res + '-mmax', r.movies.max, t[2]);
    spShow(t[0] + '-' + res + '-smin', r.series.min, t[2]);
    spShow(t[0] + '-' + res + '-smax', r.series.max, t[2]);
  }));
  SP_STAGES.forEach((stage) => { byId(stage[0]).checked = st[stage[1]]; spStageHint(stage); });
  byId('sp-fm-id').value = st.formatter.id;
  applyFormatterChoice(st.formatter);
  schedulePreview();
}

// A predefined formatter shows its (read-only) templates; Custom edits the stored ones, starting from the default.
function applyFormatterChoice(stored) {
  const id = byId('sp-fm-id').value;
  const custom = id === 'CUSTOM';
  const preset = SP.st.presets[id];
  const saved = stored || SP.st.formatter;
  byId('sp-fm-name').value = custom ? (saved.customName || preset.name) : (preset ? preset.name : '');
  byId('sp-fm-desc').value = custom ? (saved.customDescription || preset.description) : (preset ? preset.description : '');
  byId('sp-fm-name').readOnly = !custom;
  byId('sp-fm-desc').readOnly = !custom;
}

function schedulePreview() {
  clearTimeout(SP.previewTimer);
  SP.previewTimer = setTimeout(renderPreview, 300);
}

// One block per line, so no browser's handling of newlines next to a long unbreakable filename can open a gap between
// the name and the description. Spaces inside a line are kept (pre-wrap); blank edge lines are dropped.
function spPreviewText(id, text) {
  const lines = String(text || '').split('\n');
  while (lines.length && !lines[0].trim()) lines.shift();
  while (lines.length && !lines[lines.length - 1].trim()) lines.pop();
  byId(id).replaceChildren(...lines.map((line) => h('div', { class: 'pv-line' }, line)));
}

async function renderPreview() {
  const seq = ++SP.previewSeq;
  if (byId('sp-fm-id').value === 'ORIGINAL') {
    spPreviewText('sp-pv-name', 'Torrentio 4k');
    spPreviewText('sp-pv-desc', "The addon's own text, unchanged");
    return;
  }
  const body = { name: byId('sp-fm-name').value, description: byId('sp-fm-desc').value, filename: byId('sp-fm-file').value || null };
  try {
    const r = await fetch('/api/streams/format-preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const d = await r.json();
    if (seq !== SP.previewSeq) return;
    spPreviewText('sp-pv-name', r.ok ? d.name : '');
    spPreviewText('sp-pv-desc', r.ok ? d.description : d.message);
  } catch (e) {
    if (seq === SP.previewSeq) spPreviewText('sp-pv-desc', 'Could not reach the TV');
  }
}

function collectRange(prefix) {
  const out = {};
  ['required', 'excluded', 'included'].forEach((k) => { out[k] = { min: spNum(prefix + '-' + k + '-min'), max: spNum(prefix + '-' + k + '-max') }; });
  return out;
}

function collectLimits(prefix, scale) {
  const read = (res) => ({
    movies: { min: spNum(prefix + '-' + res + '-mmin', scale), max: spNum(prefix + '-' + res + '-mmax', scale) },
    series: { min: spNum(prefix + '-' + res + '-smin', scale), max: spNum(prefix + '-' + res + '-smax', scale) },
  });
  const byResolution = {};
  SP.st.resolutions.forEach((res) => {
    if (!byId(prefix + '-' + res + '-mmin')) return;
    const r = read(res);
    if (r.movies.min !== null || r.movies.max !== null || r.series.min !== null || r.series.max !== null) byResolution[res] = r;
  });
  return { global: read('global'), byResolution: byResolution };
}

async function saveStreamFilters(button) {
  const f = JSON.parse(JSON.stringify(SP.st.filters));
  collectStreamFilters(f);
  SP_LISTS.forEach((l) => {
    f.keywords[l[0]] = spCsv(byId('kw-' + l[0]).value);
    f.releaseGroups[l[0]] = spCsv(byId('rg-' + l[0]).value);
  });
  ['required', 'excluded', 'included'].forEach((k) => { f.regex[k] = spLines(byId('rx-' + k).value); });
  f.regex.preferred = spLines(byId('rx-preferred').value).map((line) => {
    const at = line.indexOf(' = ');
    return at > 0 ? { name: line.slice(0, at).trim(), pattern: line.slice(at + 3).trim() } : { name: '', pattern: line };
  });
  f.seeders = collectRange('sd');
  f.ageHours = collectRange('ag');
  f.size = collectLimits('sz', SP_GB);
  f.bitrate = collectLimits('br', SP_MBPS);
  const body = { profileId: SP.profileId, filters: f, filteringEnabled: byId('sp-on-filters').checked };
  if (await api('/streams/filters', body, button)) loadStreams(SP.profileId);
}

async function saveStreamFormatter(button) {
  const id = byId('sp-fm-id').value;
  const custom = id === 'CUSTOM';
  const formatter = {
    id: id,
    customName: custom ? byId('sp-fm-name').value : SP.st.formatter.customName,
    customDescription: custom ? byId('sp-fm-desc').value : SP.st.formatter.customDescription,
  };
  const body = { profileId: SP.profileId, formatter: formatter, formattingEnabled: byId('sp-on-formatter').checked };
  if (await api('/streams/formatter', body, button)) loadStreams(SP.profileId);
}

// The TV answers with Content-Disposition: attachment, so the browser saves the file and stays on the portal.
function exportStreams() {
  if (SP.profileId) window.location.href = '/api/streams/export?profileId=' + SP.profileId;
}

async function importStreams(input) {
  const file = input.files && input.files[0];
  input.value = '';
  if (!file || !SP.profileId) return;
  if (file.size > 64 * 1024) { toast('That file is too large to be a stream settings backup', false); return; }
  const button = byId('sp-import');
  button.disabled = true;
  try {
    const text = await file.text();
    const r = await fetch('/api/streams/import?profileId=' + SP.profileId, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text });
    let d;
    try { d = await r.json(); } catch (e) { d = { ok: false, message: 'Unexpected response (HTTP ' + r.status + ')' }; }
    toast(d.message, d.ok);
    if (d.ok) loadStreams(SP.profileId);
  } catch (e) {
    toast('Could not reach the TV', false);
  } finally {
    button.disabled = false;
  }
}

(function initStreams() {
  setupStreams();
})();
""".trimIndent()
}
