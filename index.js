const express = require('express');
const morgan = require('morgan');
const path = require('path');
const fs = require("fs");
const bodyParser = require('body-parser');
const Worker = require('./worker');

const app = express();
app.use(morgan('tiny'));
app.use(bodyParser.json({ limit: '2mb' }));
app.use(express.static(path.join(__dirname, 'public')));

let worker = null;

app.get("/frames-list", (_req, res) => {
  const framesDir = path.join(__dirname, "public", "frames");
  const files = fs.readdirSync(framesDir).filter(f => f.endsWith(".jpg")).slice(-20);
  res.json(files);
});

app.post('/start', async (req, res) => {
  try {
    if (worker) return res.status(400).json({ error: 'Already running' });

    const { host, streamId, password, intervalSec, uploadUrl, fullUrl } = req.body || {};
    let pageUrl = fullUrl?.trim() || `${host}/${streamId}${password ? `?password=${password}` : ''}`;

    worker = new Worker({
      pageUrl,
      intervalSec: Number(intervalSec) || 3,
      uploadUrl: uploadUrl || process.env.UPLOAD_URL
    });

    await worker.start();
    return res.json({ ok: true, pageUrl });
  } catch (e) {
    return res.status(500).json({ error: e.message });
  }
});

app.post('/stop', async (_req, res) => {
  if (worker) await worker.stop();
  worker = null;
  res.json({ ok: true });
});

app.get('/status', (_req, res) => {
  if (!worker) return res.json({ running: false });
  res.json({ running: worker.running, pageUrl: worker.pageUrl });
});

app.listen(process.env.PORT || 3000, () =>
  console.log("Server started")
);
