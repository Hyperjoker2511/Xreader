# WS-7 encrypted-PDF fixtures (v1.5.4)

Real standard-security-handler PDFs generated with **pikepdf**
(the QPDF library) by `generate_fixtures.py` beside them — the
benchmark shape in miniature: 4 pages of extractable text, an
image plate on page 1, restricted permission bits.

| File | Locks | Opens with |
|---|---|---|
| `plain.pdf` | none | — |
| `owner-password-only.pdf` | AES-128, owner password only | empty password (the Moon+ class the old gate refused) |
| `owner-password-only-256.pdf` | AES-256, owner password only | empty password |
| `user-password.pdf` | AES-128, user + owner passwords | user password below |

Fixture-only passwords (not used anywhere else):
owner = `owner-secret`, user = `reader-secret`.

Proven by `PdfLoadClassificationTest` on the JVM (the load seam).
The production extractor's text stripping needs Android
(`PDFBoxResourceLoader`) and remains a **device leg**: import
`owner-password-only.pdf` on a device and confirm the book lands
with text + first-page cover; import `user-password.pdf` and
confirm the password prompt unlocks it with `reader-secret`.
