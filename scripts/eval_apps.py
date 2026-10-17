"""Does Laya pick the right app if it knows what each app is FOR? (research/12)

    uv run python scripts/eval_apps.py     # needs captures/apps.json + captures/app_purposes.json

The owner's 207 installed apps, three setups, each a laya.predict_tournament "which app?" choice:
  A  names only ("iMobile")                      -- the 6-26% baseline of research/09
  B  name + purpose ("iMobile - ICICI bank account and balance")
  C  rules first: the routed skill keeps only its kinds of app, then B among those
  D  name + the phone's automatic profile, nothing hand-written (D-031; captures/app_profiles_auto.json,
     from the bridge's "profiles" command) -- skipped if that file is missing
Cases include long-tail requests no skill covers ("how many steps did I walk today").
captures/app_purposes.json maps each label to (purpose, kind); it stays git-ignored (the owner's app list).
"""
import json
import statistics
import sys
import time
from pathlib import Path

import laya
from laya import predict_tournament

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_assistant import SKILLS, calib, conf_of, rules_skill  # noqa: E402
from laya_android import MODEL, PIN  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
KW = dict(max_len=3072, head_max_len=1536)
Q = "Which app should be opened to do this request?"

# rule system: which kinds of app can do each skill
SKILL_KINDS = {
    "play_music": {"music"}, "play_video": {"video"}, "spend_query": {"upi", "bank", "card", "tracker"},
    "balance_query": {"upi", "bank"}, "bill_query": {"card", "bank"}, "device_setting": {"settings", "screen"},
    "device_status": {"settings"}, "alarm_timer": {"clock"}, "call": {"calls", "contacts"},
    "message": {"messaging", "email"}, "navigate": {"maps"}, "web_search": {"browser", "search", "weather", "news"},
    "ride": {"ride"}, "shopping": {"shopping", "grocery", "food"}, "photos": {"photos"},
}

# (request, accepted answers: app kinds, or "=Label" for specific apps). Generic public app names.
CASES = [
    ("play jazz music", {"music"}), ("play some arijit singh songs", {"music"}),
    ("put on lofi beats", {"music", "video"}), ("I want to hear some Taylor Swift", {"music"}),
    ("throw on some bollywood hits", {"music"}), ("watch cricket highlights", {"video"}),
    ("show me mr beast videos", {"=YouTube"}), ("watch the boys season 4", {"=Prime Video"}),
    ("what were my expenses last month", {"upi", "bank", "card", "tracker"}),
    ("where did my money go this month", {"upi", "bank", "card", "tracker"}),
    ("show my gpay transactions", {"=GPay"}), ("check my bank balance", {"bank", "upi"}),
    ("what's my sbi account balance", {"=YONO SBI"}), ("how much do I owe on my card", {"card", "bank"}),
    ("turn on wifi", {"settings"}), ("make the screen darker", {"settings", "screen"}),
    ("what's my battery level", {"settings"}), ("set an alarm for 6 am", {"clock"}),
    ("wake me up at 7", {"clock"}), ("call mom", {"calls", "contacts"}), ("ring dad", {"calls", "contacts"}),
    ("message rahul that I'll be late", {"messaging"}), ("let priya know I'm on my way", {"messaging"}),
    ("send an email to my manager", {"email"}), ("navigate to MG road", {"maps"}), ("take me home", {"maps"}),
    ("search for best pizza near me", {"browser", "search", "food"}), ("what's the weather today", {"weather", "browser", "search"}),
    ("book a ride to the airport", {"ride"}), ("I need a cab to work", {"ride"}),
    ("order milk", {"grocery"}), ("get me some bread", {"grocery"}), ("track my amazon order", {"=Amazon"}),
    ("show my photos from yesterday", {"photos"}),
    # long tail: no skill in the catalog covers these
    ("how many steps did I walk today", {"=Fit", "=Health"}), ("how did I sleep last night", {"=Health", "=Wakefit Zense"}),
    ("book movie tickets for tonight", {"=BookMyShow", "=District"}), ("order a pizza", {"=Domino's", "=Swiggy", "=Zomato"}),
    ("recharge my phone", {"=Airtel", "=MyJio", "=Paytm", "=PhonePe", "=GPay"}), ("what's on my calendar today", {"=Calendar"}),
    ("add eggs to my shopping list", {"=Keep Notes", "=Tasks", "=TickTick"}), ("how are my stocks doing", {"=Kite", "=smallcase", "=INDmoney"}),
    ("book a train ticket to mysore", {"=Confirmtkt", "=ixigo", "=MakeMyTrip"}), ("rent an electric bike", {"=Yulu"}),
    ("get someone to clean my house", {"=Urban Company", "=Snabbit"}), ("scan this document", {"=Adobe Scan", "=DocAI"}),
    ("translate thank you to hindi", {"=Translate"}), ("find my earbuds", {"=Find Hub"}),
    ("who is this unknown caller", {"=Truecaller"}), ("how many calories did I eat today", {"=Healthify", "=Yazio"}),
]
LONG_TAIL = 34  # cases from this index on are long tail


def main():
    apps = json.loads((ROOT / "captures" / "apps.json").read_text())
    purpose = json.loads((ROOT / "captures" / "app_purposes.json").read_text())
    labels = list(dict.fromkeys(a["label"] for a in apps))  # unique labels, launcher order
    kind = {l: purpose[l][1] for l in labels}
    text_b = {l: ("%s - %s" % (l, purpose[l][0])) if purpose[l][0] else l for l in labels}
    auto = ROOT / "captures" / "app_profiles_auto.json"
    pkg = {a["label"]: a["package"] for a in reversed(apps)}  # first package per label
    text_d = {l: json.loads(auto.read_text())[pkg[l]] for l in labels} if auto.exists() else None
    setups = "ABCD" if text_d else "ABC"

    def ok(label, accept):
        return ("=" + label) in accept or kind[label] in accept

    print("loading laya-android ...")
    agent = laya.load(MODEL, **PIN)

    def pick(req, pool, render):
        res = predict_tournament(agent, {"goal": req}, {"app": {
            "type": "choice", "instructions": Q, "criteria": {str(i): render(l) for i, l in enumerate(pool)}}}, **KW)
        a = res["answers"]["app"]
        return pool[int(a["choice"])], conf_of(a)

    rows, times = [], []
    for n, (req, accept) in enumerate(CASES):
        t = time.perf_counter()
        skill = rules_skill(req) or agent.system_one({"goal": req}, {"skill": {
            "type": "choice", "criteria": SKILLS, "instructions": "What does the user want Friday to do?"}},
            **KW)["answers"]["skill"]["choice"]
        short = [l for l in labels if kind[l] in SKILL_KINDS.get(skill, set())] or labels
        r = {"req": req, "tail": n >= LONG_TAIL, "skill": skill}
        for name, pool, render in (("A", labels, lambda l: l), ("B", labels, text_b.get), ("C", short, text_b.get),
                                   ("D", labels, text_d and text_d.get))[:len(setups)]:
            r[name], r[name + "_conf"] = pick(req, pool, render) if len(pool) > 1 else (pool[0], 1.0)
            r[name + "_ok"] = ok(r[name], accept)
        times.append(time.perf_counter() - t)
        rows.append(r)
        print("%-38s %-14s " % (req[:38], skill[:14]) + " ".join(
            "%s=%-15s%s" % (k, r[k][:15], "+" if r[k + "_ok"] else "-") for k in setups))
    (ROOT / "captures" / "eval_apps.json").write_text(json.dumps(rows, indent=1))

    print("\n== right app (accuracy) ==")
    for part, rs in (("covered by a skill", [r for r in rows if not r["tail"]]),
                     ("long tail", [r for r in rows if r["tail"]]), ("all", rows)):
        print("  %-19s n=%2d  " % (part, len(rs)) + "   ".join("%s %3.0f%%" % (name, 100 * sum(r[k + "_ok"] for r in rs) / len(rs))
              for k, name in zip(setups, ("A names", "B +purpose", "C rules+purpose", "D auto profile"))))
    print("\n== calibration (coverage / accuracy at confidence >= tau) ==")
    for k in setups[1:]:
        print("  %s  " % k + "  ".join("tau %.1f: %3.0f%% cov %3.0f%% acc" % (t, 100 * c, 100 * a)
                                      for t, c, a in calib([(r[k + "_ok"], r[k + "_conf"]) for r in rows])))
    print("\nper request (route + %d tournaments) on the Mac: median %.1f s" % (len(setups), statistics.median(times)))


if __name__ == "__main__":
    main()
