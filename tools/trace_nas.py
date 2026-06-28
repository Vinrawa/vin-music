import sqlite3, json

# 1. Check if Nas track is in the DB
conn = sqlite3.connect(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\recommendations.db')
cur = conn.cursor()

print("=== STEP 1: Is Nas in the DB? ===")
cur.execute("SELECT title, artist, genre, cluster_id, energy, dance, valence, tempo, acoustic FROM tracks WHERE artist LIKE '%Nas%' LIMIT 10")
nas_tracks = cur.fetchall()
for r in nas_tracks:
    print(f"  {r[0]} | {r[1]} | genre={r[2]} | cluster={r[3]} | e={r[4]} d={r[5]} v={r[6]} t={r[7]} a={r[8]}")

if not nas_tracks:
    print("  NOT FOUND!")

# 2. Check the cluster neighbors
print("\n=== STEP 2: Cluster-NN for Nas ===")
if nas_tracks:
    seed = nas_tracks[0]
    cluster = seed[3]
    energy = seed[4]
    dance = seed[5]
    valence = seed[6]
    tempo = seed[7]
    acoustic = seed[8]
    
    cur.execute(f"""SELECT title, artist, genre, cluster_id, energy, dance, valence, tempo, acoustic FROM tracks 
        WHERE cluster_id = {cluster}
        ORDER BY ((energy-{energy})*(energy-{energy})+(dance-{dance})*(dance-{dance})+(valence-{valence})*(valence-{valence})+((tempo-{tempo})*(tempo-{tempo})/4)+(acoustic-{acoustic})*(acoustic-{acoustic})) ASC
        LIMIT 15""")
    neighbors = cur.fetchall()
    print(f"  Seed: {seed[0]} by {seed[1]} (genre={seed[2]}, cluster={cluster})")
    print(f"  Top 15 cluster neighbors:")
    for i, r in enumerate(neighbors):
        print(f"    {i+1}. {r[0]} by {r[1]} | genre={r[2]}")

# 3. Check Every Noise for Nas genre
print("\n=== STEP 3: Every Noise genre graph ===")
with open(r'D:\ASUS\Documents\vin-music-v2\app\src\main\assets\genre_graph.json', 'r') as f:
    data = json.load(f)

amap = data['artist_genre_map']
nas_genres = amap.get('nas', [])
print(f"  Nas genres: {nas_genres}")

gsim = data['genre_similar']
for g in nas_genres:
    sims = gsim.get(g, [])
    top = sorted(sims, key=lambda x: -x['weight'])[:5]
    print(f"  {g} similar: {[s['genre'] for s in top]}")

# 4. Check what the genre filter would do
print("\n=== STEP 4: Genre filter check ===")
if nas_tracks:
    seed_genre = nas_tracks[0][2].lower()
    print(f"  Seed genre: '{seed_genre}'")
    
    # Check if cluster neighbors pass genre filter
    for r in neighbors:
        track_genre = r[2].lower()
        # Simulate areGenresSimilar
        if seed_genre in gsim:
            sim_genres = [s['genre'] for s in gsim[seed_genre]]
            passes = track_genre == seed_genre or track_genre in sim_genres
        else:
            passes = True  # unknown genre, don't filter
        status = "PASS" if passes else "FILTERED OUT"
        print(f"    {r[1]}: genre='{track_genre}' -> {status}")

conn.close()
