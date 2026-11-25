package com.seastreams.tv.core.webserver

/**
 * The portal's stream filter groups the TV offers as choices (PRD §13), loaded before [PortalStreamScript], which calls
 * [setupStreamFilters], [fillStreamFilters] and [collectStreamFilters]. Per option-backed attribute (Stream type,
 * Resolution, Quality, Encode, Visual tag, Audio tag, Audio channel, Language, Subtitle): *Required*, *Excluded* and
 * *Included* as checkbox grids, *Preferred* as an ordered list with move up / move down / remove and an add picker.
 * Also the result limits (total, per resolution, per quality, per addon; empty = unlimited) and the deduplicator
 * (on/off, compare mode, what to match by). The vocabularies come from `GET /api/streams`, so nothing is hard-coded.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalStreamFilterScript {

    val JS = """
const SPF = { groups: {} };
const SPF_HINTS = { required: 'anything else is removed', excluded: 'these are removed', included: 'kept regardless of every other filter', preferred: 'ranked in this order by Stream sorting' };
const SPF_LIMITS = [['global', 'Total results'], ['perResolution', 'Per resolution'], ['perQuality', 'Per quality'], ['perAddon', 'Per addon']];

function spfAttributes() { return SP.st.attributes.filter((a) => a.options && a.field); }

// Containers are built once per page (the vocabularies never change); fillStreamFilters refreshes the values.
function setupStreamFilters(st) {
  byId('sp-attrs').replaceChildren(...spfAttributes().map((a) => h('details', { id: 'spf-' + a.field },
    h('summary', null, a.label), h('div', { id: 'spf-body-' + a.field }))));
  const mode = h('select', { id: 'spf-dd-mode' }, st.dedupModes.map((m) => h('option', { value: m.id }, m.label)));
  byId('sp-extra').replaceChildren(
    h('details', null, h('summary', null, 'Result limits'),
      h('p', { class: 'hint' }, 'Caps applied after sorting. Each counts on its own. Empty or 0 = unlimited.'),
      h('div', { class: 'perf-grid' }, SPF_LIMITS.map((l) => spField(l[1], h('input', { type: 'number', id: 'spf-lim-' + l[0], min: '0', step: '1', inputmode: 'numeric' }))))),
    h('details', null, h('summary', null, 'Deduplicator'),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', id: 'spf-dd-on' }), 'Remove duplicates'),
      spField('Compare', mode),
      h('p', { class: 'list-title' }, 'Match by'),
      h('div', { class: 'choice-grid' }, st.dedupKeys.map((k) =>
        h('label', { class: 'check' }, h('input', { type: 'checkbox', id: 'spf-dd-' + k.id }), h('span', null, k.label))))));
}

function fillStreamFilters(f) {
  SPF.groups = {};
  spfAttributes().forEach((a) => {
    const src = f[a.field] || {};
    const keep = (values) => (values || []).filter((v) => a.options.includes(v));
    SPF.groups[a.field] = { required: keep(src.required), excluded: keep(src.excluded), included: keep(src.included), preferred: keep(src.preferred) };
    renderFilterGroup(a);
  });
  SPF_LIMITS.forEach((l) => spShow('spf-lim-' + l[0], f.limits[l[0]]));
  byId('spf-dd-on').checked = f.dedup.enabled;
  byId('spf-dd-mode').value = f.dedup.mode;
  SP.st.dedupKeys.forEach((k) => { byId('spf-dd-' + k.id).checked = f.dedup.keys.includes(k.id); });
}

// Writes every option-backed group, the limits and the deduplicator into f (a copy of the stored filters).
function collectStreamFilters(f) {
  spfAttributes().forEach((a) => {
    const g = SPF.groups[a.field];
    f[a.field] = { required: g.required.slice(), excluded: g.excluded.slice(), included: g.included.slice(), preferred: g.preferred.slice() };
  });
  SPF_LIMITS.forEach((l) => { f.limits[l[0]] = spNum('spf-lim-' + l[0]); });
  f.dedup = {
    enabled: byId('spf-dd-on').checked,
    mode: byId('spf-dd-mode').value,
    keys: SP.st.dedupKeys.filter((k) => byId('spf-dd-' + k.id).checked).map((k) => k.id),
  };
}

function filterSummary(g) {
  const parts = SP.st.filterLists.filter((l) => g[l.id].length).map((l) => l.label + ' ' + g[l.id].length);
  return parts.length ? parts.join(' · ') : 'Not filtered';
}

function renderFilterGroup(a) {
  const g = SPF.groups[a.field];
  const summary = byId('spf-' + a.field).querySelector('summary');
  summary.textContent = a.label + ' — ' + filterSummary(g);
  const parts = [];
  SP.st.filterLists.forEach((l) => {
    parts.push(h('p', { class: 'list-title' }, l.label + ': ' + SPF_HINTS[l.id]));
    parts.push(l.id === 'preferred' ? preferredEditor(a, g) : choiceGrid(a, g, l.id));
  });
  byId('spf-body-' + a.field).replaceChildren(...parts);
}

function choiceGrid(a, g, list) {
  return h('div', { class: 'choice-grid' }, a.options.map((opt) => {
    const box = h('input', { type: 'checkbox', checked: g[list].includes(opt) });
    box.addEventListener('change', () => {
      g[list] = box.checked ? g[list].concat([opt]) : g[list].filter((v) => v !== opt);
      byId('spf-' + a.field).querySelector('summary').textContent = a.label + ' — ' + filterSummary(g);
    });
    return h('label', { class: 'check' }, box, h('span', null, opt));
  }));
}

// After a change the group is rebuilt; focus goes back to the same control of the moved value, so keyboard users
// can keep pressing it.
function preferredEditor(a, g) {
  const list = g.preferred;
  const redraw = (value, action) => {
    renderFilterGroup(a);
    const target = byId('spf-body-' + a.field).querySelector('[data-value="' + CSS.escape(value) + '"][data-action="' + action + '"]');
    if (target && !target.disabled) target.focus();
  };
  const button = (value, action, label, text, disabled, run) => h('button', {
    type: 'button', class: 'icon', 'aria-label': label, 'data-value': value, 'data-action': action, disabled: disabled,
    onclick: () => { run(); redraw(value, action); },
  }, text);
  const rows = list.map((v, i) => h('li', null,
    h('span', { class: 'grow' }, '#' + (i + 1) + ' ' + v),
    button(v, 'up', 'Move ' + v + ' up', '↑', i === 0, () => spfMove(list, i, -1)),
    button(v, 'down', 'Move ' + v + ' down', '↓', i === list.length - 1, () => spfMove(list, i, 1)),
    button(v, 'remove', 'Remove ' + v, '✕', false, () => list.splice(i, 1))));
  const rest = a.options.filter((o) => !list.includes(o));
  const add = h('select', { 'aria-label': 'Add a preferred ' + a.label.toLowerCase() },
    h('option', { value: '' }, '+ Add a preferred value…'), rest.map((o) => h('option', { value: o }, o)));
  add.addEventListener('change', () => {
    if (!add.value) return;
    list.push(add.value);
    renderFilterGroup(a);
  });
  return h('div', null,
    list.length ? h('ol', { class: 'ordered' }, rows) : h('p', { class: 'sub' }, 'None yet.'),
    rest.length ? h('div', { class: 'row', style: 'margin-top:6px' }, add) : null);
}

function spfMove(list, index, offset) {
  const to = index + offset;
  if (to < 0 || to >= list.length) return;
  list.splice(to, 0, list.splice(index, 1)[0]);
}
""".trimIndent()
}
