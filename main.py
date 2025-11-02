from flask import Flask, request, jsonify
import os
import requests  # If forwarding needed

app = Flask(__name__)

# Configuration
ORIGINAL_URL = "https://practice-8waa.onrender.com"  # If forward screenshots here

# Commented: Local Capture/Audio (uncomment if local run needed later)
"""
import time
from mss import mss
from gtts import gTTS
from playsound3 import playsound
from PIL import Image
from io import BytesIO

SCREENSHOT_INTERVAL = 3
AUDIO_FOLDER = "temp_audio"
os.makedirs(AUDIO_FOLDER, exist_ok=True)

def capture_screenshot():
    with mss() as sct:
        monitor = sct.monitors[1]
        screenshot = sct.grab(monitor)
        img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
        return img

def send_screenshot(img, url):
    img_buffer = BytesIO()
    img.save(img_buffer, format='PNG')
    img_buffer.seek(0)
    files = {'screenshot': ('screenshot.png', img_buffer, 'image/png')}
    try:
        response = requests.post(url, files=files)
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
    app.config['URL'] = "http://localhost:5000/upload_screenshot"
    print("Starting Local Mode...")
    try:
        while True:
            img = capture_screenshot()
            message = send_screenshot(img, app.config['URL'])
            print(f"Message: {message}")
            text_to_audio(message)
            time.sleep(SCREENSHOT_INTERVAL)
    except KeyboardInterrupt:
        print("Stopped.")

if __name__ == "__main__":
    local_main()  # Uncomment to run local mode
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
    
    # Dummy analysis - no image processing needed (Pillow removed to avoid build error)
    response_msg = "Screenshot received! Analysis: Enemy spotted at 3 o'clock. Reload and fire!"
    
    # Optional: Forward to original URL (uncomment if needed)
    # try:
    #     file.stream.seek(0)
    #     files = {'screenshot': (file.filename, file.stream, file.content_type)}
    #     fwd_response = requests.post(ORIGINAL_URL, files=files)
    #     if fwd_response.status_code == 200:
    #         response_msg = fwd_response.text
    # except Exception as e:
    #     response_msg += f" (Forward error: {str(e)})"
    
    return response_msg  # Plain text for TTS

@app.route('/', methods=['GET'])
def home():
    return jsonify({"message": "Free Fire Server Running! POST to /upload_screenshot for analysis."})

if __name__ == '__main__':
    # Production: Use gunicorn externally
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
