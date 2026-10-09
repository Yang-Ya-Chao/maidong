"""仅同步 i歌霸公开目录；不请求私人歌单或媒体，不使用账号身份。"""
import argparse
import concurrent.futures
import json
import math
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

try:
    from pypinyin import Style, lazy_pinyin
except ImportError:
    raise SystemExit("请先安装目录工具依赖：python -m pip install -r tools/requirements-catalog.txt")


URL = "http://app.ige8.net/cloud.php"
SIZE = 1000


def request(command, params):
    data = urllib.parse.urlencode({"VER": 6, "CMD": command,
                                   "PARAMS": json.dumps(params, ensure_ascii=False)}).encode()
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(URL, data=data), timeout=40) as response:
                result = json.load(response)
            if "Result" in result and int(result["Result"]) < 0:
                raise ValueError(result.get("Description", "目录请求失败"))
            if not isinstance(result.get("List"), list):
                raise ValueError("目录响应缺少 List")
            return result
        except Exception:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def pages(command, params):
    def page(number):
        return request(command, {**params, "Page": {"PageNo": number, "PageSize": SIZE}})
    first = page(1)
    result = list(first["List"])
    count = int(first.get("Page", {}).get("PageCount", 1))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for number, response in zip(range(2, count + 1), pool.map(page, range(2, count + 1))):
            result.extend(response["List"])
            print(f"{command} page {number}/{count}", flush=True)
    return result


def pinyin(name):
    return ("".join(lazy_pinyin(name, style=Style.FIRST_LETTER)).upper(),
            "".join(lazy_pinyin(name)).upper())


def export(destination):
    head = request("GET_SONGS", {"UserId": 0, "Filter": {}, "Page": {"PageNo": 1, "PageSize": 1}})
    songs = pages("GET_SONGS", {"UserId": 0, "Filter": {}, "Order": [{"SongName": 0}]})
    singers = pages("GET_PACGS", {"PackageType": 1, "Filter": ""})
    playlists = [(3, p) for p in pages("GET_PACGS", {"PackageType": 3, "Filter": ""})]
    playlists += [(4, p) for p in pages("GET_PACGS", {"PackageType": 4, "Filter": ""})]
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".building")
    if temporary.exists():
        raise ValueError("目录提取临时文件已存在")
    with closing(sqlite3.connect(temporary)) as db:
        db.executescript("""
        CREATE TABLE source_songs(number TEXT PRIMARY KEY,name TEXT NOT NULL,singer TEXT NOT NULL,
          language TEXT NOT NULL,filename TEXT NOT NULL,version TEXT NOT NULL,duration INTEGER NOT NULL,
          voice_channel INTEGER NOT NULL,pinyin TEXT NOT NULL,pinyin_full TEXT NOT NULL,
          name_len INTEGER NOT NULL,sort_no INTEGER NOT NULL,album TEXT NOT NULL);
        CREATE TABLE source_singers(number TEXT PRIMARY KEY,name TEXT NOT NULL,region TEXT NOT NULL,
          type TEXT NOT NULL,pinyin TEXT NOT NULL,pinyin_full TEXT NOT NULL,image_url TEXT NOT NULL,sort_no INTEGER NOT NULL);
        CREATE TABLE source_playlists(number TEXT NOT NULL,package_type INTEGER NOT NULL,name TEXT NOT NULL,
          picture TEXT NOT NULL,description TEXT NOT NULL,sort_no INTEGER NOT NULL,song_count INTEGER NOT NULL,
          PRIMARY KEY(number,package_type));
        CREATE TABLE source_playlist_songs(playlist_number TEXT NOT NULL,package_type INTEGER NOT NULL,
          song_number TEXT NOT NULL,sort_no INTEGER NOT NULL,PRIMARY KEY(playlist_number,package_type,song_number));
        CREATE TABLE source_singer_songs(singer_number TEXT NOT NULL,song_number TEXT NOT NULL,
          PRIMARY KEY(singer_number,song_number));
        CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        """)
        def add_song(s, order):
            name = s.get("SongName", "")
            initial, full = pinyin(name)
            db.execute("INSERT OR IGNORE INTO source_songs VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                str(s["SongNumber"]), name, s.get("SingerName", ""), s.get("LanguageName", ""),
                s.get("FileName", ""), s.get("Version", ""), int(s.get("Duration") or 0),
                int(s.get("VoiceChannel") or 0), initial, full, sum(c.isalnum() for c in name), order, s.get("Album", "")))
        for order, song in enumerate(songs):
            add_song(song, order)
        singer_ids = {}
        for order, s in enumerate(singers):
            name = s.get("SingerName", "")
            mark = s.get("Mark", "")
            region = next((v for v in ("大陆", "港台", "日韩", "欧美") if v in mark), "其他")
            kind = "男" if "男" in mark else "女" if "女" in mark else "组合"
            initial, full = pinyin(name)
            picture = s.get("MidPicture") or s.get("SmallPicture") or s.get("LargePicture") or ""
            number = str(s["SingerId"])
            singer_ids[name] = number
            db.execute("INSERT OR REPLACE INTO source_singers VALUES(?,?,?,?,?,?,?,?)",
                       (number, name, region, kind, initial, full, picture, order))
        for number, name in db.execute("SELECT number,singer FROM source_songs").fetchall():
            for part in {name, *re.split(r"[/、&]+", name)}:
                singer_id = singer_ids.get(part.strip())
                if singer_id:
                    db.execute("INSERT OR IGNORE INTO source_singer_songs VALUES(?,?)", (singer_id, number))
        for order, (kind, p) in enumerate(playlists):
            number = str(p["PlayId"] if kind == 3 else p["RankId"])
            name = p.get("PlayTitle") if kind == 3 else p.get("RankTitle")
            members = pages("GET_SONGS", {"UserId": 0, "Filter": {"Package": {"Type": kind, "Id": number}}})
            db.execute("INSERT OR REPLACE INTO source_playlists VALUES(?,?,?,?,?,?,?)",
                       (number, kind, name or "", p.get("Picture", ""), p.get("Description", ""), order, len(members)))
            for position, song in enumerate(members):
                add_song(song, len(songs) + order * SIZE + position)
                db.execute("INSERT OR IGNORE INTO source_playlist_songs VALUES(?,?,?,?)", (number, kind, str(song["SongNumber"]), position))
            print(f"playlist {order + 1}/{len(playlists)}: {name}, {len(members)}", flush=True)
            db.commit()
        db.executescript("""
        CREATE INDEX song_name ON source_songs(name);
        CREATE INDEX song_order ON source_songs(sort_no);
        CREATE INDEX song_language ON source_songs(language,sort_no);
        CREATE INDEX song_length ON source_songs(name_len,sort_no);
        CREATE INDEX song_filename ON source_songs(filename);
        CREATE INDEX singer_name ON source_singers(name);
        CREATE INDEX singer_song ON source_singer_songs(song_number);
        CREATE INDEX playlist_song ON source_playlist_songs(song_number,package_type);
        """)
        db.executemany("INSERT INTO meta VALUES(?,?)", [
            ("schema_version", "1"), ("provider", "igeba"),
            ("snapshot_utc", datetime.now(timezone.utc).isoformat()),
            ("remote_version", str(head["List"][0]["SongNumber"]) + ":" + str(head["Page"]["RecordCount"])),
            ("advertised_song_count", str(head["Page"]["RecordCount"])),
            ("song_count", str(db.execute("SELECT COUNT(*) FROM source_songs").fetchone()[0]))])
        db.commit()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    temporary.replace(destination)
    characters = set("".join(s.get("SongName", "") + s.get("SingerName", "") for s in songs))
    charmap = {ch: lazy_pinyin(ch)[0].upper() for ch in characters if '\u4e00' <= ch <= '\u9fff'}
    destination.with_name("pinyin_chars.json").write_text(json.dumps(charmap, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"path": str(destination), "bytes": destination.stat().st_size,
                      "songs": len(songs), "singers": len(singers), "playlists": len(playlists)}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    export(parser.parse_args().destination)
