# Third-party notices — manga reader (Komikku)

Xreader is MIT-licensed. The manga reader in this app
(`app.xreader.komikku` and the View-based viewer packages under it)
contains code copied and adapted from the projects below. Those
portions remain licensed under the Apache License, Version 2.0 —
the full text ships beside this file as `APACHE-2.0.txt` — and are
NOT relicensed by Xreader's MIT licence. Copied files keep their
donor copyright headers, and each adapted file carries a changes
note in its header comment (Apache-2.0 §4(b)).

## Komikku
Copyright 2024 The Komikku Authors.
https://github.com/komikku-app/Komikku @ 1656575
Reader activity/viewers, page loaders, reader presentation
(app bars, chapter navigator, settings dialogs, chapter list,
page actions, transitions), reader preferences and tap-zone
navigation — adapted to Xreader's data spine (Room library,
ChapterPagePipeline page streams).

## Mihon / Tachiyomi
Copyright 2015 Javier Tomás and the Tachiyomi contributors;
Copyright 2024 The Mihon Authors.
https://github.com/mihonapp/mihon — upstream of Komikku; the
reader architecture, source API and page model originate here.

## TachiyomiSY
Copyright 2021 Jobobby04 and contributors. The SY-layer reader
features (dual-page handling, auto-scroll, ExhUtils strip,
chapter transitions) come from the TachiyomiSY layer inside
Komikku.

## SubsamplingScaleImageView (Tachiyomi fork)
Copyright © Dave Morrissey and the Tachiyomi contributors.
`com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d`
(Apache License 2.0) — the tiling image view every manga page is
rendered through; tiles decode at the resolution the visible
region and zoom level need.
https://github.com/tachiyomiorg/subsampling-scale-image-view

## PhotoView
Copyright 2017 Chris Banes (Apache License 2.0). Referenced by
the donor's animated-image path; Xreader's animated fallback
currently renders through Coil instead (see the changes note in
ReaderPageImageView.kt).
