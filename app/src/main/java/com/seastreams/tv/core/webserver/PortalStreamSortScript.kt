package com.seastreams.tv.core.webserver

/**
 * The portal's `Sorting` editor (PRD §13), loaded before [PortalStreamScript], which calls [setupStreamSort] and
 * [fillStreamSort]. One tab per sort list (Global, Movies, Series, Anime). A tab shows the criteria in use, in sort
 * order, each with move up / move down, its direction (Descending ⇄ Ascending) and remove; below them every unused
 * criterion with `Add` (it joins the end, as on the TV). `Save sorting` posts the whole sort to `POST /api/streams/sort`.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalStreamSortScript {

    val JS = """
const SPS = { sort: null, scope: 'global' };

function setupStreamSort(st) {
  byId('sp-sort').replaceChildren(
    h('div', { class: 'tabs', id: 'sps-tabs', role: 'group', 'aria-label': 'Sort list' }),
    h('p', { class: 'hint', id: 'sps-hint' }),
    h('ol', { class: 'ordered', id: 'sps-used' }),
    h('p', { class: 'list-title' }, 'Not used'),
    h('ol', { class: 'ordered', id: 'sps-unused' }),
    h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'primary', type: 'button', id: 'sp-save-sort' }, 'Save sorting')));
  byId('sp-save-sort').addEventListener('click', (e) => saveStreamSort(e.currentTarget));
}

function fillStreamSort(sort) {
  SPS.sort = JSON.parse(JSON.stringify(sort));
  renderSort();
}

function sortKeyInfo(id) { return SP.st.sortKeys.find((k) => k.id === id) || { id: id, label: id, description: '' }; }
function sortDirectionLabel(id) { return (SP.st.sortDirections.find((d) => d.id === id) || { label: id }).label; }

function renderSort(focusKey, focusAction) {
  byId('sps-tabs').replaceChildren(...SP.st.sortScopes.map((s) => h('button', {
    type: 'button', 'aria-pressed': String(s.id === SPS.scope),
    onclick: () => { SPS.scope = s.id; renderSort(); },
  }, s.label + ' (' + SPS.sort[s.id].length + ')')));
  const list = SPS.sort[SPS.scope];
  const scopeLabel = SP.st.sortScopes.find((s) => s.id === SPS.scope).label;
  byId('sps-hint').textContent = list.length
    ? scopeLabel + ': streams are ranked by these criteria, top first.'
    : (SPS.scope === 'global' ? 'Global is empty: streams keep the addon order.' : scopeLabel + ' is empty: the Global list applies.');
  const button = (key, action, label, text, disabled, run) => h('button', {
    type: 'button', class: 'icon', 'aria-label': label, 'data-key': key, 'data-action': action, disabled: disabled,
    onclick: () => { run(); renderSort(key, action); },
  }, text);
  byId('sps-used').replaceChildren(...list.map((c, i) => {
    const info = sortKeyInfo(c.key);
    const direction = c.direction || 'DESC';
    return h('li', null,
      h('span', { class: 'grow' }, '#' + (i + 1) + ' ' + info.label),
      button(c.key, 'up', 'Move ' + info.label + ' up', '↑', i === 0, () => spsMove(list, i, -1)),
      button(c.key, 'down', 'Move ' + info.label + ' down', '↓', i === list.length - 1, () => spsMove(list, i, 1)),
      button(c.key, 'direction', 'Direction of ' + info.label + ': ' + sortDirectionLabel(direction), sortDirectionLabel(direction), false,
        () => { c.direction = direction === 'DESC' ? 'ASC' : 'DESC'; }),
      button(c.key, 'remove', 'Remove ' + info.label, '✕', false, () => list.splice(i, 1)),
      h('span', { class: 'sub' }, info.description));
  }));
  if (!list.length) byId('sps-used').replaceChildren(h('li', null, h('span', { class: 'sub' }, 'No criteria in this list.')));
  const unused = SP.st.sortKeys.filter((k) => !list.some((c) => c.key === k.id));
  byId('sps-unused').replaceChildren(...unused.map((k) => h('li', null,
    h('span', { class: 'grow' }, k.label),
    button(k.id, 'add', 'Add ' + k.label, '+ Add', false, () => list.push({ key: k.id, direction: 'DESC' })),
    h('span', { class: 'sub' }, k.description))));
  // Keep keyboard focus on the control just used; an added criterion's focus moves to its Remove button.
  if (focusKey) {
    const action = focusAction === 'add' ? 'remove' : (focusAction === 'remove' ? 'add' : focusAction);
    const target = byId('sp-sort').querySelector('[data-key="' + focusKey + '"][data-action="' + action + '"]');
    if (target && !target.disabled) target.focus();
  }
}

function spsMove(list, index, offset) {
  const to = index + offset;
  if (to < 0 || to >= list.length) return;
  list.splice(to, 0, list.splice(index, 1)[0]);
}

async function saveStreamSort(button) {
  const body = { profileId: SP.profileId, sort: SPS.sort, sortingEnabled: byId('sp-on-sort').checked };
  if (await api('/streams/sort', body, button)) loadStreams(SP.profileId);
}
""".trimIndent()
}
