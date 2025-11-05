const puppeteer = require('puppeteer-core');
const axios = require('axios');
const FormData = require('form-data');
const fs = require("fs");
const path = require("path");

const chromiumPath = process.env.CHROME_PATH || "/usr/bin/chromium";

// Create frames folder
const framesDir = path.join(__dirname, "public", "frames");
if (!fs.existsSync(framesDir)) fs.mkdirSync(framesDir, { recursive: true });

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

  log(...args) {
    console.log("[Worker]", new Date().toISOString(), "-", ...args);
  }

  async start() {
    if (this.running) return;
    this.running = true;
    this.log("Starting worker...", this.pageUrl);

    this.browser = await puppeteer.launch({
      headless: "new",
      executablePath: chromiumPath,
      args: [
        "--no-sandbox", "--disable-setuid-sandbox",
        "--autoplay-policy=no-user-gesture-required",
        "--disable-dev-shm-usage", "--disable-background-timer-throttling",
        "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
        "--use-gl=egl"
      ]
    });

    this.page = await this.browser.newPage();
    await this.page.setViewport({ width: 1080, height: 1920 });

    this.log("Opening page...");
    await this.page.goto(this.pageUrl, { waitUntil: 'networkidle2', timeout: 60000 }).catch(e => {
      this.log("Navigation failed:", e.message);
    });

    await this.waitForMedia();
    await this.captureOnce().catch(()=>{});

    this.timer = setInterval(() => this.captureOnce().catch(()=>{}), this.intervalSec * 1000);
  }

  async waitForMedia() {
    this.log("Waiting for video/canvas...");

    // ✅ Cloudflare checkbox auto-click
    try {
      this.log("Looking for human verification...");
      await this.page.waitForSelector('input[type="checkbox"]', { timeout: 15000 });
      await this.page.evaluate(() => {
        const cb = document.querySelector('input[type="checkbox"]');
        if (cb) cb.click();
      });
      this.log("Clicked Cloudflare checkbox");
      await this.page.waitForTimeout(4000); // wait for CF to verify
    } catch {
      this.log("No CF checkbox found or click failed");
    }

    // ✅ Wait for video/canvas
    try {
      await this.page.waitForSelector('video,canvas', { timeout: 30000 });
      this.log("Video element detected!");
    } catch {
      this.log("No video element detected within timeout");
    }

    // ✅ Try autoplay
    try {
      await this.page.evaluate(() => {
        const v = document.querySelector('video');
        if (v && v.paused) v.play().catch(()=>{});
      });
      this.log("Attempted autoplay on video");
    } catch (_) {}
  }

  async captureOnce() {
    if (!this.page) return;

    this.log("Capturing screenshot...");
    const handle = await this.page.$('video') || await this.page.$('canvas') || await this.page.$('body');

    if (!handle) {
      this.log("No element to capture!");
      return;
    }

    await this.page.waitForTimeout(100);
    const box = await handle.boundingBox();

    let opts = { type: "jpeg", quality: 70 };
    if (box) {
      opts.clip = { x: box.x, y: box.y, width: box.width, height: box.height };
    }

    const buf = await this.page.screenshot(opts);

    // Save locally for preview
    const filename = `frame-${Date.now()}.jpg`;
    const filePath = path.join(framesDir, filename);
    fs.writeFileSync(filePath, buf);
    this.log("Saved frame locally:", filename);

    // Upload to remote server
    const form = new FormData();
    form.append("file", buf, { filename, contentType: "image/jpeg" });

    try {
      const r = await axios.post(this.uploadUrl, form, {
        headers: form.getHeaders(),
        timeout: 20000
      });
      this.log("Upload success:", r.status);
    } catch (e) {
      this.log("Upload failed:", e.message);
    }
  }

  async stop() {
    this.log("Stopping worker...");
    this.running = false;
    if (this.timer) clearInterval(this.timer);

    if (this.page) try { await this.page.close(); } catch {}
    if (this.browser) try { await this.browser.close(); } catch {}

    this.page = null;
    this.browser = null;
    this.log("Worker stopped");
  }
}

module.exports = Worker;
