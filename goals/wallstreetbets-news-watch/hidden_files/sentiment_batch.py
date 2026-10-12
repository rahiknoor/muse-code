#!/usr/bin/env python3
"""
sentiment_batch.py — finance sentiment batch scorer for the Reddit crowd-pulse.

Reads JSON lines from stdin:  {"id": "<post id>", "text": "<title + body>"}
Writes JSON lines to stdout: {"id": ..., "label": "bullish|bearish|neutral",
                               "score": <float -1..1>,
                               "finbert": "positive|negative|neutral",
                               "finbert_conf": <float>, "vader": <float>,
                               "irony": <float 0..1>, "sarcasm_flag": <bool>}

Ensemble: tabularisai/ModernFinBERT (finance sentiment, trained on
social-media text) blended with VADER (rule-based, catches social slang).
The VADER leg carries a finance/WSB lexicon overlay (FINANCE_LEXICON +
phrase pre-substitution — pattern adapted from
AlexKim45/company-sentiment-scraper) so WSB jargon ("tendies", "bagholder",
"to the moon", 🚀/📉 runs) scores instead of reading as neutral.
Sarcasm handling: cardiffnlp/twitter-roberta-base-irony flags ironic posts;
when irony >= 0.7 the score is DISCOUNTED toward neutral (scaled by the
irony confidence) and sarcasm_flag=true is emitted. Deliberately NOT an
auto-flip: the irony model is tweet-trained and WSB slang ("diamond hands",
"tendies") is underrepresented, so a confident reversal would be its own
fabrication. CPU only, batch size 16. Logs go to stderr; stdout is pure
JSON lines.

Crowd output is sentiment-only signal — it must never move a valuation
number. See CrowdPulse.java's hard fence.
"""
import json
import os
import sys
from pathlib import Path

BATCH = 16
MAX_CHARS = 1500  # keep well under the model's token limit

MODEL_NAME = os.environ.get("FINBERT_MODEL", "tabularisai/ModernFinBERT")
IRONY_NAME = os.environ.get("IRONY_MODEL", "cardiffnlp/twitter-roberta-base-irony")
IRONY_THRESHOLD = 0.7   # at/above: discount toward neutral + flag
IRONY_DISCOUNT = 0.5    # score *= (1 - IRONY_DISCOUNT * irony_prob)

# --- Finance/WSB lexicon overlay for the VADER leg -------------------------
# Pattern adopted 2026-09-28 from AlexKim45/company-sentiment-scraper
# (analysis/finance_lexicon.py + analysis/backends.py): stock VADER ships a
# general-purpose lexicon that mislabels WSB/fintwit jargon — exactly the
# vocabulary the crowd-pulse scores. We extend VADER's lexicon with
# hand-picked weights in the [-4, +4] range VADER expects (we stay inside
# [-3.5, +3] so multi-token phrases can still push extremes without
# saturating the compound), pre-substitute multi-word phrases, and
# space-separate emoji runs so each emoji is scored. VADER substitutes known
# emojis with text descriptions before scoring, so explicitly-scored emojis
# are popped from analyzer.emojis (same gotcha, same fix as upstream).
FINANCE_LEXICON = {
    # --- Bullish jargon ---
    "moon": 2.5, "mooning": 2.8, "lambo": 2.0, "yolo": 1.5,
    "tendies": 2.0, "ripping": 2.5, "ripped": 2.0,
    "squeeze": 1.8, "squeezing": 1.8,
    "rallying": 2.2, "rally": 2.0, "breakout": 2.0, "breaking-out": 2.0,
    "uptrend": 1.8, "outperform": 2.0, "outperformed": 2.0,
    "outperforming": 2.0, "upgraded": 2.0, "upgrade": 1.8,
    "beat": 2.0, "beats": 2.0, "beating": 1.8, "topped": 1.5,
    "raised": 1.5, "raises": 1.5, "accelerating": 1.8, "blowout": 2.5,
    "surged": 2.2, "surging": 2.2, "soared": 2.5, "soaring": 2.5,
    "skyrocket": 2.8, "skyrocketed": 2.8, "printing": 1.5,  # "calls printing"
    "bullrun": 2.8, "hodl": 1.2, "hodling": 1.2,
    # --- Bearish jargon ---
    "bagholder": -2.5, "bagholders": -2.5, "bagholding": -2.5,
    "rugpull": -3.0, "rugpulled": -3.0, "rekt": -2.5,
    "cope": -1.5, "coping": -1.5, "copium": -2.0,
    "puking": -2.5, "puked": -2.5, "tanking": -2.5, "tanked": -2.5,
    "drilling": -2.0, "drilled": -2.0,  # "price is drilling"
    "plunged": -2.8, "plunging": -2.8, "crashed": -2.8, "crashing": -2.8,
    "downgrade": -2.0, "downgraded": -2.0,
    "missed": -1.8, "miss": -1.5, "missing": -1.2,  # earnings miss
    "downtrend": -1.8, "underperform": -2.0, "underperformed": -2.0,
    "guidance-cut": -2.5, "layoffs": -2.5, "layoff": -2.0,
    "bankruptcy": -3.5, "bankrupt": -3.5, "fraud": -3.5,
    "selloff": -2.2, "sell-off": -2.2, "dumping": -2.0, "dumped": -2.0,
    # --- Symbols / emojis ---
    "\U0001F680": 3.0, "\U0001F319": 1.5, "\U0001F48E": 1.8,  # diamond hands
    "\U0001F4B0": 1.5, "\U0001F4C8": 2.2, "\U0001F7E2": 1.5,
    "\U0001F402": 2.2, "\U0001F43B": -2.2, "\U0001F4C9": -2.2,
    "\U0001F534": -1.5, "\U0001F4A9": -2.0, "\u26A0\uFE0F": -1.0,
    "\U0001FA78": -2.5,  # "bloodbath"
}

# Multi-word phrases collapsed to single lexicon tokens (longest first).
FINANCE_PHRASES = [
    ("to the moon", "moon"),
    ("diamond hands", "\U0001F48E"),
    ("paper hands", "bagholder"),
    ("guidance cut", "guidance-cut"),
    ("cut guidance", "guidance-cut"),
    ("raised guidance", "raised"),
    ("raises guidance", "raised"),
    ("guided down", "guidance-cut"),
    ("guided up", "raised"),
    ("short squeeze", "squeeze"),
    ("sell off", "selloff"),
    ("sell-off", "selloff"),
]

_EMOJIS_IN_LEXICON = tuple(k for k in FINANCE_LEXICON if not k.isascii())


def preprocess(text):
    """Lowercase + collapse finance phrases + space-separate emoji runs.

    VADER tokenizes on whitespace, so a run like "🚀🚀🚀" collapses into one
    unrecognised token — pad each lexicon-known emoji so every instance
    is scored.
    """
    lowered = text.lower()
    for phrase, token in FINANCE_PHRASES:
        if phrase in lowered:
            lowered = lowered.replace(phrase, token)
    for emoji in _EMOJIS_IN_LEXICON:
        if emoji in lowered:
            lowered = lowered.replace(emoji, " %s " % emoji)
    return lowered


def resolve_local(env_name, default_name, local_dir):
    """Prefer an explicit env override, then a local snapshot dir, then hub."""
    if env_name in os.environ:
        return os.environ[env_name]
    home = os.environ.get("HOME") or str(Path.home())
    p = Path(home) / "workspace" / "models" / local_dir
    return str(p) if p.is_dir() else default_name


def load():
    import torch
    from transformers import AutoTokenizer, AutoModelForSequenceClassification
    from vaderSentiment.vaderSentiment import SentimentIntensityAnalyzer

    device = torch.device("cpu")
    tok = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)
    model.to(device)
    model.eval()
    vader = SentimentIntensityAnalyzer()
    # Finance/WSB lexicon overlay (see FINANCE_LEXICON above): stock VADER
    # mislabels the exact jargon the crowd-pulse scores.
    vader.lexicon.update(FINANCE_LEXICON)
    for emoji in _EMOJIS_IN_LEXICON:
        vader.emojis.pop(emoji, None)
    print(f"[sentiment_batch] loaded {MODEL_NAME} on cpu", file=sys.stderr)

    # Irony model is best-effort: the batch still scores without it,
    # just with no sarcasm flagging (logged, not silent).
    i_tok = i_model = None
    try:
        src = resolve_local("IRONY_MODEL", IRONY_NAME, "irony")
        i_tok = AutoTokenizer.from_pretrained(src)
        i_model = AutoModelForSequenceClassification.from_pretrained(src)
        i_model.to(device)
        i_model.eval()
        print(f"[sentiment_batch] loaded irony model {src} on cpu", file=sys.stderr)
    except Exception as e:
        print(f"[sentiment_batch] irony model unavailable ({type(e).__name__}: {e})"
              " — no sarcasm flagging", file=sys.stderr)
    return tok, model, device, vader, i_tok, i_model


def finbert_signed(tok, model, device, texts):
    import torch
    out = []
    with torch.no_grad():
        for i in range(0, len(texts), BATCH):
            chunk = [t[:MAX_CHARS] for t in texts[i:i + BATCH]]
            enc = tok(chunk, padding=True, truncation=True,
                      max_length=512, return_tensors="pt").to(device)
            logits = model(**enc).logits
            probs = torch.softmax(logits, dim=-1)
            conf, idx = torch.max(probs, dim=-1)
            id2label = model.config.id2label
            for j in range(len(chunk)):
                label = str(id2label[int(idx[j])]).lower()
                c = float(conf[j])
                # ModernFinBERT: bullish/bearish/neutral; classic FinBERT:
                # positive/negative/neutral. Handle both label schemes.
                if "bull" in label or "pos" in label:
                    out.append(("bullish", c, c))
                elif "bear" in label or "neg" in label:
                    out.append(("bearish", c, -c))
                else:
                    out.append(("neutral", c, 0.0))
    return out


def irony_probs(i_tok, i_model, device, texts):
    """P(irony) per text. Model card label order: 0=non_irony, 1=irony."""
    import torch
    out = []
    with torch.no_grad():
        for i in range(0, len(texts), BATCH):
            chunk = [t[:MAX_CHARS] for t in texts[i:i + BATCH]]
            enc = i_tok(chunk, padding=True, truncation=True,
                        max_length=512, return_tensors="pt").to(device)
            probs = torch.softmax(i_model(**enc).logits, dim=-1)
            out.extend(float(p[1]) for p in probs)
    return out


def apply_sarcasm_discount(score, irony_p):
    """Discount toward neutral when irony is high; never flip the sign."""
    if irony_p >= IRONY_THRESHOLD:
        return score * (1.0 - IRONY_DISCOUNT * irony_p), True
    return score, False


def main():
    rows = []
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    if not rows:
        return

    tok, model, device, vader, i_tok, i_model = load()
    texts = [str(r.get("text", "")) for r in rows]
    fb = finbert_signed(tok, model, device, texts)
    ip = (irony_probs(i_tok, i_model, device, texts)
          if i_model is not None else [0.0] * len(texts))

    for r, (flabel, fconf, fsigned), irony_p in zip(rows, fb, ip):
        v = vader.polarity_scores(preprocess(str(r.get("text", ""))))["compound"]
        combined = 0.7 * fsigned + 0.3 * v
        combined, sarcastic = apply_sarcasm_discount(combined, irony_p)
        if combined >= 0.25:
            label = "bullish"
        elif combined <= -0.25:
            label = "bearish"
        else:
            label = "neutral"
        sys.stdout.write(json.dumps({
            "id": r.get("id"),
            "label": label,
            "score": round(combined, 4),
            "finbert": flabel,
            "finbert_conf": round(fconf, 4),
            "vader": round(v, 4),
            "irony": round(irony_p, 4),
            "sarcasm_flag": sarcastic,
        }) + "\n")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
