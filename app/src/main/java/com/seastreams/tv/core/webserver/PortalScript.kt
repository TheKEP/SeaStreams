package com.seastreams.tv.core.webserver

/**
 * Client-side behaviour of the Web Portal. Plain ES2017, no frameworks. Every mutation POSTs JSON
 * to `/api/…`, shows the server's message as a toast and re-renders from `/api/state`.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalScript {

    val JS = """
'use strict';
const S = { state: null, editingId: null, formReady: false, addonProfileId: null };
const submitButton = (e) => e.submitter || e.target.querySelector('button[type=submit]');
const byId = (id) => document.getElementById(id);

function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  Object.entries(props || {}).forEach(([k, v]) => {
    if (v === undefined || v === null || v === false) return;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'style') el.setAttribute('style', v);
    else if (k in el) el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  });
  kids.flat().forEach((c) => { if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : String(c)); });
  return el;
}

let toastTimer = 0;
function toast(message, ok) {
  const t = byId('toast');
  t.textContent = message;
  t.className = 'show ' + (ok ? 'ok' : 'err');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = ''; }, ok ? 3000 : 6000);
}

async function refresh() {
  try {
    const r = await fetch('/api/state', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    S.state = await r.json();
    if (!S.formReady) { S.formReady = true; setupProfileForm(); }
    render();
  } catch (e) {
    toast('Lost connection to the TV. Is seastreams open?', false);
  }
}

async function api(path, body, button) {
  if (button) button.disabled = true;
  try {
    const r = await fetch('/api' + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
    });
    let d;
    try { d = await r.json(); } catch (e) { d = { ok: false, message: 'Unexpected response (HTTP ' + r.status + ')' }; }
    toast(d.message, d.ok);
    if (d.ok) await refresh();
    return d.ok;
  } catch (e) {
    toast('Could not reach the TV', false);
    return false;
  } finally {
    if (button) button.disabled = false;
  }
}

function avatarTile(index) {
  const a = window.AVATARS[index] || window.AVATARS[0];
  return h('span', { class: 'avatar', style: 'background:' + a.c, 'aria-hidden': 'true' }, a.g);
}

function render() {
  const st = S.state;
  const status = byId('key-status');
  status.textContent = st.tmdbKeySaved ? 'Saved ' + st.tmdbKeyMasked : 'Not set';
  status.className = 'badge ' + (st.tmdbKeySaved ? 'ok' : 'err');
  renderProfiles(st);
  renderAddons(st);
  renderImportProfiles(st);
  renderStreamProfiles(st);
}

function renderProfiles(st) {
  const full = st.profiles.length >= st.maxProfiles;
  byId('profiles-hint').textContent = !st.tmdbKeySaved
    ? 'Save your TMDB key first, then create a profile.'
    : st.profiles.length + ' of ' + st.maxProfiles + ' profiles';
  const list = byId('profile-list');
  list.replaceChildren(...st.profiles.map((p) => h('li', null,
    h('div', { class: 'row' },
      avatarTile(p.avatarIndex),
      h('div', { class: 'grow' },
        h('div', { class: 'title' }, p.name),
        h('div', { class: 'sub' }, p.isKids
          ? 'Kids · up to ' + p.movieCeiling + ' / ' + p.tvCeiling + (p.allowUnrated ? ' · unrated allowed' : ' · no unrated')
          : 'Standard profile')),
      h('button', { type: 'button', class: 'icon', onclick: () => editProfile(p) }, 'Edit'),
      h('button', { type: 'button', class: 'icon danger', onclick: (e) => deleteProfile(p, e.currentTarget) }, 'Delete')))));
  const creating = S.editingId === null;
  byId('p-submit').disabled = creating && (full || !st.tmdbKeySaved);
  byId('p-submit').textContent = creating ? (full ? 'Profile limit reached' : 'Create profile') : 'Save changes';
}

function setupProfileForm() {
  const st = S.state;
  byId('p-avatars').replaceChildren(...window.AVATARS.map((a, i) => h('label', { title: 'Avatar ' + (i + 1) },
    h('input', { type: 'radio', name: 'avatar', value: String(i), checked: i === 0 }), avatarTile(i))));
  byId('p-movie').replaceChildren(...st.kidsMovieCeilings.map((c) => h('option', { value: c }, c)));
  byId('p-tv').replaceChildren(...st.kidsTvCeilings.map((c) => h('option', { value: c }, c)));
  byId('p-movie').value = 'PG';
  byId('p-tv').value = 'TV-Y7';
  byId('p-kids').addEventListener('change', () => { byId('p-kids-fields').hidden = !byId('p-kids').checked; });
  byId('p-cancel').addEventListener('click', resetProfileForm);
  byId('profile-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const checked = document.querySelector('input[name=avatar]:checked');
    const body = {
      name: byId('p-name').value,
      avatarIndex: checked ? Number(checked.value) : 0,
      isKids: byId('p-kids').checked,
      movieCeiling: byId('p-movie').value,
      tvCeiling: byId('p-tv').value,
      allowUnrated: byId('p-unrated').checked,
    };
    const path = S.editingId === null ? '/profiles' : '/profiles/' + S.editingId;
    if (await api(path, body, byId('p-submit'))) resetProfileForm();
  });
}

function editProfile(p) {
  S.editingId = p.id;
  byId('profile-form-title').textContent = 'Edit ' + p.name;
  byId('p-name').value = p.name;
  const radio = document.querySelector('input[name=avatar][value="' + p.avatarIndex + '"]');
  if (radio) radio.checked = true;
  byId('p-kids').checked = p.isKids;
  byId('p-kids-fields').hidden = !p.isKids;
  if (p.movieCeiling) byId('p-movie').value = p.movieCeiling;
  if (p.tvCeiling) byId('p-tv').value = p.tvCeiling;
  byId('p-unrated').checked = p.allowUnrated;
  byId('p-cancel').hidden = false;
  renderProfiles(S.state);
  byId('profile-form').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function resetProfileForm() {
  S.editingId = null;
  byId('profile-form').reset();
  byId('profile-form-title').textContent = 'New profile';
  byId('p-kids-fields').hidden = true;
  byId('p-movie').value = 'PG';
  byId('p-tv').value = 'TV-Y7';
  byId('p-cancel').hidden = true;
  if (S.state) renderProfiles(S.state);
}

async function deleteProfile(p, button) {
  if (!confirm('Delete profile "' + p.name + '"? Its library and watch history will be removed.')) return;
  if (await api('/profiles/' + p.id + '/delete', {}, button) && S.editingId === p.id) resetProfileForm();
}

// Whose addons are shown and edited: the picked profile, else the TV's active one, else the first.
function addonProfile(st) {
  const find = (id) => st.profiles.find((p) => p.id === id);
  const p = find(S.addonProfileId) || find(st.activeProfileId) || st.profiles[0] || null;
  S.addonProfileId = p ? p.id : null;
  return p;
}

const addonApi = (path, body, button) => api(path, Object.assign({ profileId: S.addonProfileId }, body), button);

function renderAddons(st) {
  const profile = addonProfile(st);
  const select = byId('addon-profile');
  select.replaceChildren(...(st.profiles.length
    ? st.profiles.map((p) => h('option', { value: String(p.id) }, p.name + (p.isKids ? ' (Kids)' : '')))
    : [h('option', { value: '' }, 'Create a profile first')]));
  select.value = profile ? String(profile.id) : '';
  select.disabled = !profile;
  byId('addon-submit').disabled = !profile;
  byId('addon-profile-hint').textContent = profile
    ? 'Changes apply only to ' + profile.name + '.' : 'Addons are installed per profile. Create a profile first.';
  const list = byId('addon-list');
  const entry = profile ? st.profileAddons.find((e) => e.profileId === profile.id) : null;
  const addons = entry ? entry.addons : [];
  if (!addons.length) { list.replaceChildren(h('li', { class: 'empty' }, 'No addons installed.')); return; }
  list.replaceChildren(...addons.map((a, i) => h('li', { class: a.enabled ? '' : 'disabled' },
    h('div', null,
      h('div', { class: 'title' }, a.name, ' ', a.builtIn ? h('span', { class: 'badge' }, 'Built-in') : null),
      a.name !== a.manifestName ? h('div', { class: 'sub' }, a.manifestName) : null,
      h('div', { class: 'sub' }, a.resources.join(' · ') + (a.builtIn ? '' : ' — ' + a.manifestUrl))),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'icon', 'aria-label': 'Move up', disabled: i === 0,
        onclick: (e) => addonApi('/addons/move', { manifestUrl: a.manifestUrl, offset: -1 }, e.currentTarget) }, '↑'),
      h('button', { type: 'button', class: 'icon', 'aria-label': 'Move down', disabled: i === addons.length - 1,
        onclick: (e) => addonApi('/addons/move', { manifestUrl: a.manifestUrl, offset: 1 }, e.currentTarget) }, '↓'),
      h('label', { class: 'check' },
        h('input', { type: 'checkbox', checked: a.enabled, disabled: !a.canDisable,
          onchange: (e) => addonApi('/addons/enabled', { manifestUrl: a.manifestUrl, enabled: e.currentTarget.checked }) }),
        'Enabled'),
      h('button', { type: 'button', class: 'icon', onclick: (e) => renameAddon(a, e.currentTarget) }, 'Rename'),
      a.builtIn ? null : h('button', { type: 'button', class: 'icon danger', onclick: (e) => removeAddon(a, e.currentTarget) }, 'Remove')))));
}

async function renameAddon(a, button) {
  const name = prompt('New name (leave empty to restore "' + a.manifestName + '")', a.name);
  if (name === null) return;
  await addonApi('/addons/rename', { manifestUrl: a.manifestUrl, name: name }, button);
}

async function removeAddon(a, button) {
  if (confirm('Remove addon "' + a.name + '"?')) await addonApi('/addons/remove', { manifestUrl: a.manifestUrl }, button);
}

function renderImportProfiles(st) {
  const select = byId('import-profile');
  const previous = select.value;
  select.replaceChildren(...(st.profiles.length
    ? st.profiles.map((p) => h('option', { value: String(p.id) }, p.name + (p.isKids ? ' (Kids)' : '')))
    : [h('option', { value: '' }, 'Create a profile first')]));
  if (st.profiles.some((p) => String(p.id) === previous)) select.value = previous;
}

function bindForms() {
  byId('key-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const button = submitButton(e);
    button.textContent = 'Verifying…';
    if (await api('/tmdb-key', { apiKey: byId('key-input').value }, button)) byId('key-input').value = '';
    button.textContent = 'Verify & save';
  });
  byId('addon-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (S.addonProfileId === null) { toast('Create a profile first', false); return; }
    if (await addonApi('/addons', { manifestUrl: byId('addon-url').value }, submitButton(e))) byId('addon-url').value = '';
  });
  byId('addon-profile').addEventListener('change', (e) => {
    S.addonProfileId = Number(e.currentTarget.value) || null;
    if (S.state) renderAddons(S.state);
  });
  byId('import-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const profileId = Number(byId('import-profile').value);
    if (!profileId) { toast('Create a profile first', false); return; }
    const button = submitButton(e);
    button.textContent = 'Importing…';
    const body = { url: byId('import-url').value, profileId: profileId, target: byId('import-target').value };
    if (await api('/library/import', body, button)) byId('import-url').value = '';
    button.textContent = 'Import';
  });
}

(function init() {
  bindForms();
  refresh();
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
})();
""".trimIndent()
}
