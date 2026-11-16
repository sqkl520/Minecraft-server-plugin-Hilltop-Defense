# Security Policy

## Reporting a vulnerability

**Please do not open a public issue, pull request or discussion for a security
problem.** A public report gives everyone running a server the same window to be
exploited before a fix ships.

Use one of these private channels:

1. **GitHub private advisory** (preferred) —
   [Security → Report a vulnerability](https://github.com/sqkl520/Minecraft-server-plugin-Hilltop-Defense/security/advisories/new)
   on this repository. This keeps the report, the discussion and the eventual fix
   private until a patched release is published.
2. **Email** — `sqkl520soap@gmail.com`, with `[Hilltop Village security]` in the
   subject line, if you cannot use GitHub advisories.

Please include:

- the plugin version (`/hilltop status` shows it, or check the JAR filename),
- the Paper version and any other plugins that are relevant,
- what an attacker can do and what preconditions are needed,
- reproduction steps, or a proof of concept if you have one.

## What to expect

| Stage | Target |
|---|---|
| Acknowledgement that the report was received | within **7 days** |
| Initial assessment (is it reproducible, is it in scope) | within **14 days** |
| Fix or mitigation in a published release | best effort; depends on severity and complexity |

This is a single-maintainer hobby project, so these are honest targets rather than
a contractual SLA. If a report is going to take longer than a month to fix, you
will be told rather than left waiting.

## Scope

In scope — anything that lets a player on a server running this plugin:

- break out of the game rules the plugin enforces (for example obtain items that
  should be protected, or damage villagers and nodes that should be immune),
- escalate privileges beyond the permissions they were granted,
- corrupt server state or data files they should not be able to reach,
- crash the server, or leak data belonging to other players.

Out of scope — reports that depend on:

- the server owner editing configuration to something unsafe and then expecting
  the plugin to defend against it,
- other plugins, the server software itself, or the client,
- features that are documented limitations (for example the tab list has no way to
  hide health numbers — that is a Bukkit API limitation, not a vulnerability).

## Supported versions

Only the **latest published release** receives security fixes. Pre-release
(`-SNAPSHOT`) builds on the `main` branch are development snapshots and are not
supported for production servers.

## Disclosure

Once a fix is released, the advisory is published with credit to the reporter
unless you ask to stay anonymous.
