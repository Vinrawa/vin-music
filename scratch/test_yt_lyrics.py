import urllib.request
import json
import sys

def post_yt(endpoint, body, client_name="WEB_REMIX", client_version="1.20231214.00.00", ua=None):
    url = f"https://music.youtube.com/youtubei/v1/{endpoint}?prettyPrint=false"
    if not ua:
        ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
    headers = {
        "Content-Type": "application/json",
        "User-Agent": ua,
        "Referer": "https://music.youtube.com/"
    }
    
    # Insert client context
    body_with_context = body.copy()
    body_with_context["context"] = {
        "client": {
            "clientName": client_name,
            "clientVersion": client_version,
            "hl": "en",
            "gl": "IN"
        }
    }
    
    req_data = json.dumps(body_with_context).encode("utf-8")
    req = urllib.request.Request(url, data=req_data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error calling {endpoint} with client {client_name}: {e}")
        return None

def main():
    video_id = "S9bCLPwzSC0"  # Mockingbird
    if len(sys.argv) > 1:
        video_id = sys.argv[1]
        
    print(f"Testing lyrics watch details for video_id: {video_id}")
    
    # 1. Next request (using WEB_REMIX)
    next_resp = post_yt("next", {"videoId": video_id})
    if not next_resp:
        return
        
    # Extract lyrics browseId
    tabs = next_resp.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
    
    browse_id = None
    for tab in tabs:
        tab_renderer = tab.get("tabRenderer", {})
        if tab_renderer.get("title") == "Lyrics":
            browse_id = tab_renderer.get("endpoint", {}).get("browseEndpoint", {}).get("browseId")
            unselectable = tab_renderer.get("unselectable")
            print(f"Found Lyrics Tab! browseId={browse_id}, unselectable={unselectable}")
            break
            
    if not browse_id:
        print("No lyrics browseId found in /next response.")
        return
        
    # 2. Browse request (using ANDROID_MUSIC 5.45.52)
    ua_android = "com.google.android.apps.youtube.music/5.45.52 (Linux; U; Android 12) gzip"
    browse_resp = post_yt("browse", {"browseId": browse_id}, client_name="ANDROID_MUSIC", client_version="5.45.52", ua=ua_android)
    if not browse_resp:
        return
        
    # Check if lyrics not available
    contents = browse_resp.get("contents", {})
    if "messageRenderer" in contents:
        msg = contents["messageRenderer"]["text"]["runs"][0]["text"]
        print(f"Message returned from /browse: {msg}")
        return
        
    # Check for musicDescriptionShelfRenderer recursively
    def find_shelf(data):
        if isinstance(data, dict):
            if "musicDescriptionShelfRenderer" in data:
                return data["musicDescriptionShelfRenderer"]
            for v in data.values():
                res = find_shelf(v)
                if res:
                    return res
        elif isinstance(data, list):
            for item in data:
                res = find_shelf(item)
                if res:
                    return res
        return None
        
    shelf = find_shelf(browse_resp)
    if shelf:
        runs = shelf.get("description", {}).get("runs", [])
        lyrics_text = "".join([r.get("text", "") for r in runs])
        footer_runs = shelf.get("footer", {}).get("runs", [])
        footer_text = "".join([r.get("text", "") for r in footer_runs])
        print("\n--- LYRICS FOUND! ---")
        print(lyrics_text[:300] + "...")
        print(f"Total lyrics length: {len(lyrics_text)}")
        print("----------------------")
        print(f"Attribution: {footer_text}")
    else:
        print("Could not find musicDescriptionShelfRenderer in response.")

if __name__ == "__main__":
    main()
