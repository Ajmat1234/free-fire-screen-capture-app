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
   * waitForMedia:
   * - Waits for the Cloudflare "verify you are human" checkbox to become clickable.
   * - Important: On some pages the checkbox UI appears but only becomes actionable
   *   after several seconds (page scripts run). So we poll for an element that is
   *   visible and not already checked/disabled, and click it when ready.
   *
   * - If checkbox never becomes clickable within the timeout, we proceed and try
   *   to find a video/canvas. All attempts are logged so you can diagnose.
   */
  async waitForMedia() {
    this.log("Waiting for video/canvas...");

    // Polling params
    const pollIntervalMs = 1000;
    const maxWaitMs = 40000; // max time to wait for the checkbox to become clickable
    const start = Date.now();
    let clicked = false;

    this.log("Looking for human verification checkbox (will poll up to " + (maxWaitMs/1000) + "s)...");

    while (Date.now() - start < maxWaitMs) {
      try {
        // Check in page context if there's an input checkbox that is visible and clickable
        const info = await this.page.evaluate(() => {
          const el = document.querySelector('input[type="checkbox"]');
          if (!el) return { found: false };
          const rect = el.getBoundingClientRect();
          const visible = rect.width > 0 && rect.height > 0;
          const checked = el.checked === true;
          const disabled = el.disabled === true;
          return { found: true, visible, checked, disabled };
        });

        if (info && info.found) {
          this.log("Checkbox found. visible:", info.visible, "checked:", info.checked, "disabled:", info.disabled);

          // If visible and not checked and not disabled, click it
          if (info.visible && !info.checked && !info.disabled) {
            try {
              await this.page.evaluate(() => {
                const cb = document.querySelector('input[type="checkbox"]');
                if (cb) cb.click();
              });
              clicked = true;
              this.log("Clicked verification checkbox");
              // give some time for any challenge to complete
              await this.page.waitForTimeout(4000);
              break;
            } catch (e) {
              this.log("Click attempt failed:", e.message || e);
            }
          } else {
            // Checkbox exists but either already checked or not yet actionable
            this.log("Checkbox present but not actionable yet (will retry) — checked:", info.checked, "disabled:", info.disabled);
          }
        } else {
          // no checkbox element yet
          // Optionally, sometimes the CF widget uses different markup (iframe). Try to detect common iframe-based widget.
          // We'll also search for iframe titles or elements that hint at CF challenge.
          const iframeFound = await this.page.$$eval('iframe', iframes => {
            return iframes.map(f => ({title: f.title || '', src: f.src || ''})).slice(0,5);
          }).catch(()=>[]);
          if (iframeFound && iframeFound.length) {
            // log briefly but don't spam
            this.log("iframes on page (sample):", iframeFound.slice(0,3));
          } else {
            this.log("No checkbox or relevant iframes yet");
          }
        }
      } catch (e) {
        this.log("Poll check error:", e.message || e);
      }

      await this.page.waitForTimeout(pollIntervalMs);
    }

    if (!clicked) {
      this.log("Checkbox click not performed within timeout (proceeding).");
    }

    // After checkbox logic, wait for video/canvas element
    try {
      await this.page.waitForSelector('video,canvas', { timeout: 30000 });
      this.log("Video or canvas element detected!");
    } catch {
      this.log("No video/canvas element detected within timeout");
    }

    // Try autoplay if a video exists
    try {
      await this.page.evaluate(() => {
        const v = document.querySelector('video');
        if (v && v.paused) v.play().catch(()=>{});
      });
      this.log("Attempted autoplay on video");
    } catch (e) {
      this.log("Autoplay attempt error:", e.message || e);
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
      // Ensure integers and minimum sizes
      const x = Math.max(0, Math.floor(box.x));
      const y = Math.max(0, Math.floor(box.y));
      const width = Math.max(1, Math.floor(box.width));
      const height = Math.max(1, Math.floor(box.height));
      opts.clip = { x, y, width, height };
    }

    const buf = await this.page.screenshot(opts);

    // Save locally for preview
    const filename = `frame-${Date.now()}.jpg`;
    const filePath = path.join(framesDir, filename);
    try {
      fs.writeFileSync(filePath, buf);
      this.log("Saved frame locally:", filename);
    } catch (e) {
      this.log("Failed to save frame locally:", e.message || e);
    }

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
      this.log("Upload failed:", e.message || e);
    }
  }

  async stop() {
    this.log("Stopping worker...");
    this.running = false;
    if (this.timer) clearInterval(this.timer);

    if (this.page) try { await this.page.close(); } catch (e) { this.log("page close error:", e.message || e); }
    if (this.browser) try { await this.browser.close(); } catch (e) { this.log("browser close error:", e.message || e); }

    this.page = null;
    this.browser = null;
    this.log("Worker stopped");
  }
}

module.exports = Worker;
