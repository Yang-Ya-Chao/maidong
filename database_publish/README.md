# Maidong KTV database

`muse.db` is published as 80 MiB chunks because the complete database is
969,072,640 bytes. The Android client downloads `manifest.json`, appends each
chunk in order, verifies the final size and SHA-256, then atomically installs
the database.

Expected SHA-256:

```text
592ca5c656638d33e93062f8fc1eba06b1151990ee00d96c7b8d197890503394
```

Repository layout expected by the client:

```text
database/manifest.json
database/muse.db.part000
database/muse.db.part001
...
database/muse.db.part011
```

The unsplit `muse.db` is intentionally excluded from Git.
