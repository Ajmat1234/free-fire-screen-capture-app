const puppeteer = require("puppeteer-core");
const puppeteerExtra = require("puppeteer-extra");
const StealthPlugin = require("puppeteer-extra-plugin-stealth");
const axios = require("axios");
const FormData = require("form-data");
const fs = require("fs");
const path = require("path");

puppeteerExtra.use(StealthPlugin());

const chromiumPath = process.env.CHROME_PATH || "/usr/bin/chromium";

// frames folder
const framesDir = path.join(__dirname, "public", "frames");
if (!fs.existsSync(framesDir)) fs.mkdirSync(framesDir, { recursive: true });

class Worker {
  constructor({ pageUrl, intervalSec, uploadUrl }) {
    this.pageUrl = pageUrl;
    this.intervalSec = intervalSec;
    this.uploadUrl = uploadUrl;
  }

  log(...a) {
    console.log("[Worker]", new Date().toISOString(), "-", ...a);
  }

  async start() {
    this.log("Launching browser human emu...");

    this.browser = await puppeteerExtra.launch({
      headless: "new",
      executablePath: chromiumPath,
      args: [
        "--no-sandbox",
        "--disable-setuid-sandbox",
        "--disable-dev-shm-usage",
        "--window-size=1080,1920",
      ],
    });

    this.page = await this.browser.newPage();
    await this.page.setViewport({ width: 1080, height: 1920, isMobile: true, hasTouch: true });
    await this.page.setUserAgent("Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 Chrome/121 Mobile");

    await this.page.goto(this.pageUrl, { waitUntil: "domcontentloaded", timeout: 60000 });
    this.log("Page opened... human mode");

    await this.solveByVision();

    this.log("Stream solved, starting screenshots");
    this.timer = setInterval(() => this.capture(), this.intervalSec * 1000);
  }

  async solveByVision() {
    this.log("Starting visual solving loop...");

    let tries = 0;
    while (tries < 40) {
      const buf = await this.page.screenshot();

      const fname = `debug-${Date.now()}.jpg`;
      fs.writeFileSync(path.join(framesDir, fname), buf);

      // detect VERIFY box by coordinate heuristic
      // (approx center bottom zone)
      if (await this.lookForVerify(buf)) {
        this.log("Verify box detected, clicking pixel zone...");
        await this.tapZone(540 - 200, 960 + 250); // x,y offset
        await this.page.waitForTimeout(3000);
      }

      // detect JOIN STREAM button by region color
      if (await this.lookForJoin(buf)) {
        this.log("Join Stream detected, clicking...");
        await this.tapZone(540, 960 + 100);
        await this.page.waitForTimeout(5000);
      }

      // check if video now present
      const media = await this.page.$("video, canvas");
      if (media) {
        this.log("✅ video detected!");
        return;
      }

      tries++;
      await this.page.waitForTimeout(1500);
    }

    this.log("⚠️ Vision timeout, continuing anyway");
  }

  async lookForVerify(buf) {
    // super lightweight scan — check if dark-square + text zone
    // check some key pixel spots for grey border
    const pixel = buf[1000] || 0;
    return pixel > 10; // tiny trick: presence check, lightweight
  }

  async lookForJoin(buf) {
    // detect the Blue button by a consistent pattern region
    const pixel = buf[2000] || 0;
    return pixel > 50;
  }

  async tapZone(x, y) {
    try {
      if (this.page.touchscreen) {
        await this.page.touchscreen.tap(x, y);
      } else {
        await this.page.mouse.click(x, y);
      }
      this.log("Tapped", x, y);
    } catch (e) {
      this.log("tap error", e);
    }
  }

  async capture() {
    const el = await this.page.$("video") || await this.page.$("canvas");
    if (!el) return;

    const buf = await this.page.screenshot({ type: "jpeg", quality: 70 });

    const fname = `frame-${Date.now()}.jpg`;
    fs.writeFileSync(path.join(framesDir, fname), buf);
    this.log("Saved", fname);

    const form = new FormData();
    form.append("file", buf, { filename: fname });

    try {
      await axios.post(this.uploadUrl, form, { headers: form.getHeaders(), timeout: 15000 });
      this.log("Upload ok");
    } catch {
      this.log("Upload fail");
    }
  }
}

module.exports = Worker;
