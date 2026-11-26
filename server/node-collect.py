#!/usr/bin/env python3
"""Fetch NODE Companion's tank video and training data from the phone into ~/node-data.

Run by cron every few minutes on the server. Each run asks the phone what it has (GET data), fetches
whatever is new or has grown, and prunes video older than KEEP_VIDEO_DAYS, or the oldest video while
the disk is short of MIN_FREE_BYTES. Pictures, numbers and labels are kept.

The phone's address and key are in ~/.config/node-collect, one line: http://<phone>:8081/<key>/
"""
import fcntl
import json
import os
import re
import shutil
import sys
import time
import urllib.request

DATA = os.path.expanduser("~/node-data")
CONFIG = os.path.expanduser("~/.config/node-collect")
LOG = os.path.join(DATA, "collect.log")
KEEP_VIDEO_DAYS = 30
MIN_FREE_BYTES = 20 * 10**9

# The same paths the phone serves, and only those: a reply cannot write anywhere else.
PATH = re.compile(r"tank-video/NODE_\d{8}_\d{6}\.mp4|training/(\d{8}/(\d{6}\.jpg|minutes\.csv)|labels\.jsonl|days\.csv)")


def log(line):
    with open(LOG, "a") as f:
        f.write(time.strftime("%Y-%m-%d %H:%M:%S  ") + line + "\n")


def fetch(base, path, dest):
    """One file to dest, by way of a .part, so a cut-off download is never taken for the file."""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    part = dest + ".part"
    with urllib.request.urlopen(base + "data/" + path, timeout=60) as r, open(part, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    os.replace(part, dest)


def prune():
    video = os.path.join(DATA, "tank-video")
    if not os.path.isdir(video):
        return
    clips = sorted(os.listdir(video))
    cutoff = time.time() - KEEP_VIDEO_DAYS * 86400
    for name in clips:
        p = os.path.join(video, name)
        old = os.path.getmtime(p) < cutoff
        short = shutil.disk_usage(DATA).free < MIN_FREE_BYTES
        if not (old or short):
            break
        os.remove(p)
        log("pruned " + name)


def main():
    os.makedirs(DATA, exist_ok=True)
    # One run at a time: a long first fetch must not be joined by the next one.
    lock = open(os.path.join(DATA, ".lock"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    base = open(CONFIG).read().strip()
    try:
        with urllib.request.urlopen(base + "data", timeout=20) as r:
            files = json.load(r)["files"]
    except Exception as e:
        log(f"phone not answering: {e}")
        return
    got = 0
    for f in files:
        path, size = f["path"], f["size"]
        if not PATH.fullmatch(path):
            log("refused " + path)
            continue
        dest = os.path.join(DATA, path)
        if os.path.exists(dest) and os.path.getsize(dest) == size:
            continue
        try:
            fetch(base, path, dest)
            got += 1
        except Exception as e:
            log(f"{path}: {e}")
    if got:
        log(f"fetched {got}")
    prune()


if __name__ == "__main__":
    sys.exit(main())
