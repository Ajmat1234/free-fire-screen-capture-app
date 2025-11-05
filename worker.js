const puppeteer = require("puppeteer-core");
const axios = require("axios");
const FormData = require("form-data");
const fs = require("fs");
const path = require("path");
const PNG = require("pngjs").PNG;
const pixelmatch = require("pixelmatch");

const chromiumPath = process.env.CHROME_PATH || "/usr/bin/chromium";
const framesDir = path.join(__dirname, "public", "frames");
if (!fs.existsSync(framesDir)) fs.mkdirSync(framesDir, { recursive: true });

const templateVerify = PNG.sync.read(fs.readFileSync("./vision/verify.png"));
const templateJoin = PNG.sync.read(fs.readFileSync("./vision/join.png"));

async function findTemplate(big, small, tolerance = 0.15) {
  const W = big.width, H = big.height;
  const w = small.width, h = small.height;

  for (let y = 0; y < H - h; y += 4) {
    for (let x = 0; x < W - w; x += 4) {
      let diff = 0;
      for (let j = 0; j < h; j++) {
        for (let i = 0; i < w; i++) {
          const bi = ((y + j) * W + (x + i)) * 4;
          const si = (j * w + i) * 4;

          const dr = big.data[bi] - small.data[si];
          const dg = big.data[bi + 1] - small.data[si + 1];
          const db = big.data[bi + 2] - small.data[si + 2];

          diff += dr * dr + dg * dg + db * db;
        }
      }

      if (diff / (w * h) < tolerance * 30000) {
        return { x, y, w, h };
      }
    }
  }
  return null;
}

class Worker {
  constructor({ pageUrl, intervalSec, uploadUrl }) {
    this.pageUrl = pageUrl;
    this.intervalSec = intervalSec;
    this.uploadUrl = uploadUrl;
  }

  log(...a) { console.log("[Worker]", ...a); }

  async start() {
    this.browser = await puppeteer.launch({
      headless: true,
      executablePath: chromiumPath,
      args: ["--no-sandbox", "--disable-setuid-sandbox"]
    });

    this.page = await this.browser.newPage();
    await this.page.setViewport({ width: 1080, height: 1920, isMobile: true });

    await this.page.goto(this.pageUrl, { waitUntil: "networkidle2" });

    this.log("Page opened");

    await this.solveCloudflare();
    await this.solveJoinButton();

    this.log("✅ Human solved. Starting stream.");

    await this.captureOnce();
    setInterval(() => this.captureOnce(), this.intervalSec * 1000);
  }

  async screenshotPNG() {
    const buf = await this.page.screenshot({ type: "png" });
    return PNG.sync.read(buf);
  }

  async clickCenter({ x, y, w, h }) {
    const cx = x + w / 2;
    const cy = y + h / 2;
    await this.page.mouse.click(cx, cy, { delay: 120 });
    await this.page.waitForTimeout(1500);
  }

  async solveCloudflare() {
    this.log("🔍 Looking for verification box");

    while (true) {
      const img = await this.screenshotPNG();
      const match = await findTemplate(img, templateVerify);

      if (match) {
        this.log("✅ Verify box found, clicking...");
        await this.clickCenter(match);
        await this.page.waitForTimeout(4000);
      }

      const media = await this.page.$("video,canvas");
      if (media) {
        this.log("🎯 Cloudflare solved.");
        break;
      }

      await this.page.waitForTimeout(1500);
    }
  }

  async solveJoinButton() {
    this.log("🔍 Waiting for Join Stream button...");

    while (true) {
      const img = await this.screenshotPNG();
      const match = await findTemplate(img, templateJoin, 0.2);

      if (match) {
        this.log("✅ Join button found, clicking...");
        await this.clickCenter(match);
        await this.page.waitForTimeout(3000);
      }

      const media = await this.page.$("video,canvas");
      if (media) {
        this.log("🎬 Joined stream.");
        break;
      }

      await this.page.waitForTimeout(1000);
    }
  }

  async captureOnce() {
    const buf = await this.page.screenshot({ type: "jpeg", quality: 70 });
    const filename = `frame-${Date.now()}.jpg`;
    fs.writeFileSync(path.join(framesDir, filename), buf);
    this.log("Saved", filename);

    const form = new FormData();
    form.append("file", buf, { filename });

    await axios.post(this.uploadUrl, form, { headers: form.getHeaders() });
  }
}

module.exports = Worker;
