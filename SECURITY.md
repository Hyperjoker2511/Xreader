# Security policy

## Reporting a vulnerability

Please report security problems **privately** — do not open a public
issue for a vulnerability.

Use GitHub's private vulnerability reporting: open this repository's
**Security** tab and choose **Report a vulnerability**. That keeps the
details between you and the maintainer until a fix is out.

Helpful things to include:

- What the problem is and where it happens (app version, source, screen).
- Steps to reproduce, if you have them.
- What an attacker could actually do with it, as far as you can tell.

You will get a reply as soon as the maintainer can manage — this is a
solo project, so please allow a few days. Fixes for confirmed problems
ship in the next hotfix or the next regular update, depending on
severity.

## Scope notes

- Xreader reads third-party novel sites. A problem *on one of those
  sites* (their content, their accounts, their servers) is theirs to
  fix — but if Xreader mishandles it (for example, a site's content
  escaping the reader, or a sign-in session leaking), that is in scope
  here.
- Sign-in sessions are cookies stored on your device only. Xreader
  never stores your site passwords. A report showing otherwise would
  be treated as critical.
