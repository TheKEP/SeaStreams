package com.seastreams.tv.core.webserver

import com.seastreams.tv.domain.model.ProfileAvatar

/**
 * The mobile-first single-page Web Portal. Markup and styles live here; behaviour lives in
 * [PortalScript]. All dynamic content is inserted via `textContent` (never `innerHTML`), because
 * addon names/descriptions come from third-party manifests.
 */
object PortalHtmlTemplate {

    fun render(): String {
        val avatars = ProfileAvatar.entries.joinToString(",", "[", "]") { "{\"g\":\"${it.glyph}\",\"c\":\"${it.cssColor}\"}" }
        return HTML
            .replace("/*AVATARS*/", "window.AVATARS = $avatars;")
            .replace("/*SCRIPT*/", PortalScript.JS + "\n" + PortalUsenetScript.JS + "\n" + PortalDebridScript.JS + "\n" + PortalStreamScript.JS +
                    "\n" + PortalBackupScript.JS,
            )
    }

    private val HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="dark">
<title>seastreams Portal</title>
<style>
:root {
  --bg: #0B0D12; --surface: #161A23; --surface-2: #1F2430; --line: #2A3040;
  --text: #FFFFFF; --muted: #A0A8B8; --accent: #E5E9F0; --ok: #3DD68C; --err: #FF6B6B;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text);
  font: 16px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
header { padding: 20px 16px 8px; max-width: 760px; margin: 0 auto; }
header h1 { margin: 0; font-size: 24px; letter-spacing: .2px; }
header p { margin: 4px 0 0; color: var(--muted); font-size: 14px; }
main { max-width: 760px; margin: 0 auto; padding: 8px 16px 96px; display: grid; gap: 16px; }
section { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 16px; }
section h2 { margin: 0 0 4px; font-size: 18px; }
section > p.hint { margin: 0 0 12px; color: var(--muted); font-size: 14px; }
form { display: grid; gap: 10px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.row > .grow { flex: 1 1 180px; min-width: 0; }
label.field { display: grid; gap: 4px; font-size: 13px; color: var(--muted); }
input[type=text], input[type=url], input[type=password], input[type=number], select, textarea {
  width: 100%; background: var(--bg); color: var(--text); border: 1px solid var(--line);
  border-radius: 10px; padding: 11px 12px; font: inherit; min-width: 0; }
input:focus, select:focus, button:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
button { font: inherit; border-radius: 999px; border: 1px solid var(--line); background: var(--surface-2);
  color: var(--text); padding: 9px 16px; cursor: pointer; white-space: nowrap; }
button.primary { background: var(--accent); color: #0B0D12; border-color: var(--accent); font-weight: 600; }
button.danger { color: var(--err); }
p.warn { color: var(--err); }
button.icon { padding: 7px 12px; }
button:disabled { opacity: .45; cursor: default; }
.check { display: flex; align-items: center; gap: 8px; font-size: 15px; }
.check input { width: 20px; height: 20px; accent-color: var(--accent); }
.badge { display: inline-block; font-size: 12px; padding: 2px 8px; border-radius: 999px;
  background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); }
.badge.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 40%, transparent); }
.badge.err { color: var(--err); border-color: color-mix(in srgb, var(--err) 40%, transparent); }
ul.list { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 8px; }
ul.list li { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 12px;
  display: grid; gap: 8px; }
ul.list li.disabled .title { color: var(--muted); text-decoration: line-through; }
.title { font-weight: 600; overflow-wrap: anywhere; }
.sub { color: var(--muted); font-size: 13px; overflow-wrap: anywhere; }
.avatar { width: 40px; height: 40px; border-radius: 10px; display: grid; place-items: center; font-size: 22px; flex: none; }
.avatars { display: flex; gap: 8px; flex-wrap: wrap; }
.avatars label { cursor: pointer; }
.avatars input { position: absolute; opacity: 0; pointer-events: none; }
.avatars input:checked + .avatar { outline: 3px solid var(--accent); outline-offset: 2px; }
.avatars input:focus-visible + .avatar { outline: 3px dashed var(--accent); outline-offset: 2px; }
.kids-only[hidden] { display: none; }
.empty { color: var(--muted); font-size: 14px; margin: 12px 0 0; }
#sources h3 { margin: 18px 0 0; font-size: 16px; }
#sources details { margin-top: 10px; }
#sources summary { cursor: pointer; color: var(--muted); }
.perf-grid { display: grid; gap: 8px; margin-top: 8px; }
#streams h3 { margin: 18px 0 0; font-size: 16px; }
#streams details { margin-top: 10px; }
#streams summary { cursor: pointer; color: var(--muted); }
textarea { resize: vertical; font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
table.ranges { width: 100%; border-collapse: collapse; font-size: 13px; }
table.ranges th { color: var(--muted); font-weight: 500; text-align: left; padding: 4px 4px 4px 0; }
table.ranges td { padding: 3px 4px 3px 0; }
table.ranges input { padding: 7px 8px; }
.preview { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
.preview .pv-name { font-weight: 700; white-space: pre-wrap; overflow-wrap: anywhere; }
.preview .pv-desc { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 4px; font-size: 14px; }
#toast { position: fixed; left: 16px; right: 16px; bottom: max(16px, env(safe-area-inset-bottom));
  max-width: 560px; margin: 0 auto; padding: 12px 16px; border-radius: 12px; background: var(--surface-2);
  border: 1px solid var(--line); box-shadow: 0 8px 24px rgba(0,0,0,.4); opacity: 0;
  transform: translateY(12px); transition: .2s; pointer-events: none; }
#toast.show { opacity: 1; transform: none; }
#toast.ok { border-color: var(--ok); } #toast.err { border-color: var(--err); }
</style>
</head>
<body>
<header>
  <h1>seastreams</h1>
  <p>Changes you make here appear on your TV instantly.</p>
</header>
<main>
  <section id="tmdb">
    <h2>TMDB API key <span id="key-status" class="badge">…</span></h2>
    <p class="hint">Required. Create a free key at themoviedb.org → Settings → API, then paste the API key or read access token.</p>
    <form id="key-form" autocomplete="off">
      <div class="row">
        <input class="grow" type="text" id="key-input" placeholder="Paste your TMDB API key" autocapitalize="off" spellcheck="false" required>
        <button class="primary" type="submit">Verify &amp; save</button>
      </div>
    </form>
  </section>

  <section id="profiles">
    <h2>Profiles</h2>
    <p class="hint" id="profiles-hint"></p>
    <ul class="list" id="profile-list"></ul>
    <form id="profile-form" autocomplete="off" style="margin-top:14px">
      <h3 id="profile-form-title" style="margin:0;font-size:16px">New profile</h3>
      <label class="field">Name<input type="text" id="p-name" maxlength="20" required></label>
      <div class="field"><span class="sub">Avatar</span><div class="avatars" id="p-avatars"></div></div>
      <label class="check"><input type="checkbox" id="p-kids"> Kids profile (age-restricted)</label>
      <div class="kids-only row" id="p-kids-fields" hidden>
        <label class="field grow">Highest movie rating<select id="p-movie"></select></label>
        <label class="field grow">Highest TV rating<select id="p-tv"></select></label>
        <label class="check" style="flex-basis:100%"><input type="checkbox" id="p-unrated"> Allow unrated titles</label>
      </div>
      <div class="row">
        <button class="primary" type="submit" id="p-submit">Create profile</button>
        <button type="button" id="p-cancel" hidden>Cancel</button>
      </div>
    </form>
  </section>

  <section id="addons">
    <h2>Stremio addons</h2>
    <p class="hint">Each profile has its own addons. Paste an addon's manifest URL (https://…/manifest.json, http://… for self-hosted addons, or stremio://…). A bare LAN address such as 192.168.1.20:7000 is treated as http://. Order sets stream priority and the order of addon catalog rows on Home.</p>
    <div class="row">
      <label for="addon-profile">Editing addons for</label>
      <select class="grow" id="addon-profile" aria-label="Profile whose addons are edited"></select>
    </div>
    <p class="hint" id="addon-profile-hint"></p>
    <form id="addon-form" autocomplete="off">
      <div class="row">
        <input class="grow" type="text" id="addon-url" placeholder="https://example.com/manifest.json or 192.168.1.20:7000" inputmode="url" autocapitalize="off" spellcheck="false" required>
        <button class="primary" type="submit" id="addon-submit">Install</button>
      </div>
    </form>
    <ul class="list" id="addon-list"></ul>
  </section>

  <section id="sources"></section>

  <section id="debrid"></section>

  <section id="streams"></section>

  <section id="backup"></section>

  <section id="import">
    <h2>Import a list into a Library</h2>
    <p class="hint">One-time copy of every movie or show in an mdblist.com list into a profile's Library. Titles already there are skipped, Kids profiles only receive titles within their rating limits, and later changes to the list are not synced.</p>
    <form id="import-form" autocomplete="off">
      <input type="url" id="import-url" placeholder="https://mdblist.com/lists/owner/list-name" inputmode="url" required>
      <div class="row">
        <select class="grow" id="import-profile" aria-label="Profile" required></select>
        <select class="grow" id="import-target" aria-label="Library section">
          <option value="MOVIES">Movies</option><option value="TV_SHOWS">TV Shows</option>
        </select>
        <button class="primary" type="submit">Import</button>
      </div>
    </form>
  </section>
</main>
<div id="toast" role="status" aria-live="polite"></div>
<script>
/*AVATARS*/
/*SCRIPT*/
</script>
</body>
</html>
""".trimIndent()
}
