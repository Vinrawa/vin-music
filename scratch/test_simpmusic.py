import urllib.request
import json
import ssl

def main():
    # Disable SSL verification
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    
    # 1. Search SimpMusic
    q = "Mockingbird Eminem"
    url = f"https://lyrics.simpmusic.org/api/v1/search?q={urllib.parse.quote(q)}"
    print(f"Calling search URL: {url}")
    
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, context=ctx) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print("Search data:", data)
            if not data:
                print("No search results found.")
                return
            song_id = data[0]["id"]
            print(f"Found song ID: {song_id}")
            
            # 2. Get lyrics
            lyr_url = f"https://lyrics.simpmusic.org/api/v1/lyrics/{song_id}"
            print(f"Calling lyrics URL: {lyr_url}")
            req2 = urllib.request.Request(lyr_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req2, context=ctx) as resp2:
                lyr_data = json.loads(resp2.read().decode("utf-8"))
                print("Lyrics keys:", list(lyr_data.keys()))
                print("Synced length:", len(lyr_data.get("synced", "")))
                print("Plain length:", len(lyr_data.get("plain", "")))
    except Exception as e:
        print(f"Error calling SimpMusic: {e}")

if __name__ == "__main__":
    main()
