"""Correct display word counts and add the singer reverse index; source identities remain intact."""
import sqlite3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/assets/igeba_catalog.db"
with sqlite3.connect(path) as db:
    assert db.execute("SELECT value FROM meta WHERE key='provider'").fetchone()[0] == "igeba"
    db.execute("CREATE INDEX IF NOT EXISTS singer_song ON source_singer_songs(song_number)")
    records = db.execute("SELECT number,name FROM source_songs").fetchall()
    db.executemany("UPDATE source_songs SET name_len=? WHERE number=?",
                   ((sum(ch.isalnum() for ch in name), number) for number, name in records))
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
print("Updated display counts and reverse index for", len(records), "source songs")
