const express = require('express');
const morgan = require('morgan');
const path = require('path');
const bodyParser = require('body-parser');
const Worker = require('./worker');

const app = express();
app.use(morgan('tiny'));
app.use(bodyParser.json({ limit: '2mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// single worker instance
let worker = null;

app.post('/start', async (req, res) => {
  try {
    if (worker) return res.status(400).json({ error: 'Already running' });

    const {
      host,
      streamId,
      password,
      intervalSec,
      uploadUrl,
      fullUrl
    } = req.body || {};

    // Build target URL
    let pageUrl = '';
    if (fullUrl && fullUrl.trim()) {
      pageUrl = fullUrl.trim();
    } else {
      if (!host || !streamId) {
        return res.status(400).json({ error: 'host and streamId required (or provide fullUrl)' });
      }
      pageUrl = host.replace(/\/$/, '') + '/' + encodeURIComponent(String(streamId).trim());
      if (password && String(password).trim()) {
        const qs = '?password=' + encodeURIComponent(String(password).trim());
        pageUrl += qs;
      }
    }

    const cfg = {
      pageUrl,
      intervalSec: Number(intervalSec) || 3,
      uploadUrl: uploadUrl || process.env.UPLOAD_URL || '',
    };
    if (!cfg.uploadUrl) return res.status(400).json({ error: 'uploadUrl required' });

    worker = new Worker(cfg);
    await worker.start();
    return res.json({ ok: true, pageUrl, intervalSec: cfg.intervalSec });
  } catch (e) {
    return res.status(500).json({ error: e.message || String(e) });
  }
});

app.post('/stop', async (_req, res) => {
  try {
    if (worker) {
      await worker.stop();
      worker = null;
    }
    return res.json({ ok: true, stopped: true });
  } catch (e) {
    return res.status(500).json({ error: e.message || String(e) });
  }
});

app.get('/status', (_req, res) => {
  if (!worker) return res.json({ running: false });
  return res.json({
    running: worker.running,
    pageUrl: worker.pageUrl,
    intervalSec: worker.intervalSec,
    uploadUrl: worker.uploadUrl
  });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Listening on', PORT);
});
