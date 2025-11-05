const puppeteer = require('puppeteer-core');
const axios = require('axios');
const FormData = require('form-data');
const chromiumPath = process.env.CHROME_PATH || "/usr/bin/chromium";

class Worker {
  constructor({ pageUrl, intervalSec, uploadUrl }) {
    this.pageUrl = pageUrl;
    this.intervalSec = intervalSec;
    this.uploadUrl = uploadUrl;
    this.browser = null;
    this.page = null;
    this.timer = null;
    this.running = false;
  }

  async start() {
    if (this.running) return;
    this.running = true;

    // launch chromium
    this.browser = await puppeteer.launch({
  headless: "new",
  executablePath: chromiumPath,
  args: [
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--autoplay-policy=no-user-gesture-required",
    "--disable-dev-shm-usage",
    "--disable-background-timer-throttling",
    "--disable-backgrounding-occluded-windows",
    "--disable-renderer-backgrounding",
    "--use-gl=egl"
      ]
    });

    this.page = await this.browser.newPage();
    await this.page.setViewport({ width: 1080, height: 1920 });

    // go to target page
    await this.page.goto(this.pageUrl, { waitUntil: 'networkidle2', timeout: 60000 }).catch(()=>{});

    // try to ensure video present
    await this.waitForMedia();

    // first capture immediately
    await this.captureOnce().catch(()=>{});

    // periodic captures
    this.timer = setInterval(() => this.captureOnce().catch(()=>{}), this.intervalSec * 1000);
  }

  async waitForMedia() {
    // Wait for either <video> or <canvas>
    try {
      await this.page.waitForSelector('video,canvas', { timeout: 30000 });
    } catch (_) {}
    // try to play if paused
    try {
      await this.page.evaluate(() => {
        const v = document.querySelector('video');
        if (v && v.paused) v.play().catch(()=>{});
      });
    } catch (_) {}
  }

  async captureOnce() {
    if (!this.page) return;
    const handle = await this.page.$('video') || await this.page.$('canvas') || await this.page.$('body');
    if (!handle) return;

    // tiny wait so the frame refreshes
    await this.page.waitForTimeout(100);

    const box = await handle.boundingBox();
    let opts = { type: 'jpeg', quality: 70 };
    if (box) {
      opts.clip = {
        x: Math.max(0, Math.floor(box.x)),
        y: Math.max(0, Math.floor(box.y)),
        width: Math.floor(Math.max(1, box.width)),
        height: Math.floor(Math.max(1, box.height))
      };
    }

    const buf = await this.page.screenshot(opts);
    const form = new FormData();
    form.append('file', buf, { filename: `frame-${Date.now()}.jpg`, contentType: 'image/jpeg' });

    await axios.post(this.uploadUrl, form, {
      headers: form.getHeaders(),
      timeout: 20000
    }).then(r => {
      console.log(new Date().toISOString(), 'Uploaded', r.status);
    }).catch(e => {
      console.error(new Date().toISOString(), 'Upload failed:', e.message);
    });
  }

  async stop() {
    this.running = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    if (this.page) {
      try { await this.page.close(); } catch(_) {}
    }
    if (this.browser) {
      try { await this.browser.close(); } catch(_) {}
    }
    this.page = null;
    this.browser = null;
  }
}

module.exports = Worker;
