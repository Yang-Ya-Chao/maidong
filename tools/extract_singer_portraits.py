"""从旧曲库只提取歌星名称和图片地址，生成独立的只读图片索引。"""
import argparse
import json
import re
import sqlite3
import unicodedata
from contextlib import closing
from pathlib import Path
from urllib.parse import quote


def normalize(name):
    return re.sub(r"[\s·•・、,/\\&]+", "", unicodedata.normalize("NFKC", name)).casefold()


def extract(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".building")
    if temporary.exists():
        raise ValueError("存在未完成的提取文件，请检查后重新运行")
    count = 0
    try:
        with closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)) as old:
            with closing(sqlite3.connect(temporary)) as new:
                new.execute("CREATE TABLE singer_portraits(name_key TEXT PRIMARY KEY, name TEXT NOT NULL, image_url TEXT NOT NULL)")
                new.execute("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                cdn = "https://pub.mcdn.cherryonline.cn/"
                if old.execute("SELECT 1 FROM sqlite_master WHERE name='global_confs'").fetchone():
                    row = old.execute("SELECT cdn_path FROM global_confs WHERE TRIM(COALESCE(cdn_path,''))!='' LIMIT 1").fetchone()
                    if row:
                        cdn = row[0].rstrip("/") + "/"
                rows = old.execute("SELECT name,image FROM singers WHERE deleted_at IS NULL AND TRIM(COALESCE(name,''))!='' AND TRIM(COALESCE(image,''))!='' ORDER BY COALESCE(local_hot_score,0) DESC,COALESCE(hot_score,0) DESC")
                for name, image in rows:
                    image = image.strip()
                    url = image if image.startswith(("http://", "https://", "/")) else cdn + quote(image, safe="/")
                    if url.startswith(("http://", "https://")) and "?" not in url:
                        url += "?imageView2/1/w/100/h/100/q/95!/format/webp"
                    cursor = new.execute("INSERT OR IGNORE INTO singer_portraits VALUES(?,?,?)", (normalize(name), name, url))
                    count += cursor.rowcount
                new.execute("INSERT INTO meta VALUES('schema_version','1')")
                new.execute("INSERT INTO meta VALUES('record_count',?)", (str(count),))
                new.commit()
                assert new.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        temporary.replace(destination)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return {"records": count, "bytes": destination.stat().st_size,
            "tables": ["singer_portraits", "meta"], "bitmap_files_embedded": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(extract(args.source, args.destination), ensure_ascii=False, indent=2))
