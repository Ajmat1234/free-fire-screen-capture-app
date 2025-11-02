from flask import Flask, request, jsonify, render_template_string
import os

app = Flask(__name__)

# Configuration
ORIGINAL_URL = "https://practice-8waa.onrender.com"  # Optional forward

@app.route('/')
def home():
    """Serve frontend HTML with JS for screen capture."""
    html_template = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Free Fire Screen Capture</title>
        <style>
            body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background: #1a1a1a; color: white; }
            button { background: #ff4444; color: white; border: none; padding: 15px 30px; font-size: 18px; cursor: pointer; border-radius: 5px; }
            button:hover { background: #cc0000; }
            button:disabled { background: #666; cursor: not-allowed; }
            #status { margin-top: 20px; font-size: 16px; }
            #log { margin-top: 20px; text-align: left; max-height: 200px; overflow-y: scroll; background: #333; padding: 10px; border-radius: 5px; }
            .info { background: #444; padding: 10px; border-radius: 5px; margin: 10px; }
        </style>
    </head>
    <body>
        <h1>Free Fire Real-Time Screen Capture</h1>
        <p>Click below to start sharing your screen (Free Fire game window).</p>
        <div id="browserCheck" class="info" style="display: none;"></div>
        <button id="startBtn">Send SS</button>
        <div id="status">Status: Ready</div>
        <div id="log"></div>

        <script>
            const startBtn = document.getElementById('startBtn');
            const status = document.getElementById('status');
            const logDiv = document.getElementById('log');
            const browserCheck = document.getElementById('browserCheck');
            let stream = null;
            let video = null;
            let canvas = null;
            let ctx = null;
            let intervalId = null;
            const INTERVAL = 3000;  // 3 seconds
            const WIDTH = 854;      // 480p width
            const HEIGHT = 480;     // 480p height
            const SERVER_URL = '/upload_screenshot';  // Backend endpoint

            function log(message) {
                const timestamp = new Date().toLocaleTimeString();
                logDiv.innerHTML += `[${timestamp}] ${message}<br>`;
                logDiv.scrollTop = logDiv.scrollHeight;
                console.log(message);
            }

            function updateStatus(msg) {
                status.textContent = `Status: ${msg}`;
            }

            // Browser support check
            function checkSupport() {
                if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
                    const errMsg = 'Your browser does not support screen sharing. Use Chrome/Edge on PC/Android (version 72+). On iOS, use Safari 12.1+ but full screen share limited.';
                    updateStatus('Unsupported Browser');
                    browserCheck.textContent = errMsg;
                    browserCheck.style.display = 'block';
                    startBtn.disabled = true;
                    log('Error: getDisplayMedia not supported.');
                    return false;
                }
                // Additional check for HTTPS (required)
                if (location.protocol !== 'https:') {
                    const errMsg = 'Screen sharing requires HTTPS. Refresh if on HTTP.';
                    updateStatus('HTTPS Required');
                    browserCheck.textContent = errMsg;
                    browserCheck.style.display = 'block';
                    startBtn.disabled = true;
                    log('Error: Not HTTPS.');
                    return false;
                }
                return true;
            }

            async function startCapture() {
                if (!checkSupport()) return;

                try {
                    // Get screen share permission
                    stream = await navigator.mediaDevices.getDisplayMedia({ 
                        video: { 
                            mediaSource: 'screen',
                            width: { ideal: WIDTH },
                            height: { ideal: HEIGHT }
                        }
                    });
                    video = document.createElement('video');
                    video.srcObject = stream;
                    video.play();

                    // Setup canvas for compression
                    canvas = document.createElement('canvas');
                    canvas.width = WIDTH;
                    canvas.height = HEIGHT;
                    ctx = canvas.getContext('2d');

                    updateStatus('Capturing & Sending... (Stop sharing screen to end)');
                    startBtn.disabled = true;
                    startBtn.textContent = 'Capturing...';
                    browserCheck.style.display = 'none';

                    // Wait for video ready, then start interval
                    video.onloadedmetadata = () => {
                        captureAndSend();  // Initial capture
                        intervalId = setInterval(captureAndSend, INTERVAL);
                        log('Started real-time capture every 3 seconds.');
                    };

                } catch (err) {
                    log(`Permission denied or error: ${err.message}`);
                    updateStatus('Error: Permission denied');
                    if (err.name === 'NotAllowedError') {
                        log('User denied permission. Retry by clicking button.');
                    }
                }
            }

            async function captureAndSend() {
                if (!video || video.ended || !ctx) return;

                // Draw video frame to canvas (compress to 480p)
                ctx.drawImage(video, 0, 0, WIDTH, HEIGHT);
                const imageData = canvas.toDataURL('image/png');  // PNG for quality

                try {
                    // Send to server
                    const formData = new FormData();
                    formData.append('screenshot', dataURItoBlob(imageData), 'screenshot.png');

                    const response = await fetch(SERVER_URL, {
                        method: 'POST',
                        body: formData
                    });

                    if (response.ok) {
                        const msg = await response.text();
                        log(`Sent! Server response: ${msg}`);
                        // Optional: TTS playback (browser native)
                        if ('speechSynthesis' in window && speechSynthesis.speaking === false) {
                            const utterance = new SpeechSynthesisUtterance(msg);
                            utterance.lang = 'hi-IN';  // Hindi
                            speechSynthesis.speak(utterance);
                        }
                    } else {
                        log(`Send failed: ${response.status} - ${response.statusText}`);
                    }
                } catch (err) {
                    log(`Network error: ${err.message}`);
                }
            }

            // Helper: DataURL to Blob for FormData
            function dataURItoBlob(dataURI) {
                const byteString = atob(dataURI.split(',')[1]);
                const mimeString = dataURI.split(',')[0].split(':')[1].split(';')[0];
                const ab = new ArrayBuffer(byteString.length);
                const ia = new Uint8Array(ab);
                for (let i = 0; i < byteString.length; i++) {
                    ia[i] = byteString.charCodeAt(i);
                }
                return new Blob([ab], { type: mimeString });
            }

            // Stop capture
            function stopCapture() {
                if (intervalId) {
                    clearInterval(intervalId);
                    intervalId = null;
                }
                if (stream) {
                    stream.getTracks().forEach(track => track.stop());
                    stream = null;
                }
                if (video) {
                    video.pause();
                    video.srcObject = null;
                    video = null;
                }
                updateStatus('Stopped');
                startBtn.disabled = false;
                startBtn.textContent = 'Send SS';
                log('Capture stopped.');
            }

            // Event listeners
            startBtn.addEventListener('click', startCapture);

            // Auto-stop on stream end
            document.addEventListener('visibilitychange', () => {
                if (document.hidden && stream) {
                    stopCapture();
                }
            });

            // Global stop on page unload
            window.addEventListener('beforeunload', stopCapture);
        </script>
    </body>
    </html>
    """
    return render_template_string(html_template)

@app.route('/start_capture', methods=['POST'])
def start_capture():
    """Trigger capture (legacy)."""
    message = "Capture started! Use frontend button."
    return jsonify({"status": "success", "message": message})

@app.route('/upload_screenshot', methods=['POST'])
def upload_screenshot():
    """Receive screenshot, process, return message."""
    if 'screenshot' not in request.files:
        return jsonify({"error": "No screenshot provided"}), 400
    
    file = request.files['screenshot']
    if file.filename == '':
        return jsonify({"error": "No file selected"}), 400
    
    # Dummy analysis for Free Fire
    response_msg = "Screenshot received! Analysis: Enemy spotted at 3 o'clock. Reload and fire! Health low - heal up."
    
    return response_msg  # Plain text for TTS

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
