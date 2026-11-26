#!/usr/bin/env python3
"""Chat: a ChatGPT-like page answered by local models on this server, for any question, with your aquarium
as its special subject.

A question about the tank brings in what NODE Companion knows: the phone's live tank, its days
(~/node-data/training/days.csv), your care sheet. Every question gets the lasting facts remembered from your
chats. Answers come in tiers, cheapest first: straight from the data (no AI), a quick small model, a deep large
one, or a look at the tank's newest picture; and with a web search, through SearXNG on this server, when the
question needs current facts. No AI company sees anything: only searches leave your network.

Run: python3 chat.py   (then open the address it prints; cron @reboot keeps it running)
Standard library only.
"""
import json
import os
import re
import secrets
import threading
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OLLAMA = "http://127.0.0.1:11434/api/chat"
QUICK = "qwen2.5:3b"
# Llama 3.1 8B beat qwen2.5:7b and mistral:7b on the tank's questions: most accurate, least made up (README).
DEEP = os.environ.get("CHAT_DEEP", "llama3.1:8b")
VISION = "gemma3:4b"
PORT = 8090
# SearXNG on this server: the web search, with no account or key.
SEARXNG = "http://127.0.0.1:8888/search"
SEARCH_RESULTS = 5

HOME = os.path.expanduser("~/aquarium-chat")
CHATS = os.path.join(HOME, "chats")
MEMORY = os.path.join(HOME, "memory.json")
SHEET = os.path.join(HOME, "care-sheet.md")
KEY_FILE = os.path.join(HOME, "key")
PID_FILE = os.path.join(HOME, "pid")
NODE_CONFIG = os.path.expanduser("~/.config/node-collect")
DAYS = os.path.expanduser("~/node-data/training/days.csv")
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat.html")

MAX_TEXT = 2000
CHAT_ID = re.compile(r"[0-9a-f]{12}")
LOCK = threading.Lock()

TIER_NAMES = {0: "From your data", 1: "Quick", 2: "Deep", 3: "Looked at the tank"}

# -- knowledge ---------------------------------------------------------------------------------------------------


def ago(ms, now_ms):
    days = (now_ms - ms) / 86_400_000
    if days < 1:
        hours = (now_ms - ms) / 3_600_000
        return "just now" if hours < 1 / 60 else f"{hours:.0f} hours ago" if hours >= 1.5 else "an hour ago"
    return "yesterday" if days < 2 else f"{days:.0f} days ago"


def tank_lines(tank, now_ms):
    """The phone's tank JSON as short lines a small model reads quickly."""
    if not tank:
        return ["The phone (NODE) is not answering: its care log and live state are unknown right now."]
    out = []
    about = ", ".join(x for x in [tank.get("kind"), f"{tank['litres']} litres" if tank.get("litres") else None,
                                  tank.get("livestock") and "fish: " + tank["livestock"],
                                  tank.get("plants") and "plants: " + tank["plants"]] if x)
    if about:
        out.append("Tank: " + about + ".")
    for j in tank.get("jobs", []):
        last = f"last {ago(j['last'], now_ms)}" if j.get("last") else "not logged yet"
        due = j["due"] - now_ms
        when = f"due in {due / 86_400_000:.1f} days" if due > 0 else f"overdue by {-due / 86_400_000:.1f} days"
        out.append(f"{j['name']}: {last}, every {j['every']} days, {when}.")
    why = "; ".join(tank.get("why", []))
    # "low" alone was read as bad water by two of three models: say what it is the risk of, and what it means.
    risk = tank.get("risk", "?")
    meaning = {"low": "that is good", "building": "a water change may be due soon", "high": "act soon"}.get(risk, "")
    out.append(f"Risk of the water going bad, guessed from care habits only (no tests): {risk}"
               + (f", {meaning}" if meaning else "") + (f" ({why})." if why else "."))
    if tank.get("lightOn"):
        out.append(f"Light: usually on at {tank['lightOn']}" + (f" for {tank['lightHours']:.1f} hours." if tank.get("lightHours") else "."))
    out.append("Rear camera watching now: " + ("yes, the light is on." if tank.get("watching") else "no, the light looks off."))
    if tank.get("described"):
        out.append("A small vision AI's description of a recent photo (can be wrong): " + tank["described"][18:])
    return out


def days_lines(rows, keep=7, today=None):
    """The last days from days.csv, compact. [today]'s row is only so far: the day is not over."""
    out = []
    for row in rows[-keep:]:
        p = row.split(",")
        if len(p) != 8 or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", p[0]):
            continue
        so_far = " so far today" if p[0] == today else ""
        out.append(f"{p[0]}: lit {int(p[1]) / 60:.1f} h{so_far}, activity {p[2]}, up to {p[3]} moving at once, "
                   f"plants {float(p[4]) * 100:.0f}% of picture, green {p[5]}, clarity {p[6]}, fed {p[7]}x")
    return out


def phone_base():
    try:
        return open(NODE_CONFIG).read().strip()
    except OSError:
        return None


_tank_cache = {"at": 0, "tank": None}


def fetch_tank():
    """The phone's live tank, cached a minute; None if it does not answer."""
    if time.time() - _tank_cache["at"] < 60:
        return _tank_cache["tank"]
    tank = None
    base = phone_base()
    if base:
        try:
            with urllib.request.urlopen(base + "tank", timeout=8) as r:
                tank = json.load(r).get("tank")
        except Exception:
            tank = None
    _tank_cache.update(at=time.time(), tank=tank)
    return tank


def read_days():
    try:
        return open(DAYS).read().splitlines()
    except OSError:
        return []


def knowledge(now_ms):
    sheet = read_text(SHEET).strip() or "(The owner has not written a care sheet yet.)"
    facts = [f["text"] for f in load_memory()]
    past = [f"{c['at'][:10]}: {c['title']}" for c in list_chats()[:5]]
    parts = [
        "CARE SHEET (written by the owner; it is right)\n" + sheet,
        "REMEMBERED FROM EARLIER CHATS (the owner said these)\n" + ("\n".join("- " + f for f in facts) or "(nothing yet)"),
        "THE TANK NOW (from the phone's camera and care log)\n" + "\n".join(tank_lines(fetch_tank(), now_ms)),
        "LAST DAYS (camera measures for spotting change from day to day, not judgements: plants is only how much of "
        "the picture is plant)\n"
        + ("\n".join(days_lines(read_days(), today=datetime.now().strftime("%Y-%m-%d"))) or "(no days yet)"),
        "PAST CHATS\n" + ("\n".join(past) or "(none)"),
    ]
    return "\n\n".join(parts)


SYSTEM = """You are an aquarium care helper for the owner's own tank, in a chat. Below is what is known about it.
Answer from it when it covers the question, and say so. When it does not, say plainly that the data does not
show it, and give general fishkeeping advice only as general advice. Never invent a measurement: there is no
test kit, so ammonia, nitrite, nitrate, pH and temperature are unknown unless the owner says them. Keep answers
short and practical.

{knowledge}
{results}
Now: {now}"""

GENERAL = """You are a helpful assistant in a chat, running privately on the owner's own computer. Answer clearly
and to the point; use a short list when it helps. When you are not sure, say so. Never make up facts, names,
numbers, quotes or links.

WHAT YOU KNOW ABOUT THE OWNER (from earlier chats)
{facts}
{results}
Now: {now}"""

RESULTS = """
WEB SEARCH RESULTS for "{query}" (searched just now)
{lines}
Answer from these results when they cover the question, citing them like [1] or [2]. If they do not answer it,
say that the search did not find it, rather than guessing.
"""


def results_block(query, results):
    """Search results as the model reads them; empty when there are none."""
    if not results:
        return ""
    lines = "\n".join(f"[{i + 1}] {r['title']}: {r['content']} ({r['host']})" for i, r in enumerate(results))
    return RESULTS.format(query=query, lines=lines)


NEWS_WORDS = re.compile(r"\b(news|latest|headlines?|happening)\b", re.I)


def search_params(query):
    """SearXNG's query for [query]: its news search for news, which finds articles rather than news websites."""
    params = {"q": query, "format": "json", "safesearch": 1}
    if NEWS_WORDS.search(query):
        params["categories"] = "news"
    return params


def search(query):
    """The top results for [query] from SearXNG on this server: title, url, host and snippet; [] if it fails."""
    url = SEARXNG + "?" + urllib.parse.urlencode(search_params(query))
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            found = json.load(r).get("results", [])
    except Exception:
        return []
    return pick_results(found)


def pick_results(found):
    """The first [SEARCH_RESULTS] usable results: an http(s) link and a title; snippets cut short."""
    out = []
    for f in found:
        link = f.get("url", "")
        if not link.startswith(("http://", "https://")) or not f.get("title"):
            continue
        out.append({"title": f["title"][:120], "url": link, "host": urllib.parse.urlparse(link).netloc,
                    "content": re.sub(r"\s+", " ", f.get("content") or "")[:300]})
        if len(out) == SEARCH_RESULTS:
            break
    return out

# -- tiers ---------------------------------------------------------------------------------------------------------

DEEP_WORDS = re.compile(r"\b(why|how (do|can|should) i|what should|fix|explain|compare|plan|melt\w*|dying|died|dead|sick|"
                        r"disease|cloud\w*|algae|spots?|gasping|cycle|ammonia|nitrite|nitrate|treat\w*|stock\w*|"
                        r"add (a |more |some )?\w+|recommend\w*|advice|write|draft|essay|code|program\w*|script|"
                        r"analy\w*|difference between|pros and cons|step by step|solve|calculat\w*|prove)\b", re.I)
# What makes a question the aquarium's, so it gets the tank's data.
AQUA_WORDS = re.compile(r"\b(tank|aquarium|fish\w*|shrimp|snails?|plants?|algae|water change|filter|substrate|gravel|"
                        r"sand|hornwort|hairgrass|loach\w*|tetras?|guppy|guppies|betta|feed\w*|fed|fertili[sz]\w*|co2|"
                        r"ammonia|nitrite|nitrate|water quality|cloudy|heater|aquascap\w*)\b", re.I)
# What wants the web: current things, prices, who/when/where, or asking for a search.
SEARCH_WORDS = re.compile(r"\b(latest|news|today|this (week|month|year)|current(ly)?|right now|price|costs?|weather|"
                          r"forecast|score|who won|who is|who was|when is|when was|where is|released?|update|"
                          r"20[2-3]\d|search|look (it )?up|google|online|website|near me)\b", re.I)
LOOK_WORDS = re.compile(r"\b(look|see|photo|picture|camera|show me|right now in|is there (any )?algae)\b", re.I)
JOB_WORDS = {
    "FEED": re.compile(r"\b(fed|feed\w*)\b", re.I),
    "WATER_CHANGE": re.compile(r"\b(water change|change(d)? the water)\b", re.I),
    "FILTER": re.compile(r"\bfilter\b", re.I),
    "TRIM": re.compile(r"\b(trim\w*|prun\w*)\b", re.I),
    "FERTILIZE": re.compile(r"\bfertili[sz]\w*\b", re.I),
}
WHEN = re.compile(r"\b(when did i( last)?|when was|last time|how long (ago|since))\b", re.I)
LIGHT_HOURS = re.compile(r"\bhow long\b.*\blight\b|\blight\b.*\bhow long\b", re.I)


def route(text, aqua_before=False):
    """
    How to answer [text]: (tier, aquarium, search). Tier is the cheapest that can: 0 data, 1 quick, 2 deep,
    3 look. Aquarium when it is about the tank, or a short follow-up to an answer that was. Search when it wants
    current facts. Data and looks are the aquarium's only.
    """
    aqua = bool(AQUA_WORDS.search(text) or LIGHT_HOURS.search(text) or any(p.search(text) for p in JOB_WORDS.values())) \
        or (aqua_before and len(text.split()) <= 12)
    if aqua and WHEN.search(text) and any(p.search(text) for p in JOB_WORDS.values()):
        return 0, True, False
    if aqua and LIGHT_HOURS.search(text) and not DEEP_WORDS.search(text):
        return 0, True, False
    if aqua and LOOK_WORDS.search(text):
        return 3, True, False
    tier = 2 if DEEP_WORDS.search(text) or len(text.split()) > 25 else 1
    return tier, aqua, bool(SEARCH_WORDS.search(text))


def data_answer(text, tank, days, now_ms):
    """Tier 0: an answer read straight from the data, or None to let a model answer."""
    if LIGHT_HOURS.search(text):
        today = datetime.fromtimestamp(now_ms / 1000).strftime("%Y-%m-%d")
        row = next((r for r in reversed(days) if r.startswith(today)), None)
        seen = f"The camera has seen the light on for about {int(row.split(',')[1]) / 60:.1f} hours today." if row else \
            "The camera has not seen the light on today yet."
        usual = f" It is usually on at {tank['lightOn']} for {tank['lightHours']:.1f} hours." if tank and tank.get("lightHours") else ""
        return seen + usual
    if not tank:
        return None
    for job, words in JOB_WORDS.items():
        if words.search(text):
            j = next((x for x in tank.get("jobs", []) if x["job"] == job), None)
            if not j:
                return None
            if not j.get("last"):
                return f"No {j['name'].lower()} is logged yet. Tell NODE when you do it, or press Done on its Aquarium tab."
            due = j["due"] - now_ms
            when = f"Next due in {due / 86_400_000:.1f} days." if due > 0 else f"It is overdue by {-due / 86_400_000:.1f} days."
            at = datetime.fromtimestamp(j["last"] / 1000).strftime("%a %d %b, %H:%M")
            return f"{j['name']}: last done {ago(j['last'], now_ms)} ({at}), every {j['every']} days. {when}"
    return None


# -- models --------------------------------------------------------------------------------------------------------


def ollama_stream(model, messages, keep_alive, num_predict=400, images=None):
    """Yields the reply's text as it is written."""
    if images:
        messages = messages[:-1] + [dict(messages[-1], images=images)]
    body = json.dumps({"model": model, "messages": messages, "stream": True, "keep_alive": keep_alive,
                       "options": {"temperature": 0.3, "num_predict": num_predict, "num_ctx": 4096}}).encode()
    req = urllib.request.Request(OLLAMA, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        for line in r:
            if not line.strip():
                continue
            part = json.loads(line)
            piece = part.get("message", {}).get("content", "")
            if piece:
                yield piece
            if part.get("done"):
                return


def ollama_once(model, messages, keep_alive, num_predict=200):
    return "".join(ollama_stream(model, messages, keep_alive, num_predict))


FACTS_PROMPT = """From the owner's message below, list only lasting facts about the owner worth remembering for
future chats: their aquarium (species and counts, tank size, equipment and its settings, routines, products,
problems), and about themselves (where they live, their work, family, devices, preferences). Not questions, not
passing feelings, not anything uncertain. Reply with a JSON array of short sentences in the third person
("The owner has 6 neon tetras."), or [] if there are none.

Message: {text}"""


def extract_facts(text):
    """Lasting facts from one message of the owner's, by the quick model; [] on any doubt."""
    try:
        raw = ollama_once(QUICK, [{"role": "user", "content": FACTS_PROMPT.format(text=text)}], -1, 160)
        m = re.search(r"\[.*\]", raw, re.S)
        facts = json.loads(m.group(0)) if m else []
        return [f.strip()[:200] for f in facts if isinstance(f, str) and len(f.strip()) > 8][:5]
    except Exception:
        return []


def plain(s):
    return re.sub(r"[^a-z0-9 ]", "", s.lower()).strip()


def new_facts(found, known):
    """Facts not already remembered, ignoring case and punctuation."""
    seen = {plain(k) for k in known}
    out = []
    for f in found:
        if plain(f) not in seen:
            seen.add(plain(f))
            out.append(f)
    return out


# -- storage -------------------------------------------------------------------------------------------------------


def read_text(path):
    try:
        return open(path, encoding="utf-8").read()
    except OSError:
        return ""


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def load_memory():
    try:
        return json.load(open(MEMORY, encoding="utf-8"))
    except (OSError, ValueError):
        return []


def remember(texts, source):
    with LOCK:
        mem = load_memory()
        added = []
        for t in new_facts(texts, [m["text"] for m in mem]):
            item = {"id": uuid.uuid4().hex[:12], "text": t, "source": source, "at": datetime.now().isoformat(timespec="minutes")}
            mem.append(item)
            added.append(item)
        write_json(MEMORY, mem)
        return added


def forget(fact_id):
    with LOCK:
        write_json(MEMORY, [m for m in load_memory() if m["id"] != fact_id])


def edit_fact(fact_id, text):
    with LOCK:
        mem = load_memory()
        for m in mem:
            if m["id"] == fact_id:
                m["text"] = text[:200]
        write_json(MEMORY, mem)


def chat_path(chat_id):
    if not CHAT_ID.fullmatch(chat_id):
        raise ValueError("bad chat id")
    return os.path.join(CHATS, chat_id + ".json")


def load_chat(chat_id):
    try:
        return json.load(open(chat_path(chat_id), encoding="utf-8"))
    except (OSError, ValueError):
        return None


def list_chats():
    chats = []
    for name in os.listdir(CHATS) if os.path.isdir(CHATS) else []:
        if name.endswith(".json") and CHAT_ID.fullmatch(name[:-5]):
            c = load_chat(name[:-5])
            if c:
                chats.append({"id": c["id"], "title": c["title"], "at": c["at"]})
    return sorted(chats, key=lambda c: c["at"], reverse=True)


# -- answering -----------------------------------------------------------------------------------------------------


def answer(chat_id, text, tier, want_search, send):
    """
    One exchange: route, search if wanted, answer in a stream of events, remember, save. [send] takes
    (event, data). [tier] and [want_search] come from the page's buttons, else from [route].
    """
    now_ms = int(time.time() * 1000)
    chat = (load_chat(chat_id) if chat_id else None) or {
        "id": uuid.uuid4().hex[:12], "title": text[:70], "at": datetime.now().isoformat(timespec="minutes"), "messages": []}
    last = next((m for m in reversed(chat["messages"]) if m["role"] == "assistant"), None)
    auto_tier, aqua, auto_search = route(text, bool(last and last.get("aqua")))
    tier = auto_tier if tier is None else tier
    searching = auto_search if want_search is None else want_search
    if tier in (0, 3):
        aqua, searching = True, False
    send("chat", {"id": chat["id"], "title": chat["title"]})
    reply = None
    if tier == 0:
        reply = data_answer(text, fetch_tank(), read_days(), now_ms)
        if reply is None:
            tier = 1
    results = []
    if searching:
        send("status", "Searching the web…")
        results = search(text)
        send("sources", [{"title": r["title"], "url": r["url"], "host": r["host"]} for r in results])
        if not results:
            send("status", "The web search found nothing, or is not running; answering without it.")
    send("tier", {"tier": tier, "name": TIER_NAMES[tier], "model": {0: None, 1: QUICK, 2: DEEP, 3: VISION}[tier],
                  "aqua": aqua, "search": bool(results)})
    if reply is not None:
        send("token", reply)
    else:
        history = [{"role": m["role"], "content": m["content"]} for m in chat["messages"][-8:]]
        found = results_block(text, results)
        now = datetime.now().strftime("%A %d %B %Y, %H:%M")
        if aqua:
            system = SYSTEM.format(knowledge=knowledge(now_ms), results=found, now=now)
        else:
            facts = "\n".join("- " + f["text"] for f in load_memory()) or "(nothing yet)"
            system = GENERAL.format(facts=facts, results=found, now=now)
        messages = [{"role": "system", "content": system}] + history + [{"role": "user", "content": text}]
        images = None
        model, keep = {1: (QUICK, -1), 2: (DEEP, "5m"), 3: (VISION, "5m")}[tier]
        if tier == 3:
            jpeg = fetch_photo()
            if jpeg is None:
                send("token", "I couldn't get a picture from the phone just now, so I can't look. ")
                model, keep, tier = QUICK, -1, 1
            else:
                import base64
                images = [base64.b64encode(jpeg).decode()]
                messages[-1] = {"role": "user", "content": text + "\n\n(The attached picture is the tank's newest, from "
                                "the phone's rear camera. Say only what you can see in it; small fish and details are easy "
                                "to get wrong, so say when you are unsure.)"}
        parts = []
        try:
            for piece in ollama_stream(model, messages, keep, 500 if tier == 2 else 300, images):
                parts.append(piece)
                send("token", piece)
        except Exception as e:
            send("token", f"\n\n(The model on the server did not answer: {e})")
        reply = "".join(parts).strip()
    stamp = datetime.now().isoformat(timespec="minutes")
    chat["messages"] += [{"role": "user", "content": text, "at": stamp},
                         {"role": "assistant", "content": reply, "at": stamp, "tier": tier, "aqua": aqua,
                          "sources": [{"title": r["title"], "url": r["url"], "host": r["host"]} for r in results]}]
    chat["at"] = stamp
    with LOCK:
        os.makedirs(CHATS, exist_ok=True)
        write_json(chat_path(chat["id"]), chat)
    for fact in remember(extract_facts(text), chat["id"]):
        send("remembered", fact)
    send("done", {})


def fetch_photo():
    base = phone_base()
    if not base:
        return None
    try:
        with urllib.request.urlopen(base + "tank/photo", timeout=10) as r:
            return r.read()
    except Exception:
        return None


# -- the web side --------------------------------------------------------------------------------------------------


def key():
    os.makedirs(HOME, exist_ok=True)
    k = read_text(KEY_FILE).strip()
    if not re.fullmatch(r"[a-z0-9]{10}", k):
        k = "".join(secrets.choice("abcdefghijkmnpqrstuvwxyz23456789") for _ in range(10))
        with open(KEY_FILE, "w") as f:
            f.write(k)
        os.chmod(KEY_FILE, 0o600)
    return k


KEY = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def rest(self):
        prefix = f"/{KEY}/"
        path = self.path.split("?")[0]
        if not secrets.compare_digest(path[:len(prefix)].encode(), prefix.encode()):
            return None
        return path[len(prefix):]

    def reply(self, status, body=b"", kind="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 64 * 1024:
            raise ValueError("too long")
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        rest = self.rest()
        if rest is None:
            return self.reply(404, b"not found\n", "text/plain")
        if rest == "":
            return self.reply(200, open(PAGE, "rb").read(), "text/html; charset=utf-8")
        if rest == "api/chats":
            return self.reply(200, json.dumps(list_chats()).encode())
        if rest.startswith("api/chats/"):
            chat = load_chat(rest[len("api/chats/"):]) if CHAT_ID.fullmatch(rest[len("api/chats/"):]) else None
            return self.reply(200, json.dumps(chat).encode()) if chat else self.reply(404, b"{}")
        if rest == "api/memory":
            return self.reply(200, json.dumps(load_memory()).encode())
        if rest == "api/sheet":
            return self.reply(200, json.dumps({"text": read_text(SHEET)}).encode())
        return self.reply(404, b"not found\n", "text/plain")

    def do_POST(self):
        rest = self.rest()
        if rest is None:
            return self.reply(404, b"not found\n", "text/plain")
        try:
            data = self.body()
        except ValueError:
            return self.reply(400, b"{}")
        if rest == "api/ask":
            text = str(data.get("text", "")).strip()[:MAX_TEXT]
            if not text:
                return self.reply(400, b"{}")
            tier = data.get("tier")
            tier = tier if tier in (0, 1, 2, 3) else None
            want_search = data.get("search")
            want_search = want_search if isinstance(want_search, bool) else None
            chat_id = str(data.get("chat") or "")
            chat_id = chat_id if CHAT_ID.fullmatch(chat_id) else None
            # A stream of server-sent events, one exchange long.
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True

            def send(event, payload):
                self.wfile.write(f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n".encode())
                self.wfile.flush()
            try:
                answer(chat_id, text, tier, want_search, send)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if rest == "api/memory/add":
            text = str(data.get("text", "")).strip()[:200]
            return self.reply(200, json.dumps(remember([text], "you") if text else []).encode())
        if rest == "api/memory/edit":
            edit_fact(str(data.get("id", "")), str(data.get("text", "")).strip())
            return self.reply(200, b"{}")
        if rest == "api/memory/forget":
            forget(str(data.get("id", "")))
            return self.reply(200, b"{}")
        if rest == "api/sheet":
            os.makedirs(HOME, exist_ok=True)
            with open(SHEET, "w", encoding="utf-8") as f:
                f.write(str(data.get("text", ""))[:20000])
            return self.reply(200, b"{}")
        if rest == "api/chats/delete":
            chat_id = str(data.get("id", ""))
            if CHAT_ID.fullmatch(chat_id):
                try:
                    os.remove(chat_path(chat_id))
                except OSError:
                    pass
            return self.reply(200, b"{}")
        return self.reply(404, b"not found\n", "text/plain")


def main():
    global KEY
    KEY = key()
    os.makedirs(CHATS, exist_ok=True)
    # So a restart can stop exactly this process, and nothing else that happens to match its name.
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Chat on http://<this server>:{PORT}/{KEY}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
