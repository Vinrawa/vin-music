import json

with open("scratch/genius_search.json", "r", encoding="utf-16") as f:
    data = json.load(f)
    
sections = data.get("response", {}).get("sections", [])
print(f"Number of sections: {len(sections)}")
for sec in sections:
    type_ = sec.get("type")
    print(f"Section type: {type_}")
    hits = sec.get("hits", [])
    print(f"  Number of hits: {len(hits)}")
    for hit in hits[:3]:
        result = hit.get("result", {})
        title = result.get("title")
        artist = result.get("primary_artist", {}).get("name")
        url = result.get("url")
        print(f"    - {title} by {artist} (URL: {url})")
