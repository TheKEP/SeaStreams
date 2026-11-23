package com.seastreams.tv.core.webserver

/**
 * The portal's `Debrid providers` section (PRD §14), appended after [PortalUsenetScript] and reusing its `h`, `byId`,
 * `toast`, `api`, `field`, `textInput` and `checkbox` helpers. Keys and tokens are write-only: the TV only answers
 * whether a credential is stored and usable. Code sign-in shows the code, the service's link page and a countdown
 * while the TV polls the service; the panel follows `GET /api/debrid/auth/{session}` until it ends.
 *
 * Note: this is a Kotlin raw string, so the script deliberately never uses the dollar sign.
 */
object PortalDebridScript {

    val JS = """
const D = { st: null, editing: null, auth: null, timer: 0 };
const AUTH_TEXT = {
  pending: 'Waiting for you to approve the code…', linked: 'Signed in. The provider is on your TV now.',
  expired: 'The code expired. Start again for a new one.', denied: 'The sign-in was denied. Start again to retry.',
  cancelled: 'Sign-in cancelled.', failed: 'The service refused the sign-in. Start again, or paste a key instead.',
};

async function loadDebrid() {
  try {
    const r = await fetch('/api/debrid', { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    D.st = await r.json();
    renderDebrid();
  } catch (e) {
    toast('Could not load Debrid providers from the TV', false);
  }
}

async function dApi(path, body, button) {
  const ok = await api(path, body, button);
  if (ok) await loadDebrid();
  return ok;
}

function service(kind) { return D.st ? D.st.services.find((s) => s.kind === kind) : null; }

function debridRow(p) {
  const path = '/debrid/providers';
  return h('li', { class: p.enabled ? '' : 'disabled' },
    h('div', null, h('div', { class: 'title' }, p.name), h('div', { class: 'sub' }, p.credentialLabel + (p.enabled ? '' : ' · disabled'))),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'icon', onclick: (e) => dApi(path + '/enabled', { id: p.id, enabled: !p.enabled }, e.currentTarget) }, p.enabled ? 'Disable' : 'Enable'),
      h('button', { type: 'button', class: 'icon', onclick: (e) => dApi(path + '/move', { id: p.id, offset: -1 }, e.currentTarget) }, '↑'),
      h('button', { type: 'button', class: 'icon', onclick: (e) => dApi(path + '/move', { id: p.id, offset: 1 }, e.currentTarget) }, '↓'),
      h('button', { type: 'button', class: 'icon', onclick: (e) => dApi(path + '/test', { id: p.id }, e.currentTarget) }, 'Test'),
      h('button', { type: 'button', class: 'icon', onclick: () => editDebrid(p, true) }, 'Edit'),
      h('button', { type: 'button', class: 'icon danger', onclick: (e) => removeDebrid(p, e.currentTarget) }, 'Remove')));
}

async function removeDebrid(p, button) {
  if (!confirm('Remove ' + p.name + '? Its key or sign-in is deleted from the TV.')) return;
  if (await dApi('/debrid/providers/remove', { id: p.id }, button)) editDebrid(null, false);
}

function setupDebrid() {
  byId('debrid').append(
    h('h2', null, 'Debrid providers'),
    h('p', { class: 'hint' }, 'Shared by every profile. seastreams only plays files already in your Debrid accounts. Keys and tokens are stored encrypted on the TV and are never shown again; leave the key blank when editing to keep it.'),
    check('Enable Debrid (search your Debrid libraries for streams)', checkbox('d-enabled')),
    h('ul', { class: 'list', id: 'd-list' }),
    h('form', { id: 'd-form', autocomplete: 'off', style: 'margin-top:12px' },
      h('h4', { id: 'd-title', style: 'margin:0' }, 'Add a provider'),
      field('Service', h('select', { id: 'd-kind' })),
      field('Name (optional)', textInput('d-name', { maxLength: 40 })),
      field('API key or token', h('input', { type: 'password', id: 'd-key', autocomplete: 'off', spellcheck: false })),
      h('p', { class: 'sub' }, h('a', { id: 'd-keylink', target: '_blank', rel: 'noopener noreferrer' }, 'Where to find it')),
      h('div', { class: 'row' }, h('button', { class: 'primary', type: 'submit', id: 'd-save' }, 'Add provider'),
        h('button', { type: 'button', onclick: (e) => api('/debrid/providers/test', debridTestBody(), e.currentTarget) }, 'Test'),
        h('button', { type: 'button', id: 'd-code', onclick: (e) => startAuth(byId('d-kind').value, e.currentTarget) }, 'Sign in with a code'),
        h('button', { type: 'button', onclick: () => editDebrid(null, false) }, 'Cancel'))),
    h('div', { id: 'd-auth', class: 'card', hidden: true, style: 'margin-top:12px' },
      h('h4', { id: 'd-auth-title', style: 'margin:0' }),
      h('p', { class: 'sub' }, 'Open this page on any device, sign in to the service and enter the code:'),
      h('p', null, h('a', { id: 'd-auth-link', target: '_blank', rel: 'noopener noreferrer' })),
      h('p', { id: 'd-auth-code', style: 'font-size:28px;font-weight:700;letter-spacing:3px;margin:6px 0' }),
      h('p', { class: 'sub', id: 'd-auth-status', role: 'status' }),
      h('div', { class: 'row' },
        h('button', { type: 'button', id: 'd-auth-cancel', onclick: (e) => cancelAuth(e.currentTarget) }, 'Cancel'),
        h('button', { type: 'button', id: 'd-auth-again', hidden: true, onclick: (e) => startAuth(D.auth ? D.auth.kind : byId('d-kind').value, e.currentTarget) }, 'Start again'))));
  byId('d-enabled').addEventListener('change', (e) => dApi('/sources/toggles', { debrid: e.target.checked }, null));
  byId('d-kind').addEventListener('change', () => updateDebridKind());
  byId('d-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const body = { id: D.editing ? D.editing.id : null, kind: byId('d-kind').value, apiKey: byId('d-key').value || null, name: byId('d-name').value || null };
    if (await dApi('/debrid/providers', body, submitButton(e))) editDebrid(null, false);
  });
}

function debridTestBody() {
  const key = byId('d-key').value;
  return key ? { kind: byId('d-kind').value, apiKey: key } : { id: D.editing ? D.editing.id : null };
}

// Edit (p) or Add (null): every field is reset, so no key of another row ever lingers.
function editDebrid(p, scroll) {
  D.editing = p;
  byId('d-title').textContent = p ? 'Edit ' + p.name : 'Add a provider';
  byId('d-save').textContent = p ? 'Save changes' : 'Add provider';
  byId('d-kind').disabled = !!p;
  if (p) byId('d-kind').value = p.kind;
  byId('d-name').value = p ? p.name : '';
  byId('d-key').value = '';
  byId('d-key').placeholder = p ? 'Stored — leave blank to keep' : '';
  updateDebridKind();
  if (scroll) byId('d-form').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function updateDebridKind() {
  const s = service(byId('d-kind').value);
  const link = byId('d-keylink');
  link.href = s ? s.keyPageUrl : '#';
  link.textContent = s ? 'Find your ' + s.name + ' ' + s.keyLabel : '';
  byId('d-code').hidden = !(s && s.deviceSignIn);
  byId('d-code').textContent = s && s.deviceSignIn ? 'Sign in with a ' + s.deviceSignIn : 'Sign in with a code';
}

async function startAuth(kind, button) {
  if (button) button.disabled = true;
  try {
    const r = await fetch('/api/debrid/auth/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind: kind }) });
    const d = await r.json();
    if (!r.ok) { toast(d.message || 'Could not start the sign-in', false); return; }
    D.auth = d;
    D.auth.deadline = Date.now() + d.expiresInSeconds * 1000;
    renderAuth();
    pollAuth();
  } catch (e) {
    toast('Could not reach the TV', false);
  } finally {
    if (button) button.disabled = false;
  }
}

async function pollAuth() {
  clearTimeout(D.timer);
  if (!D.auth || D.auth.status !== 'pending') return;
  try {
    const r = await fetch('/api/debrid/auth/' + encodeURIComponent(D.auth.session), { cache: 'no-store' });
    if (r.ok) {
      const d = await r.json();
      D.auth.status = d.status;
      if (d.status === 'linked') { toast((service(d.kind) || { name: 'Provider' }).name + ' signed in', true); await loadDebrid(); }
    } else if (r.status === 404) {
      D.auth.status = 'cancelled';
    }
  } catch (e) {
    // The TV may be busy for a moment; the next tick tries again.
  }
  renderAuth();
  if (D.auth && D.auth.status === 'pending') D.timer = setTimeout(pollAuth, 2000);
}

async function cancelAuth(button) {
  if (!D.auth) return;
  clearTimeout(D.timer);
  await api('/debrid/auth/cancel', { session: D.auth.session }, button);
  D.auth.status = 'cancelled';
  renderAuth();
}

function renderAuth() {
  const a = D.auth;
  const box = byId('d-auth');
  box.hidden = !a;
  if (!a) return;
  const s = service(a.kind);
  byId('d-auth-title').textContent = 'Sign in to ' + (s ? s.name : a.kind);
  const link = byId('d-auth-link');
  link.href = a.verificationUrl; link.textContent = a.verificationUrl;
  byId('d-auth-code').textContent = a.userCode;
  const left = Math.max(0, Math.round((a.deadline - Date.now()) / 1000));
  const clock = Math.floor(left / 60) + ':' + String(left % 60).padStart(2, '0');
  byId('d-auth-status').textContent = (AUTH_TEXT[a.status] || a.status) + (a.status === 'pending' ? ' Code expires in ' + clock + '.' : '');
  byId('d-auth-cancel').hidden = a.status !== 'pending';
  byId('d-auth-again').hidden = a.status === 'pending' || a.status === 'linked';
}

function renderDebrid() {
  const st = D.st;
  byId('d-enabled').checked = st.enabled;
  const kind = byId('d-kind');
  if (kind.options.length === 0) {
    st.services.forEach((s) => kind.append(h('option', { value: s.kind }, s.name)));
    updateDebridKind();
  }
  if (D.editing) {
    const still = st.providers.find((p) => p.id === D.editing.id);
    if (still) D.editing = still; else editDebrid(null, false);
  }
  byId('d-list').replaceChildren(...(st.providers.length ? st.providers.map(debridRow) : [h('li', { class: 'sub' }, 'No Debrid providers yet')]));
}

(function initDebrid() {
  setupDebrid();
  loadDebrid();
  setInterval(() => { if (D.auth && D.auth.status === 'pending') renderAuth(); }, 1000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) loadDebrid(); });
})();
""".trimIndent()
}
