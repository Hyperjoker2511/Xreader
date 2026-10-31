# Xreader

**A novel reader built for people who read translated web novels — and refuse to settle.**

[![Build](https://github.com/Hyperjoker2511/Xreader/actions/workflows/build.yml/badge.svg)](https://github.com/Hyperjoker2511/Xreader/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Hyperjoker2511/Xreader)](https://github.com/Hyperjoker2511/Xreader/releases/latest)

Xreader reads Korean and Chinese web novels (and e-books) the way they deserve to be read: a calm, beautiful, cover-first library, an immersive reader that stays out of the story's way, sources you can add from a link and *prove* work before trusting them, and an AI hub that puts every major AI engine — Gemini, OpenAI, Claude, APInex, your own gateway, even a local model — behind one picker, used everywhere and wasted nowhere.

## ⬇️ Download

**[Get the latest release](https://github.com/Hyperjoker2511/Xreader/releases/latest)** — download the APK from the release page and install it. Android 8.0+; signed with the project's own key, so every update installs cleanly over the last.

## Highlights

- **Library first** — labelled bottom navigation (Library · Explore · History · Downloads · Profile), cover-first shelves, custom shelves plus Favourites, pull-to-refresh that shows what's new, and a "For you" row built from the tags you follow.
- **A reader that disappears** — true endless scroll, immersive mode, auto-scroll, real frosted glass (actual backdrop blur), vibrant colour schemes and reader palettes, 3D-tilt covers, and a profile page with genuine stage presence. Comics from sources that carry them read here too — with pinch and double-tap zoom.
- **Sources without the guesswork** — add a source from a link; Xreader test-drives the *whole path* (list → search → book page → chapters → chapter text) and shows you the proof before saving. Per-source health cards with one-tap repair, and a searchable source store.
- **Bring your library with you** — log in to a site (Webnovel, Wuxiaworld, Novelpia, …) and import your bookshelf: matched without duplicates, confirmed by you, never silently half-done.
- **An AI hub, not an AI feature** — bring keys for Gemini, OpenAI or Claude, use APInex, add *any* OpenAI- or Anthropic-compatible gateway by pasting its link (the app test-drives it first), or run a local model. Every AI feature — source building, "Catch me up" recaps bounded by your reading position, tag suggestions, lore chat, library matching — runs on whichever engine you pick. Pick one and only that one answers: no silent switches, ever.
- **Words you're learning** — tap a word in the reader, keep a personal vocabulary list with an offline dictionary.
- **Search inside your downloads**, reading history, offline reading, and backups that live on your phone first.

## Screenshots

<!-- Phone screenshots land here with the first public release. -->

## Building from source

The repository builds itself: every push runs the full unit-test suite and, when it passes, a signed release build in GitHub Actions. Release signing reads four repository secrets — `KEYSTORE_B64`, `XR_STORE_PASSWORD`, `XR_KEY_ALIAS`, `XR_KEY_PASSWORD` — whose values live only in the owner's private keystore note, never in this repository. Binary assets (fonts, launcher images, soundscapes) are stored base64-encoded for transport and restored by the workflow before building.

## Updates & contributing

Releases land every 15–30 days, built from the issues and reviews filed here plus the maintainer's own list. Contributions are welcome — and everything is checked first: every pull request is reviewed and run through the full test gate before it can merge. Nothing enters unchecked.

## Notes

Xreader is a personal project, built novel-first: no all-in-one media bundling, no feature buried three menus deep, and defaults that are right with zero configuration.
