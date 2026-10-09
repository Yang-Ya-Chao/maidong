"""Compare cached singer pages against anonymous public singer packages."""
import concurrent.futures
import json
import sqlite3
from pathlib import Path
from audit_public_catalog import request

ROOT = Path(__file__).resolve().parents[1]
with sqlite3.connect(f"file:{ROOT / 'app/src/main/assets/igeba_catalog.db'}?mode=ro", uri=True) as db:
    singers = db.execute("SELECT g.number,g.name,COUNT(r.song_number) FROM source_singers g "
                         "LEFT JOIN source_singer_songs r ON r.singer_number=g.number "
                         "GROUP BY g.number ORDER BY COUNT(r.song_number) DESC LIMIT 10").fetchall()
    missing = db.execute("SELECT COUNT(*) FROM source_songs s WHERE NOT EXISTS "
                         "(SELECT 1 FROM source_singer_songs r WHERE r.song_number=s.number)").fetchone()[0]


def check(singer):
    number, name, cached = singer
    result = request("GET_SONGS", {"UserId": 0, "Filter": {"Package": {"Type": 1, "Id": number}},
                                  "Page": {"PageNo": 1, "PageSize": 1000}})
    rows = result["List"]
    return {"id": number, "name": name, "cached": cached,
            "advertised": int(result["Page"]["RecordCount"]),
            "first_page_actual": len(rows), "first_page_unique": len({str(s['SongNumber']) for s in rows})}


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    report = {"songs_without_cached_singer_relation": missing, "samples": list(pool.map(check, singers))}
(ROOT / "docs/singer-relations-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=True))
