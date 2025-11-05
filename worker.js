/**
 * worker.js
 * Puppeteer worker with stealth + mobile fingerprint + visual click fallback
 *
 * Required extra packages:
 *   npm install puppeteer-extra puppeteer-extra-plugin-stealth
 *
 * (You still need puppeteer-core + chromium in Dockerfile as before)
 */

const PuppeteerExtra = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const puppeteerCore = require('puppeteer-core'); // keep using puppeteer-core (already in your deps)
const axios = require('axios');
const FormData = require('form-data');
const fs = require("fs");
const path = require("path");

PuppeteerExtra.use(StealthPlugin());

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

    // Launch with puppeteer-core through puppeteer-extra
    this.browser = await PuppeteerExtra.launch({
      headless: "new",
      executablePath: chromiumPath,
      // pass puppeteer-core to the extra wrapper
      // (puppeteer-extra handles plugins; underlying is puppeteer-core)
      puppeteer: puppeteerCore,
      args: [
        "--no-sandbox",
        "--disable-setuid-sandbox",
        "--autoplay-policy=no-user-gesture-required",
        "--disable-dev-shm-usage",
        "--disable-background-timer-throttling",
        "--disable-backgrounding-occluded-windows",
        "--disable-renderer-backgrounding",
        "--use-gl=egl",
        // these help avoid detection
        "--disable-dev-shm-usage",
        "--disable-infobars",
        "--window-size=1080,1920"
      ],
    });

    this.page = await this.browser.newPage();

    // Mobile user agent & viewport (Chrome on Android)
    const mobileUA = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36";
    await this.page.setUserAgent(mobileUA);
    await this.page.setExtraHTTPHeaders({
      'accept-language': 'en-US,en;q=0.9'
    });

    // Mobile viewport & enable touch emulation
    await this.page.setViewport({ width: 1080, height: 1920, isMobile: true, hasTouch: true });

    // Make the page look more human by overriding some navigator properties
    await this.page.evaluateOnNewDocument(() => {
      // make webdriver false
      Object.defineProperty(navigator, 'webdriver', { get: () => false });

      // languages
      Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });

      // permissions (some sites check)
      const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
      if (originalQuery) {
        window.navigator.permissions.query = parameters =>
          parameters.name === 'notifications'
            ? Promise.resolve({ state: Notification.permission })
            : originalQuery(parameters);
      }

      // plugins / mimeTypes (small fake)
      Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
      Object.defineProperty(navigator, 'mimeTypes', { get: () => [1, 2, 3] });
    });

    // open target
    this.log("Opening page...");
    await this.page.goto(this.pageUrl, { waitUntil: 'networkidle2', timeout: 60000 }).catch(e => {
      this.log("Navigation failed:", e.message);
    });

    // attempt to accept cookies/popups quickly if present
    await this._tryAcceptCookieButtons();

    // wait for verification & media (smart attempts inside)
    await this.waitForMedia();

    // first capture immediately
    await this.captureOnce().catch(()=>{});

    // periodic captures
    this.timer = setInterval(() => this.captureOnce().catch(()=>{}), this.intervalSec * 1000);
  }

  // Try some likely "Accept cookies" or "Accept" buttons that block overlays
  async _tryAcceptCookieButtons() {
    try {
      // common selectors
      const selectors = [
        "button[aria-label='accept']",
        "button[aria-label='Accept']",
        "button:contains('Accept')",
        "button:contains('I agree')",
        "button#accept",
        "button.accept",
        "button.cookie-accept",
        ".cookie-consent button"
      ];

      for (const s of selectors) {
        try {
          const found = await this.page.$(s);
          if (found) {
            this.log("Clicking cookie/accept button selector:", s);
            await found.click({ delay: 50 });
            await this.page.waitForTimeout(800);
          }
        } catch (_) {}
      }
    } catch (e) {
      this.log("Cookie acceptance attempt failed:", e.message || e);
    }
  }

  /**
   * waitForMedia - improved strategy:
   * 1) Try DOM detection + label/checkbox clicking (if available)
   * 2) Try frame-based detection (same-origin frames)
   * 3) Try stealth touch clicks (touchscreen.tap) near the join area
   * 4) Visual blind-click fallback: take screenshot(s) and click coordinates (touch + mouse)
   *
   * This version uses mobile fingerprint + stealth plugin to reduce challenges.
   */
  async waitForMedia() {
    this.log("Waiting for verification widget / video... (stealth + mobile mode active)");

    // short helper: try to click actual checkbox if present
    const tryDomClick = async () => {
      try {
        // look for label or input with text near it
        const found = await this.page.evaluate(() => {
          function textMatch(s) {
            if (!s) return false;
            s = s.toLowerCase();
            return s.includes('verify') && s.includes('human');
          }
          // search for visible elements containing phrase
          const nodes = Array.from(document.querySelectorAll('body *'));
          for (const n of nodes) {
            const tag = n.tagName && n.tagName.toLowerCase();
            if (tag === 'script' || tag === 'style' || tag === 'noscript') continue;
            const txt = (n.innerText || n.textContent || '').trim();
            if (!txt) continue;
            if (textMatch(txt)) {
              // gather element path info
              return { found: true, selector: null };
            }
          }
          return { found: false };
        }).catch(()=>({found:false}));

        if (found && found.found) {
          this.log("verify/human phrase appears in DOM — trying to click input or label around it.");
          // try clicking input or label near the text
          const clicked = await this.page.evaluate(() => {
            try {
              const nodes = Array.from(document.querySelectorAll('body *'));
              for (const n of nodes) {
                const txt = (n.innerText || n.textContent || '').toLowerCase();
                if (!txt) continue;
                if (txt.includes('verify') && txt.includes('human')) {
                  // try input inside or previous sibling or parent
                  let cb = n.querySelector('input[type="checkbox"]') ||
                           (n.previousElementSibling && n.previousElementSibling.querySelector && n.previousElementSibling.querySelector('input[type="checkbox"]')) ||
                           (n.parentElement && n.parentElement.querySelector && n.parentElement.querySelector('input[type="checkbox"]')) ||
                           null;
                  if (cb) { cb.click(); return true; }
                  // try label
                  let lbl = n.querySelector('label') || (n.previousElementSibling && n.previousElementSibling.tagName && n.previousElementSibling.tagName.toLowerCase() === 'label' ? n.previousElementSibling : null);
                  if (lbl) { lbl.click(); return true; }
                }
              }
            } catch (e) {}
            return false;
          }).catch(()=>false);

          if (clicked) {
            this.log("Clicked DOM checkbox/label.");
            await this.page.waitForTimeout(2500);
            return true;
          }
        }
      } catch (e) {
        this.log("tryDomClick error:", e.message || e);
      }
      return false;
    };

    // 1) try DOM click strategy
    const domClicked = await tryDomClick();
    if (domClicked) {
      this.log("DOM based click succeeded.");
    }

    // 2) check same-origin frames for DOM text and click inside
    if (!domClicked) {
      try {
        const frames = this.page.frames();
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
                const txt = (el.innerText || el.textContent || '').trim().toLowerCase();
                if (!txt) continue;
                if (textMatch(txt)) {
                  return true;
                }
              }
              return false;
            }).catch(()=>false);

            if (fres) {
              this.log("verify/human text found inside a same-origin frame. Attempting to click inside frame.");
              // attempt to click inside frame context
              try {
                const clicked = await f.evaluate(() => {
                  try {
                    const nodes = Array.from(document.querySelectorAll('body *'));
                    for (const n of nodes) {
                      const txt = (n.innerText || n.textContent || '').toLowerCase();
                      if (!txt) continue;
                      if (txt.includes('verify') && txt.includes('human')) {
                        const cb = n.querySelector('input[type="checkbox"]');
                        if (cb) { cb.click(); return true; }
                        const lbl = n.querySelector('label');
                        if (lbl) { lbl.click(); return true; }
                        // fallback dispatch click event
                        n.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                        return true;
                      }
                    }
                  } catch (e) {}
                  return false;
                }).catch(()=>false);

                if (clicked) {
                  this.log("Clicked inside same-origin frame.");
                  await this.page.waitForTimeout(2500);
                  break;
                }
              } catch (e) {
                this.log("Frame click error:", e.message || e);
              }
            }
          } catch (_) {}
        }
      } catch (e) {
        this.log("Frame detection error:", e.message || e);
      }
    }

    // 3) If still not resolved, attempt touchscreen taps near the center-bottom area where the widget appears
    // Coordinates tuned for viewport 1080x1920 (mobile)
    const centerX = 1080 / 2;
    const centerY = 1920 / 2;
    const touchPoints = [
      { x: Math.floor(centerX - 240), y: Math.floor(centerY + 230) },
      { x: Math.floor(centerX - 220), y: Math.floor(centerY + 210) },
      { x: Math.floor(centerX - 260), y: Math.floor(centerY + 250) },
      { x: Math.floor(centerX - 200), y: Math.floor(centerY + 200) }
    ];

    let solved = false;
    const start = Date.now();
    const maxWaitMs = 45000;
    const pollIntervalMs = 2000;

    this.log("Attempting touch + visual fallback if DOM methods fail.");

    while (Date.now() - start < maxWaitMs) {
      try {
        // quick check: if video already present, stop
        const media = await this.page.$('video,canvas');
        if (media) {
          this.log("Media element already present before visual clicks.");
          solved = true;
          break;
        }

        // take a checkpoint screenshot and save (so you can inspect)
        try {
          const snap = await this.page.screenshot({ type: 'jpeg', quality: 50 });
          const fname = `checkpoint-${Date.now()}.jpg`;
          fs.writeFileSync(path.join(framesDir, fname), snap);
          this.log("Saved checkpoint screenshot:", fname);
        } catch (e) {
          this.log("Screenshot (checkpoint) error:", e.message || e);
        }

        // perform touch taps (mobile) then mouse clicks as fallback
        for (const p of touchPoints) {
          try {
            // use touchscreen.tap if available
            if (this.page.touchscreen && typeof this.page.touchscreen.tap === 'function') {
              try {
                await this.page.touchscreen.tap(p.x, p.y);
                this.log("Touch tap at", p.x, p.y);
              } catch (e) {
                // touchscreen may not be available - fallback to mouse click
                await this.page.mouse.click(p.x, p.y, { delay: 60 });
                this.log("Fallback mouse click at", p.x, p.y);
              }
            } else {
              await this.page.mouse.click(p.x, p.y, { delay: 60 });
              this.log("Mouse click (no touchscreen) at", p.x, p.y);
            }
            await this.page.waitForTimeout(1200);
          } catch (e) {
            this.log("Click/tap inner error:", e.message || e);
          }
        }

        // small wait then check again
        await this.page.waitForTimeout(800);

        const mediaAfter = await this.page.$('video,canvas');
        if (mediaAfter) {
          this.log("Media detected after touch/click attempts.");
          solved = true;
          break;
        } else {
          this.log("No media after taps; will retry until timeout.");
        }
      } catch (e) {
        this.log("Visual fallback loop error:", e.message || e);
      }

      await this.page.waitForTimeout(pollIntervalMs);
    }

    if (!solved) {
      this.log("Verification/widget not resolved within timeout; proceeding anyway.");
    } else {
      this.log("Verification/widget likely resolved (media present).");
    }

    // final attempt to wait for video/canvas and autoplay
    try {
      await this.page.waitForSelector('video,canvas', { timeout: 30000 });
      this.log("Video or canvas element detected!");
      try {
        await this.page.evaluate(() => {
          const v = document.querySelector('video');
          if (v && v.paused) v.play().catch(()=>{});
        });
        this.log("Attempted autoplay on video");
      } catch (e) {
        this.log("Autoplay attempt error:", e.message || e);
      }
    } catch {
      this.log("No video/canvas element detected within final wait");
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
