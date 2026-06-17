import json

with open("scratch/genius_search_hindi.json", "r", encoding="utf-16") as f:
    data = json.load(f)
    
sections = data.get("response", {}).get("sections", [])
for sec in sections:
    if sec.get("type") == "song":
        hits = sec.get("hits", [])
        for hit in hits:
            result = hit.get("result", {})
            url = result.get("url")
            print(url)
