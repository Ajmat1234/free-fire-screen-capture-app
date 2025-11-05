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
   * Enhanced waitForMedia:
   * - Polls the page (and accessible frames) for text containing "verify" + "human"
   * - When found: tries these strategies in order:
   *    1) click a nearby input[type=checkbox] or label (if present)
   *    2) click a point slightly to the left of the text bounding box (mouse click)
   * - If click succeeds, waits a bit for challenge to resolve; then proceeds to detect video/canvas and autoplay.
   *
   * This approach works for both an actual checkbox element and UIs where the visible checkbox is rendered
   * as part of a larger widget.
   */
  async waitForMedia() {
    this.log("Waiting for video/canvas...");

    const pollIntervalMs = 1000;
    const maxWaitMs = 45000; // wait up to ~45s for the verify widget to become actionable
    const start = Date.now();
    let clicked = false;

    this.log("Polling for 'verify you are human' text across page and frames (up to " + (maxWaitMs/1000) + "s)...");

    while (Date.now() - start < maxWaitMs) {
      try {
        // 1) Try to click checkbox or left-of-text inside main page context
        const result = await this.page.evaluate(() => {
          function textMatch(s) {
            if (!s) return false;
            s = s.toLowerCase();
            return s.includes('verify') && s.includes('human');
          }

          // Helper: search element whose visible text includes the phrase
          const all = Array.from(document.querySelectorAll('body *'));
          for (const el of all) {
            // Skip script/style and invisible nodes quickly
            const tag = el.tagName && el.tagName.toLowerCase();
            if (tag === 'script' || tag === 'style' || tag === 'noscript') continue;
            let txt = (el.innerText || el.textContent || '').trim();
            if (!txt) continue;
            if (textMatch(txt)) {
              const rect = el.getBoundingClientRect();
              // try to find an input[type=checkbox] inside or nearby (siblings / previous / parent)
              const checkbox =
                el.querySelector('input[type="checkbox"]') ||
                el.querySelector('input[type="checkbox"][role="checkbox"]') ||
                (el.previousElementSibling && el.previousElementSibling.querySelector && el.previousElementSibling.querySelector('input[type="checkbox"]')) ||
                (el.parentElement && el.parentElement.querySelector && el.parentElement.querySelector('input[type="checkbox"]')) ||
                null;

              const label =
                el.querySelector('label') ||
                (el.previousElementSibling && el.previousElementSibling.tagName && el.previousElementSibling.tagName.toLowerCase() === 'label' ? el.previousElementSibling : null) ||
                null;

              return {
                found: true,
                textRect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
                hasCheckbox: !!checkbox,
                hasLabel: !!label
              };
            }
          }
          return { found: false };
        }).catch(()=>({ found:false }));

        if (result && result.found) {
          this.log("Found verification text on main page. hasCheckbox:", result.hasCheckbox, "hasLabel:", result.hasLabel);

          // If there's a nearby checkbox/label, try clicking that first (in page context)
          if (result.hasCheckbox || result.hasLabel) {
            try {
              const clickedInner = await this.page.evaluate(() => {
                try {
                  const el = Array.from(document.querySelectorAll('body *')).find(e => {
                    const t = (e.innerText || e.textContent || '').toLowerCase();
                    return t.includes('verify') && t.includes('human');
                  });
                  if (!el) return false;
                  // prefer direct checkbox inside
                  let cb = el.querySelector('input[type="checkbox"]');
                  if (!cb && el.previousElementSibling && el.previousElementSibling.querySelector) {
                    cb = el.previousElementSibling.querySelector('input[type="checkbox"]');
                  }
                  if (cb) { cb.click(); return true; }
                  // else try a label
                  let lbl = el.querySelector('label') || (el.previousElementSibling && el.previousElementSibling.tagName && el.previousElementSibling.tagName.toLowerCase() === 'label' ? el.previousElementSibling : null);
                  if (lbl) { lbl.click(); return true; }
                  return false;
                } catch (e) { return false; }
              });
              if (clickedInner) {
                this.log("Clicked checkbox/label (main page)");
                clicked = true;
                await this.page.waitForTimeout(3000);
                break;
              }
            } catch (e) {
              this.log("Error clicking checkbox/label in page:", e.message || e);
            }
          }

          // If no direct checkbox/label clicked, attempt a mouse click just to the left of the text bounding box
          try {
            const r = result.textRect;
            // compute click point slightly left inside viewport
            const clickX = Math.max(5, Math.floor(r.x + 8)); // 8px from left edge of text box
            const clickY = Math.floor(r.y + (r.height / 2));
            await this.page.mouse.click(clickX, clickY, { delay: 50 });
            this.log("Mouse-clicked near verification text at", clickX, clickY);
            clicked = true;
            await this.page.waitForTimeout(3000);
            break;
          } catch (e) {
            this.log("Mouse click near text failed:", e.message || e);
          }
        } else {
          // If not found in main page, attempt accessible child frames (same-origin)
          const frames = this.page.frames();
          let frameClicked = false;
          for (const f of frames) {
            if (f === this.page.mainFrame()) continue;
            try {
              const fres = await f.evaluate(() => {
                function textMatch(s) {
                  if (!s) return false;
                  s = s.toLowerCase();
                  return s.includes('verify') && s.includes('human');
                }
                const all = Array.from(document.querySelectorAll('body *'));
                for (const el of all) {
                  const tag = el.tagName && el.tagName.toLowerCase();
                  if (tag === 'script' || tag === 'style' || tag === 'noscript') continue;
                  const txt = (el.innerText || el.textContent || '').trim();
                  if (!txt) continue;
                  if (textMatch(txt)) {
                    const rect = el.getBoundingClientRect();
                    return { found: true, rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height }, hasCheckbox: !!el.querySelector('input[type="checkbox"]') };
                  }
                }
                return { found: false };
              }).catch(()=>({found:false}));

              if (fres && fres.found) {
                this.log("Found verification text inside a frame (same-origin). Trying to click inside that frame.");
                // try to click inside frame via its own element
                try {
                  const clickedInFrame = await f.evaluate(() => {
                    const el = Array.from(document.querySelectorAll('body *')).find(e => {
                      const t = (e.innerText || e.textContent || '').toLowerCase();
                      return t.includes('verify') && t.includes('human');
                    });
                    if (!el) return false;
                    const cb = el.querySelector('input[type="checkbox"]');
                    if (cb) { cb.click(); return true; }
                    const lbl = el.querySelector('label') || (el.previousElementSibling && el.previousElementSibling.tagName && el.previousElementSibling.tagName.toLowerCase() === 'label' ? el.previousElementSibling : null);
                    if (lbl) { lbl.click(); return true; }
                    // fallback: click left of the element bounding rect via element click
                    try {
                      const rect = el.getBoundingClientRect();
                      const clickX = Math.max(5, rect.x + 8);
                      const clickY = rect.y + (rect.height/2);
                      // element-based click using dispatchEvent (not ideal for coordinate click but may work)
                      el.dispatchEvent(new MouseEvent('click', { clientX: clickX, clientY: clickY, bubbles: true }));
                      return true;
                    } catch (e) { return false; }
                  });
                  if (clickedInFrame) {
                    this.log("Clicked checkbox/label inside frame");
                    frameClicked = true;
                    clicked = true;
                    await this.page.waitForTimeout(3000);
                    break;
                  }
                } catch (e) {
                  this.log("Error clicking inside frame:", e.message || e);
                }

                // If above fails, try a mouse click mapped to frame's bounding rect using main page coordinates.
                try {
                  // get bounding box of the frame element in parent so we can compute global coordinates
                  const frameElements = await this.page.$$eval('iframe', (iframes, rectChild) => {
                    return iframes.map(f => ({ src: f.src || '', title: f.title || '', left: f.getBoundingClientRect().left, top: f.getBoundingClientRect().top }));
                  });
                  // We won't attempt precise coordinate mapping here to avoid cross-origin issues; rely on previous approaches.
                } catch (_) {}
              }
            } catch (e) {
              // frames that are cross-origin will throw when evaluated; ignore them
            }
            if (frameClicked) break;
          }

          if (frameClicked) break;

          // Not found anywhere yet
          this.log("No 'verify you are human' text found in page/frames yet (will retry).");
        }
      } catch (e) {
        this.log("Polling iteration error:", e.message || e);
      }

      await this.page.waitForTimeout(pollIntervalMs);
    }

    if (!clicked) {
      this.log("Verification checkbox was not clicked within timeout. Proceeding anyway.");
    }

    // After verification attempts, wait for video/canvas element
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
