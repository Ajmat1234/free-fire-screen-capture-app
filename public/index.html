<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>WebRTC → Snapshot Uploader</title>
<style>
  body { font-family: system-ui, Arial, sans-serif; max-width: 720px; margin: 24px auto; padding: 0 12px; }
  header { margin-bottom: 16px; }
  .card { border: 1px solid #ddd; border-radius: 12px; padding: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
  label { display:block; font-weight:600; margin: 10px 0 6px; }
  input { width: 100%; padding: 10px; border:1px solid #ccc; border-radius:8px; }
  .row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  button { padding: 10px 16px; border:0; border-radius:10px; cursor:pointer; }
  .start { background:#0ea5e9; color:#fff; }
  .stop { background:#ef4444; color:#fff; }
  .muted { color:#666; font-size: 13px; }
  .status { margin-top:12px; padding:8px 10px; background:#f6f7f9; border-radius:8px; }
</style>
</head>
<body>
  <header>
    <h2>WebRTC → Snapshot Uploader</h2>
    <p class="muted">Headless browser will open your player page and upload a JPEG every N seconds.</p>
  </header>

  <div class="card">
    <label>Stream Host</label>
    <input id="host" placeholder="https://screenstream.io" value="https://screenstream.io"/>

    <div class="row">
      <div>
        <label>Stream ID</label>
        <input id="streamId" placeholder="76289788" value="76289788"/>
      </div>
      <div>
        <label>Password (optional)</label>
        <input id="password" placeholder="32HTm7" value="32HTm7"/>
      </div>
    </div>

    <label>Upload URL</label>
    <input id="uploadUrl" placeholder="https://practice-8waa.onrender.com/upload" value="https://practice-8waa.onrender.com/upload"/>

    <div class="row">
      <div>
        <label>Interval (seconds)</label>
        <input id="intervalSec" type="number" min="1" value="3"/>
      </div>
      <div>
        <label>Full Player URL (override)</label>
        <input id="fullUrl" placeholder="https://screenstream.io/76289788?password=32HTm7"/>
      </div>
    </div>

    <div style="display:flex; gap:10px; margin-top:16px;">
      <button class="start" id="btnStart">Start</button>
      <button class="stop" id="btnStop">Stop</button>
    </div>

    <div class="status" id="status">Status: Idle</div>
    <div class="muted">Tip: If Start fails, try filling “Full Player URL”.</div>
  </div>

<script>
async function call(path, method, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type':'application/json' },
    body: body ? JSON.stringify(body) : undefined
  });
  return res.json();
}
async function refreshStatus(){
  const s = await fetch('/status').then(r=>r.json());
  document.getElementById('status').textContent =
    'Status: ' + (s.running ? ('Running @ ' + (s.pageUrl||'')) : 'Idle');
}
document.getElementById('btnStart').onclick = async () => {
  const host = document.getElementById('host').value.trim();
  const streamId = document.getElementById('streamId').value.trim();
  const password = document.getElementById('password').value.trim();
  const uploadUrl = document.getElementById('uploadUrl').value.trim();
  const intervalSec = document.getElementById('intervalSec').value.trim();
  const fullUrl = document.getElementById('fullUrl').value.trim();

  const payload = { host, streamId, password, uploadUrl, intervalSec, fullUrl };
  const out = await call('/start', 'POST', payload);
  document.getElementById('status').textContent = 'Start: ' + JSON.stringify(out);
};
document.getElementById('btnStop').onclick = async () => {
  const out = await call('/stop', 'POST');
  document.getElementById('status').textContent = 'Stop: ' + JSON.stringify(out);
};
setInterval(refreshStatus, 3000);
refreshStatus();
</script>
</body>
</html>
