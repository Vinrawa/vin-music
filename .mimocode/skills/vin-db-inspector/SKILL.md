---
name: vin-db-inspector
description: Query and analyze the Vin Music v2 bundled databases (recommendations.db and genre_graph.json) using Python scripts for track coverage, genre distribution, cluster analysis, and recommendation tracing.
---

# Vin Music v2 — Database Inspector

Reusable workflow for querying the bundled Spotify dataset and Every Noise genre graph. All analysis uses Python with sqlite3 and json — no external dependencies.

## Data Files

- **recommendations.db** (53 MB): `app/src/main/assets/recommendations.db` — 500K tracks, 100 clusters, Spotify audio features + genre labels
- **genre_graph.json** (5.5 MB): `app/src/main/assets/genre_graph.json` — Every Noise genre similarity (3,651 entries, 54K artist→genre mappings)

## Common Queries

### Track count by artist

```python
import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\recommendations.db')
cur = conn.cursor()
cur.execute("SELECT artist_name, COUNT(*) as n FROM tracks GROUP BY artist_name ORDER BY n DESC LIMIT 20")
for row in cur.fetchall():
    print(f"{row[0]}: {row[1]} tracks")
conn.close()
```

### Genre distribution

```python
import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\recommendations.db')
cur = conn.cursor()
cur.execute("SELECT genre, COUNT(*) as n FROM tracks WHERE genre != '' GROUP BY genre ORDER BY n DESC LIMIT 20")
for row in cur.fetchall():
    print(f"{row[0]}: {row[1]} tracks")
conn.close()
```

### Cluster coverage for an artist

```python
import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\recommendations.db')
cur = conn.cursor()
artist = "J. Cole"  # change as needed
cur.execute("SELECT cluster_id, COUNT(*) FROM tracks WHERE artist_name = ? GROUP BY cluster_id ORDER BY COUNT(*) DESC", (artist,))
for row in cur.fetchall():
    print(f"Cluster {row[0]}: {row[1]} tracks")
conn.close()
```

### Trace cluster-NN recommendations for a seed track

```python
import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\recommendations.db')
cur = conn.cursor()
# Find seed track
cur.execute("SELECT id, cluster_id, danceability, energy, valence, tempo, acousticness FROM tracks WHERE artist_name = ? AND track_name LIKE ?", ("J. Cole", "%Rich%"))
seed = cur.fetchone()
if seed:
    seed_id, cluster_id = seed[0], seed[1]
    print(f"Seed: cluster {cluster_id}, features: E={seed[3]}, V={seed[4]}, D={seed[2]}, T={seed[5]}, A={seed[6]}")
    # Find cluster neighbors
    cur.execute("SELECT track_name, artist_name, danceability, energy, valence, tempo, acousticness FROM tracks WHERE cluster_id = ? AND id != ? LIMIT 10", (cluster_id, seed_id))
    for row in cur.fetchall():
        print(f"  {row[0]} - {row[1]} (E={row[3]}, V={row[4]})")
conn.close()
```

### Genre graph analysis

```python
import json, sys
sys.stdout.reconfigure(encoding='utf-8')
with open(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\genre_graph.json', 'r') as f:
    data = json.load(f)
# Artist genre mappings
artist_map = data.get('artist_genres', {})
print(f"Total artists in genre map: {len(artist_map)}")
# Check specific artist
artist_key = "kendricklamar"  # Every Noise uses concatenated lowercase
if artist_key in artist_map:
    print(f"Genres for {artist_key}: {artist_map[artist_key]}")
# Genre similarity entries
sim = data.get('genre_similarity', {})
print(f"Total genre similarity entries: {len(sim)}")
```

## Key Schema

**tracks table** (recommendations.db):
- `id`, `track_name`, `artist_name`, `album_name`
- `danceability`, `energy`, `key`, `loudness`, `mode`, `speechiness`, `acousticness`, `instrumentalness`, `liveness`, `valence`, `tempo`
- `cluster_id` (0-99), `genre` (Every Noise label, empty string if unmatched)

**genre_graph.json** structure:
- `artist_genres`: `{artist_name_lower: [genre1, genre2, ...]}` — 54K entries
- `genre_similarity`: `{genre: [{genre, weight}, ...]}` — 3,651 entries
- Keys use no-space lowercase: `"conscioushiphop"`, `"dancepop"`, `"chicagorap"`

## Tips

- Always add `sys.stdout.reconfigure(encoding='utf-8')` — track titles contain Unicode (e.g., "Rich N****z")
- DB genres and genre_graph.json keys both use no-space lowercase — consistent format
- 73% of 500K tracks have genre labels; 27% have empty genre string
- For large result sets, use `LIMIT` to avoid output truncation
- The old 2.17M DB (118 MB) was replaced back to 500K smart DB (53 MB) for APK size reasons
