import re

with open("scratch/lyrics.html", "r", encoding="utf-16") as f:
    html = f.read()

# Let's see if we can find data-lyrics-container="true"
# <div data-lyrics-container="true" class="Lyrics__Container-sc-1ynbvzw-1 kUgSbL">...</div>
containers = re.findall(r'<div[^>]*data-lyrics-container="true"[^>]*>(.*?)</div>', html, re.DOTALL)
print(f"Found {len(containers)} containers.")

def clean_html(text):
    # Replace <br/> or <br> with newline
    text = re.sub(r'<br\s*/?>', '\n', text)
    # Remove all other HTML tags
    text = re.sub(r'<.*?>', '', text)
    # Decode HTML entities
    import html as html_lib
    text = html_lib.unescape(text)
    return text.strip()

if containers:
    full_lyrics = "\n".join([clean_html(c) for c in containers])
    print("\n--- SCRAPED LYRICS ---")
    print(full_lyrics[:400] + "...")
    print(f"Total lyrics length: {len(full_lyrics)}")
else:
    # Try older format: <div class="lyrics">...</div>
    old_match = re.search(r'<div[^>]*class="lyrics"[^>]*>(.*?)</div>', html, re.DOTALL)
    if old_match:
        print("\n--- SCRAPED LYRICS (OLD FORMAT) ---")
        print(clean_html(old_match.group(1))[:400] + "...")
    else:
        print("Could not find any lyrics container.")
