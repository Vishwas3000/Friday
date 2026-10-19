"""Laya-Android input format + one decision step, shared by the scripts here.

Candidate rules, labels and prompts are adapted from github.com/Byun11/laya-android (Apache-2.0):
src/accessibility.py, src/candidates.py, src/serialization.py. The model only works if its input matches them.
Run this file directly for a self-check.
"""
import re
import xml.etree.ElementTree as ET

MODEL = "ByunByun/laya-android"
# pinned + verified so a changed upload can't silently change behaviour (HF revision of 2026-10-07)
PIN = {"revision": "5a03b2bc4220ec786a00a87eacdaa75cb844bd6b",
       "expected_sha256": {"model.safetensors": "4ffca5d9a75f4888dc029b1de1dc2d81b77944b69f6b22c93db5285a66262993"}}
OP_DESC = {
    "CLICK": "tap a UI element",
    "LONG_PRESS": "long-press a UI element",
    "SCROLL_UP": "scroll up",
    "SCROLL_DOWN": "scroll down",
    "SCROLL_LEFT": "scroll left",
    "SCROLL_RIGHT": "scroll right",
    "OPEN_APP": "launch an app by name",
    "INPUT_TEXT": "type text into the focused field",
    "BACK": "press the system back button",
    "HOME": "go to the home screen",
    "WAIT": "wait for the screen to update",
    "DONE": "the goal is complete; stop",
}
Q_OP = "Which operation should be performed next?"
Q_TARGET = "Which UI element should be targeted?"
FLAGS = ("clickable", "long_clickable", "editable", "scrollable", "checkable")


def _own_text(n):
    return (n["text"] or n["content_desc"] or n["hint"]).strip()


def _label(n, limit=60):
    """Own text, else descendants' texts in DFS order, capped (a clickable row's text lives in its children)."""
    t = _own_text(n)
    if t:
        return " ".join(t.split())[:limit]
    parts, stack = [], list(reversed(n["children"]))
    while stack and sum(len(p) + 1 for p in parts) < limit:
        c = stack.pop()
        if _own_text(c):
            parts.append(" ".join(_own_text(c).split()))
        stack.extend(reversed(c["children"]))
    return " ".join(parts)[:limit]


def parse_uiautomator(xml_text, ime_package=""):
    """`uiautomator dump` XML -> flat node list (the dump already omits invisible nodes)."""
    root = ET.fromstring(xml_text[: xml_text.rindex("</hierarchy>") + len("</hierarchy>")])
    nodes = []

    def walk(el, depth):
        a = el.attrib
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", a.get("bounds", "[0,0][0,0]")))
        cls = a.get("class", "")
        n = {"text": a.get("text", ""), "content_desc": a.get("content-desc", ""), "hint": a.get("hint", ""),
             "class": cls.rsplit(".", 1)[-1], "resource_id": a.get("resource-id", "").rsplit("/", 1)[-1],
             "clickable": a.get("clickable") == "true", "long_clickable": a.get("long-clickable") == "true",
             "scrollable": a.get("scrollable") == "true", "checkable": a.get("checkable") == "true",
             # shortcut: uiautomator has no is-editable attribute, so infer it from the class
             "editable": "EditText" in cls or "AutoCompleteTextView" in cls,
             "checked": a.get("checked") == "true", "selected": a.get("selected") == "true",
             "enabled": a.get("enabled") == "true", "bounds": (x1, y1, x2, y2), "depth": depth, "children": [],
             "visible": True, "ime": a.get("package", "") == ime_package}
        nodes.append(n)
        for child in el.findall("node"):
            n["children"].append(walk(child, depth + 1))
        return n

    for top in root.findall("node"):
        walk(top, 0)
    return nodes


IME_WINDOW = 2  # AccessibilityWindowInfo.TYPE_INPUT_METHOD
SYSTEM_WINDOW = 3  # TYPE_SYSTEM: status bar, nav bar, shade -- never a target (Candidates.kt)


def parse_forest(state):
    """The phone app's `state` reply (every window's tree) -> the same flat node list as parse_uiautomator."""
    nodes = []

    def walk(j, depth, ime, sys=False):
        n = {k: j[k] for k in ("text", "content_desc", "hint", "clickable", "long_clickable", "scrollable",
                               "checkable", "editable", "checked", "selected", "enabled", "visible")}
        n.update({"class": j["class"].rsplit(".", 1)[-1], "resource_id": j["resource_id"].rsplit("/", 1)[-1],
                  "bounds": tuple(j["bounds"]), "depth": depth, "ime": ime, "sys": sys, "children": []})
        nodes.append(n)
        n["children"] = [walk(c, depth + 1, ime, sys) for c in j["children"]]
        return n

    for w in state["windows"]:
        walk(w["root"], 0, w["type"] == IME_WINDOW, w["type"] == SYSTEM_WINDOW)
    return nodes


def build_candidates(nodes, screen, system=True, ids=True, loose_text=True):
    """Visible, actionable, non-IME nodes, deduplicated on (box, label) keeping the deepest, in screen order.
    system / ids / loose_text turn off Friday's changes to the trained format, one at a time (research/27); the phone
    always has all three on."""
    w, h = screen
    groups = {}
    for n in nodes:
        if n["ime"] or (system and n.get("sys")) or not n["visible"] or not any(n[f] for f in FLAGS):
            continue
        x1, y1, x2, y2 = n["bounds"]
        box = (max(0, x1), max(0, y1), min(w, x2), min(h, y2))
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        lab = _label(n)
        c = groups.get((box, lab))
        if c is None or n["depth"] > c["depth"]:
            merged = {f: n[f] or (c[f] if c else False) for f in FLAGS}
            groups[(box, lab)] = {"label": lab, "class": n["class"], "resource_id": n["resource_id"],
                                  "enabled": n["enabled"], "checked": n["checked"], "selected": n["selected"],
                                  "bounds": box, "depth": n["depth"], **merged}
        else:
            for f in FLAGS:
                c[f] = c[f] or n[f]
    # Text the app draws but doesn't mark tappable (Compose / React Native): when it is at least twice the tappable ones,
    # the first 30 such texts (3+ letters, outside every tappable box) join, tapped by finger. Must match Candidates.kt.
    boxes = [c["bounds"] for c in groups.values()]
    loose = {}
    for n in nodes:
        if n["ime"] or (system and n.get("sys")) or not n["visible"] or any(n[f] for f in FLAGS) or not _own_text(n):
            continue
        x1, y1, x2, y2 = n["bounds"]
        box = (max(0, x1), max(0, y1), min(w, x2), min(h, y2))
        if box[2] <= box[0] or box[3] <= box[1] or any(
                o[0] <= box[0] and o[1] <= box[1] and o[2] >= box[2] and o[3] >= box[3] for o in boxes):
            continue
        lab = _label(n)
        if sum(ch.isalpha() for ch in lab) < 3:
            continue
        c = loose.get((box, lab))
        if c is None or n["depth"] > c["depth"]:
            loose[(box, lab)] = {"label": lab, "class": n["class"], "resource_id": n["resource_id"], "enabled": n["enabled"],
                                 "checked": n["checked"], "selected": n["selected"], "bounds": box, "depth": n["depth"],
                                 **{f: False for f in FLAGS}}
    if loose_text and len(loose) >= 2 * len(groups):
        for k, c in sorted(loose.items(), key=lambda kv: (kv[1]["bounds"][1], kv[1]["bounds"][0]))[:30]:
            groups[k] = c
    b = lambda c: c["bounds"]  # noqa: E731
    out = sorted(groups.values(), key=lambda c: (b(c)[1], b(c)[0], b(c)[3], b(c)[2], c["label"], c["class"]))
    for c in out:  # after sorting, as on the phone: the order and the ids don't move
        c["label"] = c["label"] or (_id_label(c["resource_id"]) if ids else "")
    return out


_ID_NOISE = {"id", "btn", "button", "iv", "img", "image", "imageview", "icon", "ic", "view", "layout", "container",
             "wrapper", "root", "item", "cell", "holder", "frame", "txt", "text", "tv", "lbl", "label", "action", "ll",
             "rl", "cl", "fl", "vg", "group", "content", "wrap", "parent", "child", "widget", "bg", "background", "main",
             "compound", "webview", "element", "tag"}


def _id_label(rid):
    """Words from a resource id for an element with no text (research/24 §4). Must match Candidates.kt idLabel."""
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", rid.rsplit("/", 1)[-1]).lower()
    return " ".join(w for w in re.split(r"[^a-z]+", s) if len(w) > 1 and w not in _ID_NOISE)


assert (_id_label("com.x:id/tab_menu_stopwatch"), _id_label("searchButton"), _id_label("iv_icon"),
        _id_label("action_orders")) == ("tab menu stopwatch", "search", "", "orders")


def option_label(c):
    flags = [f for f, on in (("edit", c["editable"]), ("long", c["long_clickable"] and not c["clickable"]),
                             ("scroll", c["scrollable"] and not c["clickable"]), ("on", c["checked"]),
                             ("selected", c["selected"]), ("disabled", not c["enabled"])) if on]
    return " ".join([c["label"][:40] or "-", "(%s)" % c["class"], *flags])


# Routes.kt's word helpers and Candidates.kt's shortlist (D-049), line for line (research/27 L3)
_STOP = {"a", "an", "the", "app", "tab", "please", "my", "me", "to", "and", "then", "for", "in", "at", "of", "with",
         "from", "can", "could", "would", "you", "i", "want", "just", "now", "hey", "friday"}
_NAV = {"open", "go", "show", "display", "check", "see", "view", "find", "look", "get", "take", "bring", "switch",
        "visit", "launch", "navigate", "tap", "click", "page", "screen", "section"}


def content_words(s):
    """Routes.contentWords: lower-case words ([^\\p{L}\\p{N}] splits), plurals folded, filler dropped."""
    ws = [w for w in re.split(r"[\W_]+", s.lower()) if w]
    return {w[:-1] if len(w) > 3 and w.endswith("s") else w for w in ws} - _STOP


def about_words(goal, app_label=None):
    """Routes.aboutWords: what a goal is about, without the app's name or navigation verbs."""
    return content_words(goal) - content_words(app_label or "") - _NAV


def shortlist(cands, want, k):
    """Candidates.kt shortlist: the k elements the target question shows (indices, screen order)."""
    if k <= 0 or len(cands) <= k:
        return list(range(len(cands)))
    hits = [len((content_words(c["label"]) | content_words(c["resource_id"])) & want) for c in cands]
    keep = dict.fromkeys(sorted((i for i in range(len(cands)) if hits[i] > 0), key=lambda i: -hits[i])[:k])
    for i in range(len(cands)):
        if cands[i]["editable"] and len(keep) < k:
            keep[i] = None
    top, bottom = 0, len(cands) - 1
    while len(keep) < k:
        keep[top] = None
        top += 1
        if len(keep) < k:
            keep[bottom] = None
            bottom -= 1
    return sorted(keep)


def _sl(label, rid="", edit=False):
    return {"label": label, "resource_id": rid, "editable": edit}


# shortlistSelfCheck(): the orders tab (by its resource id), the text field, then the top and bottom of the screen
_c = [_sl("Back"), _sl("Search", edit=True)] + [_sl("Item %d" % i) for i in range(1, 21)] + [_sl("", "action_orders"),
                                                                                            _sl("Home")]
_want = about_words("open my orders on meesho", "Meesho")
assert shortlist(_c, _want, 6) == [0, 1, 2, 21, 22, 23], shortlist(_c, _want, 6)
assert shortlist(_c[:5], _want, 6) == [0, 1, 2, 3, 4] and shortlist(_c, _want, 0) == list(range(len(_c)))


def history_entry(op, cand=None, payload=None):
    if op in ("CLICK", "LONG_PRESS"):
        return "%s %s" % (op, (cand["label"] or cand["class"]) if cand else "(unlabeled)")
    if op == "INPUT_TEXT":
        return 'INPUT_TEXT "%s"' % (payload or "")
    if op == "OPEN_APP":
        return "OPEN_APP %s" % (payload or "")
    return op


def decide(agent, goal, history, cands):
    """One forward pass -> (answers, usage). answers[q] has 'choice' and 'probabilities'."""
    labels = [option_label(c) for c in cands]
    state = {"goal": goal, "history": history[-3:], "ui": ["[%d] %s" % (i, lab) for i, lab in enumerate(labels)]}
    questions = {"operation": {"type": "choice", "instructions": Q_OP, "criteria": dict(OP_DESC)}}
    if cands:
        questions["target"] = {"type": "choice", "instructions": Q_TARGET,
                               "criteria": {str(i): lab for i, lab in enumerate(labels)}}
    out = agent.system_one(state, questions, max_len=3072, head_max_len=1536)
    return out["answers"], out["usage"]


def _node(cls, box, text="", clickable=False, children=()):
    return {"text": text, "content_desc": "", "hint": "", "clickable": clickable, "long_clickable": False,
            "scrollable": False, "checkable": False, "editable": False, "checked": False, "selected": False,
            "enabled": True, "visible": True, "class": cls, "resource_id": "", "bounds": box, "ime": False,
            "children": list(children)}


_feed = [_node("Button", (0, 0, 100, 20), "Search", True), _node("TextView", (0, 30, 100, 50), "Biryani House"),
         _node("TextView", (0, 60, 100, 80), "Desserts"), _node("TextView", (0, 90, 100, 110), "4.3")]
for _n in _feed:
    _n["depth"] = 1
assert [c["label"] for c in build_candidates(_feed, (100, 200))] == ["Search", "Biryani House", "Desserts"]


if __name__ == "__main__":
    xml = """<?xml version='1.0' ?><hierarchy rotation="0">
    <node class="android.widget.FrameLayout" package="p" bounds="[0,0][100,200]" clickable="false" enabled="true">
      <node class="android.widget.LinearLayout" package="p" bounds="[0,0][100,50]" clickable="true" enabled="true">
        <node class="android.widget.TextView" package="p" text="Battery" bounds="[5,5][60,25]" enabled="true"/>
        <node class="android.widget.TextView" package="p" text="80%" bounds="[5,25][60,45]" enabled="true"/>
      </node>
      <node class="android.widget.EditText" package="p" hint="Search" bounds="[0,60][100,90]" clickable="true"
            enabled="true"/>
      <node class="android.widget.Switch" package="p" bounds="[0,100][100,300]" checkable="true" checked="true"
            enabled="false" content-desc="Wi-Fi"/>
      <node class="android.widget.Button" package="ime" text="q" bounds="[0,150][10,160]" clickable="true"/>
    </node></hierarchy>UI hierchary dumped to: /dev/tty"""
    want = ["Battery 80% (LinearLayout)", "Search (EditText) edit", "Wi-Fi (Switch) on disabled"]
    cs = build_candidates(parse_uiautomator(xml, ime_package="ime"), (100, 200))
    assert [option_label(c) for c in cs] == want, [option_label(c) for c in cs]
    assert cs[2]["bounds"] == (0, 100, 100, 200)  # clipped to the screen
    assert history_entry("CLICK", cs[0]) == "CLICK Battery 80%" and history_entry("BACK") == "BACK"

    # the phone app's forest must give the same candidates: IME window dropped, invisible nodes skipped
    def j(cls, bounds, kids=(), **kw):
        base = dict(text="", content_desc="", hint="", clickable=False, long_clickable=False, scrollable=False,
                    checkable=False, editable=False, checked=False, selected=False, enabled=True, visible=True)
        return {**base, **kw, "class": "android.widget." + cls, "resource_id": "", "bounds": bounds,
                "children": list(kids)}
    app = j("FrameLayout", [0, 0, 100, 200], [
        j("LinearLayout", [0, 0, 100, 50], [j("TextView", [5, 5, 60, 25], text="Battery"),
                                            j("TextView", [5, 25, 60, 45], text="80%")], clickable=True),
        j("EditText", [0, 60, 100, 90], hint="Search", clickable=True, editable=True),
        j("Switch", [0, 100, 100, 300], content_desc="Wi-Fi", checkable=True, checked=True, enabled=False),
        j("Button", [0, 0, 9, 9], text="hidden", clickable=True, visible=False)])
    ime = j("Button", [0, 150, 10, 160], text="q", clickable=True)
    forest = {"windows": [{"type": 1, "root": app}, {"type": IME_WINDOW, "root": ime}]}
    got = [option_label(c) for c in build_candidates(parse_forest(forest), (100, 200))]
    assert got == want, got
    print("ok")

