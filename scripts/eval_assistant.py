"""Can Laya be Friday's "brain" zero-shot? Measured on the owner's real installed apps.

    uv run python scripts/eval_assistant.py     # needs captures/apps.json (the phone's app list)

1. Skill routing: Laya choice over a skill catalog vs a keyword-rules baseline vs the hybrid
   (rules first, Laya when no rule fires). Split into phrasings the rules were written for
   ("seen") and paraphrases they never saw ("para") -- rules always ace their own phrasings.
2. App choice over ALL installed apps via laya.predict_tournament, two phrasings:
     launcher - the target question laya-android was fine-tuned on (app labels as launcher icons)
     app-q    - a plain "which app?" question
3. Extractive slot filling: Laya picks the search term from the request's own word spans
   (choosing, not generating -- the only way a non-generative model can fill a slot).
4. Calibration: accuracy above confidence thresholds (can Friday "ask when unsure"?).

Per-case output includes the owner's app names, so it goes to captures/ (git-ignored); only
aggregates belong in research notes. Expected apps are generic public app names matched against
whatever is installed.
"""
import json
import re
import statistics
import sys
import time
from pathlib import Path

import laya
from laya import predict_tournament

sys.path.insert(0, str(Path(__file__).resolve().parent))  # sibling laya_android.py
from laya_android import MODEL, OP_DESC, PIN, Q_OP, Q_TARGET  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
KW = dict(max_len=3072, head_max_len=1536)

SKILLS = {
    "open_app": "open or launch an app the user names",
    "play_music": "play songs, an artist, an album, a genre or a playlist",
    "play_video": "watch or play a video, show or movie",
    "spend_query": "find out how much money was spent or paid, or see past payments",
    "balance_query": "check a bank account or wallet balance",
    "bill_query": "check a credit card bill or amount due",
    "device_setting": "turn a phone setting on or off or change it (Wi-Fi, Bluetooth, dark mode, brightness)",
    "device_status": "check battery, storage or another phone status",
    "alarm_timer": "set an alarm, timer or reminder",
    "call": "make a phone call to a person or number",
    "message": "send a message, text or email to someone",
    "navigate": "get directions or navigate to a place",
    "web_search": "search the web or look something up",
    "ride": "book a cab, auto or bike ride",
    "shopping": "buy, order or track something from a shop",
    "photos": "show or find photos",
}

# The baseline rule system: first match wins.
RULES = [
    ("open_app", r"^open\b"),
    ("spend_query", r"\b(spen[dt]|expens\w*|transactions?|paid|payments?)\b|how much did i pay"),
    ("balance_query", r"\bbalance\b"),
    ("bill_query", r"\b(bill|dues?)\b"),
    ("alarm_timer", r"\b(alarm|timer|remind\w*)\b"),
    ("device_status", r"\b(battery|storage|space left)\b"),
    ("device_setting", r"\b(turn (on|off)|enable|disable|brightness|dark mode|wi-?fi|bluetooth)\b"),
    ("call", r"^(call|dial|phone)\b"),
    ("message", r"\b(message|text|whatsapp|e-?mail|mail)\b"),
    ("navigate", r"\b(navigate|directions?|get to|route to)\b"),
    ("ride", r"\b(ride|cab|auto|taxi)\b"),
    ("shopping", r"\b(order|buy|deliver\w*)\b"),
    ("photos", r"\b(photos?|pictures?|pics)\b"),
    ("play_video", r"\b(watch|videos?|movie|episode|season)\b"),
    ("play_music", r"\b(play|listen|put on)\b"),
    ("web_search", r"\b(search|look up|google|weather)\b"),
]

CATS = {  # generic, public app names; resolved against what is installed
    "MUSIC": {"Spotify", "YT Music", "JioSaavn", "Gaana", "Wynk Music", "Amazon Music", "Apple Music"},
    "VIDEO": {"YouTube", "Prime Video", "Netflix", "JioHotstar", "Hotstar", "JioCinema", "Airtel Xstream Play"},
    "UPI": {"GPay", "Google Pay", "PhonePe", "Paytm", "BHIM", "Amazon Pay", "CRED", "MobiKwik"},
    "CARDS": {"CRED", "SBI Card", "OneCard", "Amazon Pay"},
    "TRACKERS": {"Wallet", "INDmoney", "Money Manager", "Walnut"},
    "RIDES": {"Namma Yatri", "Uber", "Ola", "Rapido"},
    "QUICK": {"Amazon Now", "Blinkit", "Zepto", "Swiggy", "BigBasket", "JioMart"},
    "SHOP": {"Amazon", "Amazon Now", "Flipkart", "Blinkit", "Zepto", "Swiggy", "BigBasket", "Myntra"},
    "MSG": {"WhatsApp", "Messages", "Telegram", "Signal"},
    "SEARCH": {"Chrome", "Google", "Firefox"},
}
BANK_RE = re.compile(r"\b(bank|hdfc|icici|axis|kotak|idfc|imobile|yono)\b", re.I)

# (request, skill, apps spec, slot, split)
CASES = [
    ("play jazz music", "play_music", ["MUSIC"], "jazz", "seen"),
    ("play some arijit singh songs", "play_music", ["MUSIC"], "arijit singh", "seen"),
    ("put on lofi beats", "play_music", ["MUSIC", "YouTube"], "lofi beats", "seen"),
    ("play the latest coldplay album", "play_music", ["MUSIC"], "coldplay", "seen"),
    ("watch cricket highlights", "play_video", ["VIDEO"], "cricket highlights", "seen"),
    ("show me mr beast videos", "play_video", ["YouTube"], "mr beast", "seen"),
    ("watch the boys season 4", "play_video", ["Prime Video"], "the boys", "seen"),
    ("what were my expenses last month", "spend_query", ["UPI", "BANKS", "TRACKERS"], None, "seen"),
    ("how much did I spend this week", "spend_query", ["UPI", "BANKS", "TRACKERS"], None, "seen"),
    ("how much did I pay to swiggy last month", "spend_query", ["UPI", "BANKS", "TRACKERS"], "swiggy", "seen"),
    ("show my gpay transactions", "spend_query", ["GPay"], None, "seen"),
    ("check my bank balance", "balance_query", ["UPI", "BANKS"], None, "seen"),
    ("what's my sbi account balance", "balance_query", ["YONO SBI"], None, "seen"),
    ("show my credit card bill", "bill_query", ["CARDS", "BANKS"], None, "seen"),
    ("turn on wifi", "device_setting", ["Settings"], None, "seen"),
    ("turn on dark mode", "device_setting", ["Settings"], None, "seen"),
    ("increase the brightness", "device_setting", ["Settings"], None, "seen"),
    ("what's my battery level", "device_status", ["Settings"], None, "seen"),
    ("how much storage is left", "device_status", ["Settings", "Files"], None, "seen"),
    ("set an alarm for 6 am", "alarm_timer", ["Clock"], None, "seen"),
    ("start a 10 minute timer", "alarm_timer", ["Clock"], None, "seen"),
    ("call mom", "call", ["Phone"], "mom", "seen"),
    ("message rahul that I'll be late", "message", ["MSG"], "rahul", "seen"),
    ("send an email to my manager", "message", ["Gmail"], "manager", "seen"),
    ("navigate to MG road", "navigate", ["Maps"], "MG road", "seen"),
    ("how do I get to the airport", "navigate", ["Maps"], "airport", "seen"),
    ("search for best pizza near me", "web_search", ["SEARCH"], "best pizza near me", "seen"),
    ("what's the weather today", "web_search", ["SEARCH"], "weather", "seen"),
    ("book a ride to the airport", "ride", ["RIDES"], "airport", "seen"),
    ("order milk", "shopping", ["QUICK"], "milk", "seen"),
    ("track my amazon order", "shopping", ["Amazon"], None, "seen"),
    ("show my photos from yesterday", "photos", ["Photos"], None, "seen"),
    ("open spotify", "open_app", ["Spotify"], None, "seen"),
    ("open settings", "open_app", ["Settings"], None, "seen"),
    # paraphrases the rules were not written for
    ("I want to hear some Taylor Swift", "play_music", ["MUSIC"], "Taylor Swift", "para"),
    ("throw on some bollywood hits", "play_music", ["MUSIC"], "bollywood hits", "para"),
    ("put on the office", "play_video", ["VIDEO"], "the office", "para"),
    ("how much money went out in september", "spend_query", ["UPI", "BANKS", "TRACKERS"], None, "para"),
    ("where did my money go this month", "spend_query", ["UPI", "BANKS", "TRACKERS"], None, "para"),
    ("did my salary come in", "balance_query", ["UPI", "BANKS"], None, "para"),
    ("how much do I owe on my card", "bill_query", ["CARDS", "BANKS"], None, "para"),
    ("make the screen darker", "device_setting", ["Settings"], None, "para"),
    ("is my phone charged", "device_status", ["Settings"], None, "para"),
    ("wake me up at 7", "alarm_timer", ["Clock"], None, "para"),
    ("ring dad", "call", ["Phone"], "dad", "para"),
    ("let priya know I'm on my way", "message", ["MSG"], "priya", "para"),
    ("take me home", "navigate", ["Maps"], "home", "para"),
    ("I need a cab to work", "ride", ["RIDES"], "work", "para"),
    ("get me some bread", "shopping", ["QUICK"], "bread", "para"),
    ("what's happening in the news today", "web_search", ["SEARCH"], "news", "para"),
]

STOP = set("""a an the my me some to for of on in at from with and that this these i i'm i'll im be is are was
do did does how much many what what's whats where when who show play watch put open start set call ring
message text send email navigate take get book order buy track search look up find turn off increase decrease
make let know need want hear throw latest season songs song music videos video album last week month today
yesterday tonight now near best way going happening please""".split())


def rules_skill(req):
    for skill, pat in RULES:
        if re.search(pat, req, re.I):
            return skill
    return None


def expected_apps(spec, labels):
    want = set()
    for tok in spec:
        if tok == "BANKS":
            want |= {l for l in labels if BANK_RE.search(l)}
        elif tok in CATS:
            want |= {l for l in labels if l in CATS[tok]}
        elif tok in labels:
            want.add(tok)
    return want


def spans(req, trim=False):
    """Candidate slot spans (1-4 words). trim=True: a span must start AND end on a content word,
    so 'play jazz music' is never offered -- the rule shapes the options, Laya only picks."""
    toks = re.findall(r"[\w']+", req)
    out = []
    for n in range(1, 5):
        for i in range(len(toks) - n + 1):
            s = toks[i:i + n]
            if trim:
                if s[0].lower() in STOP or s[-1].lower() in STOP:
                    continue
            elif all(w.lower() in STOP for w in s):
                continue
            out.append(" ".join(s))
    return list(dict.fromkeys(out))


def conf_of(a):
    return a.get("answer_confidence", max(a["probabilities"].values()))


def calib(pairs):
    out = []
    for tau in (0.0, 0.3, 0.5, 0.7, 0.9):
        kept = [ok for ok, p in pairs if p >= tau]
        out.append((tau, len(kept) / len(pairs), sum(kept) / len(kept) if kept else float("nan")))
    return out


def main():
    apps = json.loads((ROOT / "captures" / "apps.json").read_text())
    labels = [a["label"] for a in apps]
    print("loading laya-android ...")
    agent = laya.load(MODEL, **PIN)
    rows, times = [], {"skill": [], "launcher": [], "app-q": [], "slot": []}

    for req, skill, spec, slot, split in CASES:
        r = {"req": req, "split": split, "skill": skill}
        # 1. skill routing
        t = time.perf_counter()
        a = agent.system_one({"goal": req}, {"skill": {"type": "choice", "criteria": SKILLS,
                             "instructions": "What does the user want Friday to do?"}}, **KW)["answers"]["skill"]
        times["skill"].append(time.perf_counter() - t)
        r["laya_skill"], r["laya_skill_conf"] = a["choice"], conf_of(a)
        r["rules_skill"] = rules_skill(req)
        r["hybrid_skill"] = r["rules_skill"] or r["laya_skill"]

        # 2. app choice over every installed app (tournament)
        want = expected_apps(spec, labels)
        r["want"] = sorted(want)
        for name, state, qid, qs in (
            ("launcher", {"goal": req, "history": [], "ui": []}, "target",
             {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)},
              "target": {"type": "choice", "instructions": Q_TARGET,
                         "criteria": {str(i): "%s (TextView)" % l for i, l in enumerate(labels)}}}),
            ("app-q", {"goal": req}, "app",
             {"app": {"type": "choice", "instructions": "Which app should be opened to do this request?",
                      "criteria": {str(i): l for i, l in enumerate(labels)}}}),
        ):
            t = time.perf_counter()
            res = predict_tournament(agent, state, qs, **KW)
            times[name].append(time.perf_counter() - t)
            a = res["answers"][qid]
            r[name] = labels[int(a["choice"])]
            r[name + "_conf"] = conf_of(a)
            if name == "launcher":
                r["launcher_op"] = res["answers"]["operation"]["choice"]

        # 3. extractive slot
        if slot:
            r["slot_want"] = slot
            for key, trim in (("slot", False), ("slot_trim", True)):
                sp = spans(req, trim)
                t = time.perf_counter()
                res = predict_tournament(agent, {"goal": req}, {"slot": {
                    "type": "choice", "criteria": {str(i): s for i, s in enumerate(sp)},
                    "instructions": "Which words in the request name the song, artist, show, person, place or item?"}},
                    **KW)
                times["slot"].append(time.perf_counter() - t)
                a = res["answers"]["slot"]
                r[key], r[key + "_conf"] = sp[int(a["choice"])], conf_of(a)
        rows.append(r)
        print("%-42s skill rules=%-14s laya=%-14s(%.2f) | launcher=%-16s(%.2f) app-q=%-16s(%.2f)%s"
              % (req[:42], r["rules_skill"], r["laya_skill"], r["laya_skill_conf"], r["launcher"][:16],
                 r["launcher_conf"], r["app-q"][:16], r["app-q_conf"],
                 " | slot=%r trim=%r" % (r["slot"], r["slot_trim"]) if slot else ""))

    (ROOT / "captures" / "eval_assistant.json").write_text(json.dumps(rows, indent=1))

    def acc(rs, key):
        return sum(r[key] == r["skill"] for r in rs) / len(rs)

    print("\n== skill routing (accuracy) ==")
    for split in ("seen", "para"):
        rs = [r for r in rows if r["split"] == split]
        print("  %-5s n=%2d  rules %.0f%%  laya %.0f%%  hybrid %.0f%%" % (
            split, len(rs), 100 * acc(rs, "rules_skill"), 100 * acc(rs, "laya_skill"), 100 * acc(rs, "hybrid_skill")))

    print("\n== app choice over all %d installed apps (accuracy, cases with an installed answer) ==" % len(labels))
    for name in ("launcher", "app-q"):
        for split in ("seen", "para"):
            rs = [r for r in rows if r["split"] == split and r["want"]]
            ok = sum(r[name] in r["want"] for r in rs)
            print("  %-8s %-5s %d/%d = %.0f%%" % (name, split, ok, len(rs), 100 * ok / len(rs)))
    ops = [r["launcher_op"] for r in rows]
    print("  launcher first op: OPEN_APP %d, CLICK %d, other %d" % (
        ops.count("OPEN_APP"), ops.count("CLICK"), len(ops) - ops.count("OPEN_APP") - ops.count("CLICK")))

    sl = [r for r in rows if "slot" in r]
    print("\n== extractive slot (n=%d) ==" % len(sl))
    for key, name in (("slot", "any span       "), ("slot_trim", "content-trimmed")):
        exact = sum(r[key].lower() == r["slot_want"].lower() for r in sl)
        overlap = sum(r[key].lower() in r["slot_want"].lower() or r["slot_want"].lower() in r[key].lower()
                      for r in sl)
        print("  %s  exact %.0f%%  overlap %.0f%%" % (name, 100 * exact / len(sl), 100 * overlap / len(sl)))

    print("\n== calibration: coverage / accuracy at confidence >= tau ==")
    for name, pairs in (
        ("skill", [(r["laya_skill"] == r["skill"], r["laya_skill_conf"]) for r in rows]),
        ("launcher", [(r["launcher"] in r["want"], r["launcher_conf"]) for r in rows if r["want"]]),
        ("app-q", [(r["app-q"] in r["want"], r["app-q_conf"]) for r in rows if r["want"]]),
    ):
        print("  %-8s " % name + "  ".join("tau %.1f: %3.0f%% cov, %3.0f%% acc" % (t, 100 * c, 100 * a)
                                         for t, c, a in calib(pairs)))

    print("\n== latency on the Mac (MPS), median ==  " + "  ".join(
        "%s %.0f ms" % (k, 1000 * statistics.median(v)) for k, v in times.items() if v))


if __name__ == "__main__":
    main()
