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

  /**
   * ✅ BLIND-CLICK waitForMedia() (Cloudflare checkbox bypass)
   */
  async waitForMedia() {
    this.log("Waiting for CF checkbox...");

    const centerX = 1080 / 2;
    const centerY = 1920 / 2;

    const clickPoints = [
      { x: centerX - 240, y: centerY + 230 },
      { x: centerX - 220, y: centerY + 210 },
      { x: centerX - 260, y: centerY + 250 }
    ];

    let clicked = false;

    const start = Date.now();
    while (Date.now() - start < 40000) {
      try {
        this.log("Checking frame for checkbox...");
        const buf = await this.page.screenshot();

        for (const p of clickPoints) {
          this.log(`Trying CF checkbox click at x=${p.x}, y=${p.y}`);
          await this.page.mouse.click(p.x, p.y, { delay: 80 });
          await this.page.waitForTimeout(1500);
        }

        const hasVideo = await this.page.$("video,canvas");
        if (hasVideo) {
          this.log("Stream unlocked and video detected!");
          clicked = true;
          break;
        }
      } catch (err) {
        this.log("CF click loop error:", err.message);
      }

      await this.page.waitForTimeout(2000);
    }

    if (!clicked) this.log("CF checkbox click timeout, continuing anyway");

    try {
      await this.page.waitForSelector("video,canvas", { timeout: 30000 });
      this.log("Video found, playing...");
      await this.page.evaluate(() => {
        const v = document.querySelector("video");
        if (v && v.paused) v.play().catch(()=>{});
      });
    } catch {
      this.log("Video not found after CF solve attempt");
    }
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
      const x = Math.max(0, Math.floor(box.x));
      const y = Math.max(0, Math.floor(box.y));
      const width = Math.max(1, Math.floor(box.width));
      const height = Math.max(1, Math.floor(box.height));
      opts.clip = { x, y, width, height };
    }

    const buf = await this.page.screenshot(opts);

    const filename = `frame-${Date.now()}.jpg`;
    const filePath = path.join(framesDir, filename);
    try {
      fs.writeFileSync(filePath, buf);
      this.log("Saved frame locally:", filename);
    } catch (e) {
      this.log("Failed to save frame locally:", e.message || e);
    }

    const form = new FormData();
    form.append("file", buf, { filename, contentType: "image/jpeg" });

    try {
      const r = await axios.post(this.uploadUrl, form, {
        headers: form.getHeaders(),
        timeout: 20000
      });
      this.log("Upload success:", r.status);
    } catch (e) {
      this.log("Upload failed:", e.message || e);
    }
  }

  async stop() {
    this.log("Stopping worker...");
    this.running = false;
    if (this.timer) clearInterval(this.timer);

    if (this.page) try { await this.page.close(); } catch (e) {}
    if (this.browser) try { await this.browser.close(); } catch (e) {}

    this.page = null;
    this.browser = null;
    this.log("Worker stopped");
  }
}

module.exports = Worker;
