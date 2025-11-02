from flask import Flask, request, jsonify
import main  # Import your main logic (but run in thread for async)

app = Flask(__name__)

@app.route('/start_capture', methods=['POST'])
def start_capture():
    # Trigger main script (in production, use threading)
    message = "Capture started! Check your local machine."
    return jsonify({"status": "success", "message": message})

@app.route('/upload_screenshot', methods=['POST'])
def upload_screenshot():
    # Handle screenshot upload (server-side processing if needed)
    if 'screenshot' not in request.files:
        return jsonify({"error": "No screenshot"}), 400
    
    file = request.files['screenshot']
    # Process here if needed, else forward to your URL
    # For now, simulate response
    response_msg = "Screenshot received! Analysis: Enemy at 12 o'clock."  # Dummy
    return response_msg

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
