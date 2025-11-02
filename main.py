import time
import requests
from mss import mss
from io import BytesIO
from PIL import Image
from gtts import gTTS
import pygame
import os

# Configuration
URL = "https://practice-8waa.onrender.com/ss"  # Your endpoint
SCREENSHOT_INTERVAL = 3  # Seconds
AUDIO_FOLDER = "temp_audio"  # Folder for temp audio files

# Create audio folder if not exists
os.makedirs(AUDIO_FOLDER, exist_ok=True)

# Initialize pygame for audio
pygame.mixer.init()

def capture_screenshot():
    """Capture full screen screenshot using mss (fast)."""
    with mss() as sct:
        # Full screen: monitor=1 (or specify {'top': 0, 'left': 0, 'width': 1920, 'height': 1080} for custom)
        monitor = sct.monitors[1]  # Primary monitor
        screenshot = sct.grab(monitor)
        img = Image.frombytes("RGB", screenshot.size, screenshot.bgra, "raw", "BGRX")
        return img

def send_screenshot(img):
    """Send screenshot as POST to URL."""
    # Convert image to bytes
    img_buffer = BytesIO()
    img.save(img_buffer, format='PNG')
    img_buffer.seek(0)
    
    files = {'screenshot': ('screenshot.png', img_buffer, 'image/png')}
    try:
        response = requests.post(URL, files=files)
        if response.status_code == 200:
            return response.text  # Assume response is text message
        else:
            return f"Error: {response.status_code}"
    except Exception as e:
        return f"Request failed: {str(e)}"

def text_to_audio(text):
    """Convert text to speech using gTTS and play with pygame."""
    if not text.strip():
        return
    
    # Save TTS as MP3
    tts = gTTS(text=text, lang='hi')  # Hindi language, change if needed
    audio_file = os.path.join(AUDIO_FOLDER, "response.mp3")
    tts.save(audio_file)
    
    # Play audio
    pygame.mixer.music.load(audio_file)
    pygame.mixer.music.play()
    
    # Wait for playback to finish
    while pygame.mixer.music.get_busy():
        time.sleep(0.1)
    
    # Cleanup
    os.remove(audio_file)

def main():
    print("Starting Free Fire Screen Capture App...")
    print(f"Capturing every {SCREENSHOT_INTERVAL} seconds and sending to {URL}")
    print("Press Ctrl+C to stop.")
    
    try:
        while True:
            # Step 1: Capture screenshot
            print("Capturing screenshot...")
            img = capture_screenshot()
            
            # Step 2: Send to URL
            print("Sending to server...")
            message = send_screenshot(img)
            print(f"Received message: {message}")
            
            # Step 3: Convert to audio and play
            print("Playing audio...")
            text_to_audio(message)
            
            # Wait for next interval
            time.sleep(SCREENSHOT_INTERVAL)
            
    except KeyboardInterrupt:
        print("\nStopped by user.")
    finally:
        pygame.mixer.quit()

if __name__ == "__main__":
    main()
