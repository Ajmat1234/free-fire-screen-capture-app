from flask import Flask, request, jsonify
import os
from io import BytesIO
from PIL import Image
import requests  # If forwarding needed

app = Flask(__name__)

# Configuration
ORIGINAL_URL = "https://practice-8waa.onrender.com"  # If forward screenshots here

# Commented: Local Capture/Audio (uncomment if local run needed)
"""
import time
from mss import mss
from gtts import gTTS
from playsound3 import playsound

SCREENSHOT_INTERVAL = 3
AUDIO_FOLDER = "temp_audio"
os.makedirs(AUDIO_FOLDER, exist_ok=True)

def capture_screenshot():
    with mss() as sct:
        monitor = sct.monitors[1]
        screenshot = sct.grab(monitor)
        img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
        return img

def send_screenshot(img):
    img_buffer = BytesIO()
    img.save(img_buffer, format='PNG')
    img_buffer.seek(0)
    files = {'screenshot': ('screenshot.png', img_buffer, 'image/png')}
    try:
        response = requests.post(app.config['URL'], files=files)  # Use Render URL
        return response.text if response.status_code == 200 else f"Error: {response.status_code}"
    except Exception as e:
        return f"Request failed: {str(e)}"

def text_to_audio(text):
    if not text.strip():
        return
    tts = gTTS(text=text, lang='hi')
    audio_file = os.path.join(AUDIO_FOLDER, "response.mp3")
    tts.save(audio_file)
    playsound(audio_file)
    os.remove(audio_file)

def local_main():
    app.config['URL'] = "http://localhost:5000/upload_screenshot"  # Local test
    print("Starting Local Mode...")
    try:
        while True:
            img = capture_screenshot()
            message = send_screenshot(img)
            print(f"Message: {message}")
            text_to_audio(message)
            time.sleep(SCREENSHOT_INTERVAL)
    except KeyboardInterrupt:
        print("Stopped.")

if __name__ == "__main__":
    local_main()  # Uncomment this line to run local mode
"""

@app.route('/start_capture', methods=['POST'])
def start_capture():
    """Trigger capture (for client apps)."""
    message = "Capture started! Send screenshots to /upload_screenshot."
    return jsonify({"status": "success", "message": message})

@app.route('/upload_screenshot', methods=['POST'])
def upload_screenshot():
    """Receive screenshot, process, return message."""
    if 'screenshot' not in request.files:
        return jsonify({"error": "No screenshot provided"}), 400
    
    file = request.files['screenshot']
    if file.filename == '':
        return jsonify({"error": "No file selected"}), 400
    
    # Optional: Save or process image (e.g., analyze for Free Fire)
    try:
        img = Image.open(file.stream)
        # Dummy analysis - replace with real logic (e.g., OpenCV for enemy detection)
        response_msg = "Screenshot received! Analysis: Enemy spotted at 3 o'clock. Reload and fire!"
        
        # Optional: Forward to original URL
        # img_buffer = BytesIO()
        # img.save(img_buffer, format='PNG')
        # img_buffer.seek(0)
        # files = {'screenshot': ('screenshot.png', img_buffer, 'image/png')}
        # fwd_response = requests.post(ORIGINAL_URL, files=files)
        # if fwd_response.status_code == 200:
        #     response_msg = fwd_response.text
        
    except Exception as e:
        return jsonify({"error": str(e)}), 500
    
    return response_msg  # Plain text response for TTS

@app.route('/', methods=['GET'])
def home():
    return jsonify({"message": "Free Fire Server Running! POST to /upload_screenshot."})

if __name__ == '__main__':
    # Production: Use gunicorn externally
    app.run(host='0.0.0.0', port=int(os.environ.get('PORT', 5000)), debug=False)
