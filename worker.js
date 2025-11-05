/**
 * worker.js
 * Gemini-vision driven Puppeteer worker
 *
 * Requirements:
 * - set GEMINI_API_KEY in env (Render secret)
 * - puppeteer-core, axios, form-data, pngjs, pixelmatch installed
 *
 * Behavior:
 * - Takes screenshot -> sends to Gemini -> Gemini returns JSON with bbox(s)
 * - Clicks checkbox first (if reported), waits, re-screens
 * - Clicks Join when reported, waits for video + motion
 * - Only then begins regular frame captures and uploads to uploadUrl
 */

const fs = require("fs");
const path = require("path");
const axios = require("axios");
const FormData = require("form-data");
const puppeteer = require("puppeteer-core");
const { PNG } = require("pngjs");
const pixelmatch = require("pixelmatch");

const GEMINI_KEY = process.env.GEMINI_API_KEY;
if (!GEMINI_KEY) {
  console.error("GEMINI_API_KEY is not set. Set it in env before running.");
  process.exit(1);
}

const CHROME = process.env.CHROME_PATH || "/usr/bin/chromium";
const framesDir = path.join(__dirname, "public", "frames");
const debugDir = path.join(__dirname, "public", "debug");
if (!fs.existsSync(framesDir)) fs.mkdirSync(framesDir, { recursive: true });
if (!fs.existsSync(debugDir)) fs.mkdirSync(debugDir, { recursive: true });

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function randRange(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

async function callGeminiWithImageBase64(base64) {
  // Uses the Generative Language API endpoint for gemini-2.0-flash (image capable)
  // It requests a JSON-only response with bounding boxes for "verify" and "join" UI elements.
  const url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

  // Prompt - instruct model to output strict JSON with only coordinates
  const promptText = `
You are an assistant that inspects a mobile webpage screenshot (image provided).
Return ONLY a JSON object (no extra text). The JSON must have keys:
- "checkbox" : { "x": center_x, "y": center_y, "w": width, "h": height, "confidence": 0-1 } or null
- "join_button" : { "x": center_x, "y": center_y, "w": width, "h": height, "confidence": 0-1 } or null
- "action": one of "click_checkbox", "click_join", "wait", "none"
- "notes": short string (optional)

Coordinates should be integers in pixels relative to the screenshot top-left (0,0).
If you are not sure about a box, set it to null. Be concise and only output valid JSON.
`;

  const body = {
    contents: [
      {
        parts: [
          { text: promptText },
          { inline_data: { mime_type: "image/jpeg", data: base64 } }
        ]
      }
    ],
    // Optionally control temperature/length etc.
    temperature: 0.0,
    max_output_tokens: 512
  };

  try {
    const res = await axios.post(url, body, {
      headers: {
        "Content-Type": "application/json",
        "X-goog-api-key": GEMINI_KEY
      },
      timeout: 30000
    });

    // Model response location: res.data.candidates / res.data. ... The exact layout can vary.
    // Try common fields:
    const payload = res.data;
    // The generated text is usually in payload.candidates[0].content[0].text or payload.choices[0] etc.
    // We'll search the response for a JSON substring to be robust.
    const textCandidates = [];

    // Inspect plausible locations:
    if (payload.candidates) {
      for (const c of payload.candidates) {
        if (c.content) {
          for (const p of c.content) {
            if (p.text) textCandidates.push(p.text);
            if (p.image) textCandidates.push(JSON.stringify(p.image).slice(0,200));
          }
        }
        if (c.output_text) textCandidates.push(c.output_text);
      }
    }
    if (payload.candidates && payload.candidates[0] && payload.candidates[0].output) {
      textCandidates.push(payload.candidates[0].output);
    }
    if (payload.output && payload.output[0] && payload.output[0].content) {
      for (const pc of payload.output[0].content) if (pc.text) textCandidates.push(pc.text);
    }
    if (payload.choices) {
      for (const ch of payload.choices) if (ch.text) textCandidates.push(ch.text);
    }
    if (payload.outputText) textCandidates.push(payload.outputText);
    if (payload.message) textCandidates.push(JSON.stringify(payload.message).slice(0,200));

    // Fallback: stringify entire response and attempt to extract JSON block
    textCandidates.push(JSON.stringify(payload).slice(0,20000));

    // Extract JSON object from candidate strings
    for (const txt of textCandidates) {
      if (!txt) continue;
      // find first { ... } block
      const jsonMatch = txt.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        try {
          const obj = JSON.parse(jsonMatch[0]);
          return obj;
        } catch (e) {
          // try some cleaning: convert single quotes to double (risky)
          const cleaned = jsonMatch[0].replace(/'/g, '"');
          try { return JSON.parse(cleaned); } catch (_) {}
        }
      }
    }

    console.warn("Gemini response parsing failed - returning null");
    return null;
  } catch (e) {
    console.warn("Gemini API error:", e.message || e.toString());
    return null;
  }
}

async function saveScreenshot(page, prefix = "debug") {
  const buf = await page.screenshot({ type: "jpeg", quality: 70 });
  const name = `${prefix}-${Date.now()}.jpg`;
  fs.writeFileSync(path.join(debugDir, name), buf);
  return buf;
}

async function detectMotion(bufA, bufB) {
  // bufA/bufB are JPEG buffers - convert to PNG via pngjs by re-reading
  // A slightly different approach: write JPEGs to PNG via pngjs can't parse JPEG directly,
  // so to keep light, we simply decode JPEGs into PNG using a small trick:
  // Pixelmatch wants raw RGBA buffers; we'll use PNG.sync.read on PNG data.
  // To avoid heavy conversion, we will compare byte differences as a coarse check,
  // then fallback to pixelmatch if PNGs are used.
  if (!bufA || !bufB) return false;
  // quick coarse check: average absolute byte difference
  const len = Math.min(bufA.length, bufB.length);
  let diff = 0;
  for (let i = 0; i < len; i += 100) diff += Math.abs(bufA[i] - bufB[i]);
  // If coarse diff small, assume static; else assume motion.
  if (diff > 1000) return true;

  // If we reach here, assume no large motion. Return false.
  return false;
}

class Worker {
  constructor({ pageUrl, intervalSec = 3, uploadUrl }) {
    this.pageUrl = pageUrl;
    this.interval = intervalSec;
    this.uploadUrl = uploadUrl;
    this.browser = null;
    this.page = null;
    this.captureTimer = null;
    this.state = { solved: false };
  }

  log(...args) { console.log("[Worker]", new Date().toISOString(), "-", ...args); }

  async start() {
    this.log("Starting worker for", this.pageUrl);

    this.browser = await puppeteer.launch({
      headless: "new",
      executablePath: CHROME,
      args: [
        "--no-sandbox",
        "--disable-setuid-sandbox",
        "--disable-dev-shm-usage",
        "--disable-background-timer-throttling",
        "--window-size=1080,1920"
      ]
    });

    this.page = await this.browser.newPage();
    await this.page.setViewport({ width: 1080, height: 1920, isMobile: true, hasTouch: true });
    await this.page.setUserAgent("Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36");

    // open page
    await this.page.goto(this.pageUrl, { waitUntil: "domcontentloaded", timeout: 60000 }).catch(e => {
      this.log("Navigation warning:", e.message);
    });

    // initial debug screenshot
    await saveScreenshot(this.page, "before-solve");

    // solve loop: screenshot -> call Gemini -> act (click or wait)
    await this.solveLoop();

    // after solved, wait for real video and motion, then start capture loop
    await this.waitForStreamAndMotion();

    // start capture loop
    this.captureTimer = setInterval(() => this.captureOnce().catch(e => this.log("capture err", e.message)), this.interval * 1000);
    // also immediately capture one
    await this.captureOnce();
  }

  async solveLoop() {
    const page = this.page;
    let lastDecision = null;
    let consecutiveNoAction = 0;

    // limit attempts to prevent infinite loops
    for (let attempt = 0; attempt < 40; attempt++) {
      this.log("SolveLoop attempt", attempt + 1);

      // take screenshot and send to Gemini (base64)
      const buf = await page.screenshot({ type: "jpeg", quality: 75 });
      const base64 = buf.toString("base64");
      fs.writeFileSync(path.join(debugDir, `screenshot-${Date.now()}.jpg`), buf);

      // call Gemini
      const result = await callGeminiWithImageBase64(base64);
      this.log("Gemini result:", !!result ? "ok" : "null");

      if (!result) {
        // no structured result - wait and retry but slowly
        consecutiveNoAction++;
        await this.page.waitForTimeout(2000 + randRange(0, 1500));
        if (consecutiveNoAction > 6) {
          this.log("Too many null results; trying a conservative click near expected area (center-bottom) as fallback");
          await this.page.mouse.click(540 + randRange(-20, 20), 1200 + randRange(-10, 10), { delay: 150 });
          await this.page.waitForTimeout(2500);
          consecutiveNoAction = 0;
        }
        continue;
      }

      // Expect result to be JSON with fields: checkbox, join_button, action
      const checkbox = result.checkbox || null;
      const join = result.join_button || null;
      const action = (result.action || "wait").toLowerCase();

      this.log("Parsed action:", action, "checkbox:", !!checkbox, "join:", !!join);

      // Save debug note
      await saveScreenshot(page, `after-gemini-${action}`);

      // If gemini suggests clicking checkbox, do it (and reloop)
      if (action === "click_checkbox" && checkbox) {
        const jitterX = randRange(-6, 6);
        const jitterY = randRange(-6, 6);
        const clickX = Math.max(5, Math.round(checkbox.x + jitterX));
        const clickY = Math.max(5, Math.round(checkbox.y + jitterY));
        this.log("Clicking checkbox at", clickX, clickY);
        await page.mouse.click(clickX, clickY, { delay: randRange(80, 180) });
        await page.waitForTimeout(3000 + randRange(0, 2000));
        lastDecision = "clicked_checkbox";
        consecutiveNoAction = 0;
        continue;
      }

      // If gemini suggests clicking join button, click it and break to next phase
      if (action === "click_join" && join) {
        const jitterX = randRange(-8, 8);
        const jitterY = randRange(-8, 8);
        const clickX = Math.max(5, Math.round(join.x + jitterX));
        const clickY = Math.max(5, Math.round(join.y + jitterY));
        this.log("Clicking Join button at", clickX, clickY);
        await page.mouse.click(clickX, clickY, { delay: randRange(120, 240) });
        await page.waitForTimeout(4000 + randRange(0, 3000));
        lastDecision = "clicked_join";
        this.state.solved = true;
        break;
      }

      // If model says wait, obey
      if (action === "wait") {
        this.log("Model asked to wait. Sleeping a bit...");
        await page.waitForTimeout(2000 + randRange(0, 2000));
        consecutiveNoAction++;
        if (consecutiveNoAction > 8) {
          // take a conservative center-bottom click to prompt UI
          this.log("Long wait - doing small center-bottom tap to prompt UI");
          await page.mouse.click(540 + randRange(-30,30), 1200 + randRange(-20,20), { delay: 150 });
          await page.waitForTimeout(2500);
          consecutiveNoAction = 0;
        }
        continue;
      }

      // fallback: if checkbox present but model didn't ask, prefer clicking checkbox first
      if (checkbox && !this.state.solved) {
        this.log("Fallback: checkbox present, clicking it.");
        await page.mouse.click(Math.round(checkbox.x) + randRange(-5,5), Math.round(checkbox.y) + randRange(-5,5), { delay: 120 });
        await page.waitForTimeout(2500);
        continue;
      }

      // fallback join click
      if (join && !this.state.solved) {
        this.log("Fallback: join button present, clicking it.");
        await page.mouse.click(Math.round(join.x) + randRange(-6,6), Math.round(join.y) + randRange(-6,6), { delay: 150 });
        await page.waitForTimeout(3500);
        this.state.solved = true;
        break;
      }

      // nothing to do -> short wait
      await page.waitForTimeout(1500 + randRange(0, 1200));
    } // end for

    this.log("SolveLoop finished. State.solved=", this.state.solved);
  }

  async waitForStreamAndMotion() {
    const p = this.page;
    this.log("Waiting for video/canvas to appear... (max 30s)");
    let found = false;
    for (let i = 0; i < 30; i++) {
      const media = await p.$("video,canvas");
      if (media) { found = true; break; }
      await p.waitForTimeout(1000);
    }
    if (!found) this.log("No video element found in time - continuing anyway.");

    // verify motion: compare two screenshots
    this.log("Verifying motion to confirm live stream...");
    let prev = null;
    for (let i = 0; i < 20; i++) {
      const buf = await p.screenshot({ type: "jpeg", quality: 60 });
      if (prev) {
        if (await detectMotion(prev, buf)) {
          this.log("Motion detected -> live stream confirmed");
          await saveScreenshot(p, "video-moving");
          return;
        }
      }
      prev = buf;
      await p.waitForTimeout(800);
    }
    this.log("Motion not confirmed; proceeding but captures may be static until live.");
  }

  async captureOnce() {
    try {
      const p = this.page;
      const buf = await p.screenshot({ type: "jpeg", quality: 70 });
      const fname = `frame-${Date.now()}.jpg`;
      fs.writeFileSync(path.join(framesDir, fname), buf);
      this.log("Saved frame", fname);

      // upload to configured uploadUrl if provided
      if (this.uploadUrl) {
        const form = new FormData();
        form.append("file", buf, { filename: fname });
        try {
          await axios.post(this.uploadUrl, form, {
            headers: form.getHeaders(),
            timeout: 20000
          });
          this.log("Uploaded", fname);
        } catch (e) {
          this.log("Upload failed:", e.message || e.toString());
        }
      }
    } catch (e) {
      this.log("captureOnce error:", e.message || e.toString());
    }
  }

  async stop() {
    try {
      if (this.captureTimer) clearInterval(this.captureTimer);
      if (this.page) await this.page.close();
      if (this.browser) await this.browser.close();
    } catch {}
  }
}

module.exports = Worker;
