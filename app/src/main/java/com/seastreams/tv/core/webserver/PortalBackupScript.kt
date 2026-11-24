package com.seastreams.tv.core.webserver

/**
 * The portal's `Backup & restore` section (PRD §8), appended after [PortalScript] and reusing its `h`, `byId` and `toast`
 * helpers. `Download Backup` navigates to `GET /api/backup/export` (the TV answers with an attachment, so the browser
 * saves the file and stays here); `Restore` posts a picked file's text to `POST /api/backup/restore`. The file's own
 * scope decides what a restore does: a profile backup goes into the selected profile, a full one restores every profile
 * it holds. The section is built once; [renderBackupProfiles] only refreshes the profile list.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalBackupScript {

    val JS = """
const BK = { profileId: null, maxBytes: 16 * 1024 * 1024 };

function setupBackup() {
  const sec = byId('backup');
  if (!sec) return;
  sec.replaceChildren(
    h('h2', null, 'Backup & restore'),
    h('p', { class: 'hint' }, 'Save a .json backup of one profile or of the whole app (every profile with its settings, addons, stream rules, Library and watch progress, plus Usenet and Debrid sources), and restore it on this TV or another one.'),
    h('div', { class: 'row' }, h('label', { for: 'bk-profile' }, 'Profile'), h('select', { class: 'grow', id: 'bk-profile', 'aria-label': 'Profile to back up or restore into' })),
    h('h3', null, 'Back up'),
    h('label', { class: 'check' }, h('input', { type: 'radio', name: 'bk-scope', id: 'bk-scope-profile', value: 'profile', checked: true }), h('span', { id: 'bk-scope-profile-label' }, 'Current profile')),
    h('label', { class: 'check' }, h('input', { type: 'radio', name: 'bk-scope', id: 'bk-scope-app', value: 'app' }), h('span', null, 'Full app backup (all profiles & sources)')),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', id: 'bk-creds' }), h('span', null, 'Include credentials (TMDB key, passwords, API keys & tokens)')),
    h('p', { class: 'hint warn', id: 'bk-creds-warn', hidden: true }, 'The file will hold your TMDB key, passwords, API keys and tokens in plain text. Keep it private and delete it once restored.'),
    h('div', { class: 'row' }, h('button', { class: 'primary', type: 'button', id: 'bk-download' }, 'Download Backup (.json)')),
    h('h3', null, 'Restore'),
    h('p', { class: 'hint', id: 'bk-restore-hint' }, 'Pick a backup file.'),
    h('div', { class: 'row' },
      h('input', { type: 'file', class: 'grow', id: 'bk-file', accept: 'application/json,.json' }),
      h('button', { type: 'button', id: 'bk-restore', disabled: true }, 'Restore from Backup (.json)')));
  byId('bk-profile').addEventListener('change', (e) => { BK.profileId = Number(e.currentTarget.value) || null; updateBackupLabels(); });
  byId('bk-creds').addEventListener('change', (e) => { byId('bk-creds-warn').hidden = !e.currentTarget.checked; });
  byId('bk-download').addEventListener('click', downloadBackup);
  byId('bk-file').addEventListener('change', inspectBackupFile);
  byId('bk-restore').addEventListener('click', (e) => restoreBackup(e.currentTarget));
}

function renderBackupProfiles(st) {
  const select = byId('bk-profile');
  if (!select) return;
  select.replaceChildren(...(st.profiles.length
    ? st.profiles.map((p) => h('option', { value: String(p.id) }, p.name + (p.isKids ? ' (Kids)' : '')))
    : [h('option', { value: '' }, 'Create a profile first')]));
  const keep = st.profiles.find((p) => p.id === BK.profileId) || st.profiles.find((p) => p.id === st.activeProfileId) || st.profiles[0];
  select.disabled = !keep;
  BK.profileId = keep ? keep.id : null;
  if (keep) select.value = String(keep.id);
  updateBackupLabels();
}

function backupProfileName() {
  const p = S.state && S.state.profiles.find((x) => x.id === BK.profileId);
  return p ? p.name : null;
}

function updateBackupLabels() {
  const name = backupProfileName();
  byId('bk-scope-profile-label').textContent = 'Current profile (' + (name || 'none') + ')';
  updateRestoreHint();
}

// Reads the picked file's scope so the hint says exactly what Restore will do.
async function inspectBackupFile() {
  const input = byId('bk-file');
  const file = input.files && input.files[0];
  BK.fileScope = null;
  BK.fileError = null;
  if (file && file.size > BK.maxBytes) BK.fileError = 'That file is too large to be a seastreams backup.';
  else if (file) {
    try { BK.fileScope = JSON.parse(await file.text()).scope || null; } catch (e) { BK.fileError = "That file isn't valid JSON."; }
  }
  updateRestoreHint();
}

function updateRestoreHint() {
  const input = byId('bk-file');
  const hint = byId('bk-restore-hint');
  const button = byId('bk-restore');
  if (!input || !hint) return;
  const picked = input.files && input.files[0];
  const name = backupProfileName();
  let text = 'Pick a backup file. A profile backup restores into the selected profile; a full app backup restores every profile it holds (matched by name, new ones are created).';
  if (picked && BK.fileError) text = BK.fileError;
  else if (picked && BK.fileScope === 'app') text = "This full app backup restores all profiles in the file, matched to this TV's profiles by name, plus the Usenet and Debrid sources.";
  else if (picked && BK.fileScope === 'profile') text = name ? 'This profile backup restores settings, addons, stream rules, Library and watch progress into ' + name + ' (its name and Kids limits stay; titles already saved and newer progress are kept), plus the Usenet and Debrid sources.' : 'Create a profile first to restore this profile backup into.';
  else if (picked) text = "This doesn't look like a seastreams backup.";
  hint.textContent = text;
  button.disabled = !picked || Boolean(BK.fileError) || (BK.fileScope === 'profile' && !BK.profileId);
}

function downloadBackup() {
  const scope = byId('bk-scope-app').checked ? 'app' : 'profile';
  if (scope === 'profile' && !BK.profileId) { toast('Create a profile first', false); return; }
  const creds = byId('bk-creds').checked ? 'true' : 'false';
  // The TV answers with Content-Disposition: attachment, so the browser saves the file and stays on the portal.
  window.location.href = '/api/backup/export?scope=' + scope + '&profileId=' + (BK.profileId || '') + '&includeCredentials=' + creds;
}

async function restoreBackup(button) {
  const input = byId('bk-file');
  const file = input.files && input.files[0];
  if (!file) return;
  if (file.size > BK.maxBytes) { toast('That file is too large to be a seastreams backup', false); return; }
  button.disabled = true;
  try {
    const text = await file.text();
    const r = await fetch('/api/backup/restore?profileId=' + (BK.profileId || ''), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text });
    let d;
    try { d = await r.json(); } catch (e) { d = { ok: false, message: 'Unexpected response (HTTP ' + r.status + ')' }; }
    toast(d.message, d.ok);
    if (d.ok) {
      input.value = '';
      BK.fileScope = null;
      await refresh();
      if (typeof SP !== 'undefined' && SP.profileId) loadStreams(SP.profileId);
    }
  } catch (e) {
    toast('Could not reach the TV', false);
  } finally {
    updateRestoreHint();
  }
}

(function initBackup() {
  setupBackup();
})();
""".trimIndent()
}
