from flask import Flask, request, jsonify
import os
import requests  # If forwarding needed

app = Flask(__name__)

# Configuration
ORIGINAL_URL = "https://practice-8waa.onrender.com"  # If forward screenshots here

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
    
    # Dummy analysis - no processing needed
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
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
