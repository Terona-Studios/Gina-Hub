# Gina-Hub — Attribution Notice

**Gina-Hub is a heavily modified fork of [PaperMC / Paper](https://github.com/PaperMC/Paper).**

Gina-Hub is not an independent implementation. It is built directly on Paper's source code
(itself built on Spigot, CraftBukkit, and Bukkit) and contains substantial modifications and
performance optimizations specifically targeting high-player-count static HUB/lobby servers.
Gina-Hub does not claim to be written from scratch, and does not attempt to hide or
misrepresent its origin.

## Relationship to Paper

| | |
|---|---|
| Base project | [PaperMC/Paper](https://github.com/PaperMC/Paper) |
| Base version | Minecraft / Paper 1.21.11 |
| Base commit | `c5eb0790f199da6c38d0a650e1e5cd5415b28185` |
| Base Paper build | 1.21.11 build #132 (STABLE) |

Gina-Hub retains the Paper/Bukkit plugin API surface (events, commands, scheduler, plugin
lifecycle, plugin messaging) so far as realistically possible, so that the existing Paper
plugin ecosystem continues to work. Server software identity shown to players and admins
(startup banner, `/version`, `/gina` command, crash reports, console) has been changed from
"Paper" to "Gina-Hub" — see the [engineering rationale](#what-was-and-was-not-rebranded)
below for what was deliberately left alone.

## Licensing

Gina-Hub inherits its licensing from Paper unchanged. See [LICENSE.md](LICENSE.md) and
[licenses/GPL.md](licenses/GPL.md) / [licenses/MIT.md](licenses/MIT.md). Gina-Hub's own
modifications to GPLv3-licensed code are themselves distributed under GPLv3, as required
by its copyleft terms. No upstream copyright, contributor, or license notice has been
removed.

## What was, and was not, rebranded

Gina-Hub replaces "Paper" branding wherever it is shown to players or server admins as the
running software's identity: the startup banner, `/version` / `/about` output, the `/gina`
admin command (with `/paper` kept as a working alias), crash report headers, the console
application name, and the generated config file headers.

Gina-Hub intentionally does **not** rename:
- Internal Java package names (`io.papermc.paper.*`, `com.destroystokyo.paper.*`) — renaming
  these would be a large, compatibility-breaking change with no user-facing benefit.
- The Paper API-compatibility brand id (`papermc:paper`) used by `ServerBuildInfo.isBrandCompatible()`
  — plugins use this to detect "does this server support the Paper API surface," and Gina-Hub
  genuinely does, so this check correctly continues to return `true` in addition to Gina-Hub's
  own brand id (`cryvex:gina-hub`).
- The term "Paper plugin" as used in the plugin-loading subsystem (`paper-plugin.yml` format,
  `/plugins` output categorization) — this is ecosystem terminology plugin developers already
  rely on; renaming it would confuse compatibility, not clarify branding.
- Upstream documentation links (`docs.papermc.io`, Paper's Discord) in warning/error messages
  where the underlying documented behavior is still genuinely Paper's and Gina-Hub has no
  competing documentation of its own yet.
