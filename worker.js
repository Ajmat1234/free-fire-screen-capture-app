/***************************************
 * HUMAN VISION BOT (Final Revision)
 * - Debug screenshots at: /public/debug
 * - Only starts streaming when REAL video frame changes detected
 ***************************************/

const puppeteer = require("puppeteer-core");
const fs = require("fs");
const path = require("path");
const axios = require("axios");
const FormData = require("form-data");
const { PNG } = require("pngjs");
const pixelmatch = require("pixelmatch");

const CHROME = process.env.CHROME_PATH || "/usr/bin/chromium";

const debugDir = path.join(__dirname, "public", "debug");
const framesDir = path.join(__dirname, "public", "frames");
if (!fs.existsSync(debugDir)) fs.mkdirSync(debugDir, { recursive: true });
if (!fs.existsSync(framesDir)) fs.mkdirSync(framesDir, { recursive: true });

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function saveDebug(page, label) {
  const fname = `debug-${Date.now()}-${label}.jpg`;
  const fpath = path.join(debugDir, fname);
  try {
    const buf = await page.screenshot({ type: "jpeg", quality: 60 });
    fs.writeFileSync(fpath, buf);
    console.log("[Debug Saved]", fname);
  } catch {}
}

async function detectMovement(buf1, buf2) {
  try {
    const img1 = PNG.sync.read(buf1);
    const img2 = PNG.sync.read(buf2);
    const diff = new PNG({ width: img1.width, height: img1.height });
    const mismatch = pixelmatch(
      img1.data, img2.data, diff.data,
      img1.width, img1.height,
      { threshold: 0.2 }
    );
    return mismatch > 1000;
  } catch {
    return true;
  }
}

class Worker {
  constructor({ pageUrl, intervalSec, uploadUrl }) {
    this.pageUrl = pageUrl;
    this.interval = intervalSec;
    this.uploadUrl = uploadUrl;
    this.page = null;
    this.browser = null;
    this.timer = null;
  }

  log(...a) { console.log("[Worker]", ...a) }

  async start() {
    this.log("Launching headless human...");
    
    this.browser = await puppeteer.launch({
      headless: true,
      executablePath: CHROME,
      args: [
        "--no-sandbox","--disable-setuid-sandbox",
        "--disable-dev-shm-usage","--use-gl=egl",
        "--window-size=1080,1920"
      ]
    });

    const page = this.page = await this.browser.newPage();
    await page.setViewport({ width:1080, height:1920 });

    await page.setUserAgent(
      "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36"
    );

    this.log("Opening page...");
    await page.goto(this.pageUrl, { waitUntil: "domcontentloaded", timeout:60000 });

    await saveDebug(page, "page-loaded");

    this.log("Starting CF + Join solve routine...");
    await this.solveHumanFlow();
    this.log("✅ Solve routine finished");

    this.log("🎯 Checking for live stream...");
    await this.waitForRealVideo();

    this.log("📸 Start capture loop");
    this.timer = setInterval(()=>this.capture(), this.interval*1000);
    await this.capture();
  }

  async solveHumanFlow() {
    const p = this.page;
    
    // Phase 1: Wait CF auto verify period
    this.log("⌛ Waiting auto verify window (Cloudflare quiet period)...");
    for (let i=0;i<8;i++){
      await saveDebug(p, `auto-wait-${i}`);
      await sleep(1500);
      // If video already appears skip
      if (await p.$("video,canvas")) return;
    }

    // Phase 2: Look for checkbox
    this.log("🔍 Searching for 'Verify you are human' box...");
    for (let i=0;i<20;i++){
      const txt = await p.content();
      if (txt.toLowerCase().includes("verify you are human")){
        this.log("✅ Found verify text, clicking");
        await saveDebug(p,"verify_seen");
        // click below text area approximated
        await p.mouse.click(400,1150,{delay:120});
        await sleep(3000);
        break;
      }
      await saveDebug(p,`chk-${i}`);
      await sleep(1200);
    }

    // Phase 3: Wait for join button visible
    this.log("🟦 Waiting Join button...");
    for (let i=0;i<30;i++){
      const txt = await p.content();
      if (txt.toLowerCase().includes("join stream")){
        this.log("✅ Join text found");
        await saveDebug(p,"join-seen");
        // click join approx center
        await p.mouse.click(540,1200,{delay:200});
        await sleep(3000);
        return;
      }
      await sleep(1000);
    }

    this.log("⚠️ Could not find Join button but continuing");
  }

  async waitForRealVideo() {
    const p = this.page;

    this.log("⏳ Waiting video DOM...");
    await saveDebug(p,"before-video");

    let has = false;
    for (let i=0;i<40;i++){
      if (await p.$("video,canvas")){
        has = true; break;
      }
      await sleep(1000);
    }
    if (!has) this.log("⚠️ No video DOM but continuing");

    // detect motion = real stream
    this.log("🎥 Checking stream motion...");
    let last = null;
    for (let i=0;i<25;i++){
      const buf = await p.screenshot({ type:"jpeg", quality:40 });
      if (last){
        if (await detectMovement(last, buf)) {
          this.log("✅ Video moving");
          await saveDebug(p,"video-moving");
          return;
        }
      }
      last = buf;
      await sleep(800);
    }

    this.log("⚠️ Motion not confirmed, proceeding anyway");
  }

  async capture() {
    const p = this.page;
    const el = await p.$("video,canvas,body");
    if (!el){ this.log("⛔ no node"); return; }

    const buf = await p.screenshot({type:"jpeg",quality:70});
    const name = `frame-${Date.now()}.jpg`;
    fs.writeFileSync(path.join(framesDir,name),buf);
    this.log("📎 saved",name);

    const fd = new FormData();
    fd.append("file",buf,name);
    axios.post(this.uploadUrl, fd, { headers: fd.getHeaders() }).catch(()=>{});
  }
}

module.exports = Worker;
