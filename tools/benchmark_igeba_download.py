"""Compare bounded 2-MiB range reads. Never logs or saves signed media URLs."""
import concurrent.futures
import json
import random
import time
import requests

CHUNK = 2 * 1024 * 1024


def address(number):
    begin = time.perf_counter()
    result = requests.post("http://app.ige8.net/telnet.php", data={
        "VER": "10.0", "CMD": "GET_SONGADDR",
        "PARAMS": json.dumps({"UserId": random.randrange(100000, 1000000), "SongNumber": number}),
    }, timeout=(15, 40)).json()
    rights = result.get("Rights", {}).get("Code")
    if rights not in (0, 1):
        raise RuntimeError(f"source declined ({rights})")
    return result["FileUrl"], time.perf_counter() - begin


def block(url, start, end):
    begin = time.perf_counter()
    response = requests.get(url, headers={"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"},
                            timeout=(15, 30), stream=True)
    with response:
        headers = time.perf_counter() - begin
        if response.status_code != 206:
            raise RuntimeError(f"media HTTP {response.status_code}")
        content_range = response.headers.get("Content-Range", "")
        raw = response.content
    return len(raw), content_range, headers, time.perf_counter() - begin


def compare(number):
    url, resolve_seconds = address(number)
    size, header, _, _ = block(url, 0, 63)
    exclusive = size == 63
    if size not in (63, 64):
        raise RuntimeError("unknown source range convention")
    total = int(header.rsplit("/", 1)[1])
    result = {"song": number, "resolveSeconds": round(resolve_seconds, 3), "total": total,
              "exclusiveEnd": exclusive, "runs": []}
    for workers in (1, 2, 2, 1):
        ranges = [(i * CHUNK, min((i + 1) * CHUNK, total)) for i in range(8) if i * CHUNK < total]
        begin = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(block, url, start, stop if exclusive else stop - 1) for start, stop in ranges]
            blocks = [future.result() for future in futures]
        elapsed = time.perf_counter() - begin
        for (start, stop), (received, span, _, _) in zip(ranges, blocks):
            if received != stop - start or span != f"bytes {start}-{stop-1}/{total}":
                raise RuntimeError("range or size mismatch")
        result["runs"].append({"connections": workers, "bytes": sum(b[0] for b in blocks),
                               "seconds": round(elapsed, 3),
                               "MiBps": round(sum(b[0] for b in blocks) / elapsed / 1048576, 3),
                               "headerWaitSeconds": round(sum(b[2] for b in blocks), 3)})
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    for number in ("30000458", "20051274"):
        try:
            compare(number)
        except Exception as error:
            # Exception messages intentionally exclude the signed URL.
            print(json.dumps({"song": number, "errorType": type(error).__name__}), flush=True)
