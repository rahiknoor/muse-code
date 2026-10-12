# muse-code

The Java (plus Python/shell) tooling Muse built for Rahik's workspace — auditors, valuation pipelines, tracker jobs, vault tooling, and video-pipeline scripts.

This repo exists so the code can be read, reviewed, and explained (CodeRabbit PR walkthroughs). It's a mirror for reading — the live code runs from the workspace, not from here.

## Layout (mirrors the workspace)

- `auditors/` — the read-only auditor fleet (vault diff, consistency, secrets, number grounding, …)
- `atlas-scripts/` — Obsidian vault tooling (valuation refresh, graph, index, link checks)
- `creator_tracker/` — YouTube creator call tracking (targets, diffs, view builder)
- `dcf_csv/` — the DCF valuation pipeline (rebuild, gates, backtests)
- `java-shared/` — shared helpers (SelfTest, LastGood, Blessed, …)
- `job_infra/` — job infrastructure (consecutive-failure checks, …)
- `stock_watch/` — price/drop-alert pipelines
- `youtube/`, `av_free_stack/` — video pipeline scripts
- `goals/` — per-goal pipeline scripts
- everything else — study files, one-off builders, misc tooling

## Notes

- Java 21 (Temurin). Most tools are single-file programs run with `java <File>.java`.
- Nothing here holds secrets — API keys and credentials live in the Secure Vault / authd, never in code.
- Values and data files are not included — code only.
