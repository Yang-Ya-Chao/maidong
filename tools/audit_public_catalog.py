"""Read-only check of i歌霸 anonymous public catalog; never requests media."""
import concurrent.futures
import json
import sqlite3
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def request(command, params):
    form = urllib.parse.urlencode({"VER": 6, "CMD": command,
                                  "PARAMS": json.dumps(params, ensure_ascii=False)}).encode()
    with urllib.request.urlopen(urllib.request.Request(
            "http://app.ige8.net/cloud.php", data=form), timeout=30) as response:
        value = json.load(response)
    if not isinstance(value.get("List"), list):
        raise ValueError("Catalog did not return List")
    return value


def main():
    params = {"PackageType": 3, "Filter": "", "Page": {"PageNo": 1, "PageSize": 100}}
    lists = request("GET_PACGS", params)
    head = request("GET_SONGS", {"UserId": 0, "Filter": {},
                               "Page": {"PageNo": 1, "PageSize": 1}})
    report = {"checked_utc": datetime.now(timezone.utc).isoformat(),
              "anonymous_user_id": 0, "live_playlist_count": lists["Page"]["RecordCount"],
              "live_song_count": head["Page"]["RecordCount"]}
    with sqlite3.connect(f"file:{ROOT / 'app/src/main/assets/igeba_catalog.db'}?mode=ro", uri=True) as db:
        report["cache_integrity"] = db.execute("PRAGMA integrity_check").fetchone()[0]
        report["cache_counts"] = {table: db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                                  for table in ("source_songs", "source_singers", "source_playlists",
                                                "source_playlist_songs")}
        report["dangling_playlist_members"] = db.execute(
            "SELECT COUNT(*) FROM source_playlist_songs p LEFT JOIN source_songs s "
            "ON s.number=p.song_number WHERE s.number IS NULL").fetchone()[0]
        cache_counts = dict(db.execute("SELECT number,song_count FROM source_playlists WHERE package_type=3"))

    def inspect(p):
        number = str(p["PlayId"])
        result = request("GET_SONGS", {"UserId": 0, "Filter": {"Package": {"Type": 3, "Id": number}},
                                      "Page": {"PageNo": 1, "PageSize": 5}})
        rows = result["List"]
        item = {"id": number, "title": p["PlayTitle"], "live_count": int(result["Page"]["RecordCount"]),
                "cached_count": cache_counts.get(number), "returned_count": len(rows),
                "sample": [{key: s.get(key) for key in ("SongNumber", "SongName", "SingerName",
                                                       "FileName", "VoiceChannel")} for s in rows]}
        if item["live_count"] != item["cached_count"]:
            full = request("GET_SONGS", {"UserId": 0, "Filter": {"Package": {"Type": 3, "Id": number}},
                                        "Page": {"PageNo": 1, "PageSize": 1000}})
            numbers = [str(s["SongNumber"]) for s in full["List"]]
            item["full_returned_count"] = len(numbers)
            item["unique_song_count"] = len(set(numbers))
            item["duplicate_song_numbers"] = sorted({n for n in numbers if numbers.count(n) > 1})
            with sqlite3.connect(f"file:{ROOT / 'app/src/main/assets/igeba_catalog.db'}?mode=ro", uri=True) as db:
                cached = {r[0] for r in db.execute("SELECT song_number FROM source_playlist_songs "
                                                  "WHERE playlist_number=? AND package_type=3", (number,))}
            item["uncached_song_numbers"] = sorted(set(numbers) - cached)
        return item

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        report["playlists"] = list(pool.map(inspect, lists["List"]))
    report["playlist_count_mismatches"] = sum(
        p["live_count"] != p["cached_count"] for p in report["playlists"])
    output = ROOT / "docs/public-catalog-audit.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "playlists"}, ensure_ascii=False))
    print(json.dumps(report["playlists"][:3], ensure_ascii=False))


if __name__ == "__main__":
    main()
