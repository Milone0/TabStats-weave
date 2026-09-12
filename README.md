# TabStats (Weave port)

Hypixel stats directly in your 1.8.9 tablist — Bedwars, Duels and Skywars.

This is a port of [DirectivesMods/TabStats](https://github.com/DirectivesMods/TabStats)
from Forge to [Weave Loader](https://github.com/Weave-MC/Weave-Loader), so that it runs on
**Lunar Client 1.8.9**. Upstream closes its README with *"This does not work on Lunar - if
someone wants to port it to Weave you may."* — this is that port.

On plain Forge 1.8.9, use upstream instead. Nothing here improves on it.

Curious *why* Lunar cannot load the Forge build, or need to fix a bug?
See [TECHNICAL.md](TECHNICAL.md).

## Requirements

- Lunar Client with Minecraft **1.8.9**
- A Hypixel API key from the [Developer Dashboard](https://developer.hypixel.net/dashboard)
  (the old in-game `/api new` was removed in 2023)
- The vanilla `1.8.9.jar` at `%APPDATA%\.minecraft\versions\1.8.9\1.8.9.jar`
  — Weave reads it to build its mappings. If it is missing, launch 1.8.9 once in the
  official Minecraft launcher, or point Weave elsewhere with
  `-Dweave.vanilla.jar.path=...`
- **JDK 17** or newer — only if you build the mod yourself

## Get the jar

Download `TabStats-1.3.0-weave.1.jar` from the
[latest release](https://github.com/Milone0/TabStats-weave/releases/latest). That is all
most people need — continue at [Install](#install).

To build it from source instead, e.g. to change something:

```bash
git clone https://github.com/Milone0/TabStats-weave.git
cd TabStats-weave
./gradlew build          # Windows: gradlew.bat build
```

The first run downloads Gradle, the Weave artifacts and the Minecraft libraries, and
remaps the vanilla jar — expect a few minutes. Result: `build/libs/TabStats-1.3.0-weave.1.jar`.

If `java` is not on your `PATH`, point Gradle at a JDK explicitly:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew build
```

## Install

**1. Get Weave Loader.** Download `Weave-Loader-Agent-1.4.0.jar` from the
[Weave releases](https://github.com/Weave-MC/Weave-Loader/releases) and put it somewhere
permanent — Lunar will reference this exact path on every launch.

**2. Install the mod.** Copy the built jar into Weave's version-specific mods folder
(create it if needed):

| OS | Path |
|---|---|
| Windows | `%USERPROFILE%\.weave\mods\1.8.9\` |
| macOS / Linux | `~/.weave/mods/1.8.9/` |

The `1.8.9` subfolder matters: jars placed directly in `mods/` load on *every* Minecraft
version.

**3. Add the agent to Lunar.** In the Lunar launcher:

- open **Settings**, and turn on **Advanced Mode** in the left sidebar
  — the next field is hidden without it, even from the search box
- go to **Game** and find **JVM Arguments**
- set it to, with your own path:

```
-javaagent:C:/path/to/Weave-Loader-Agent-1.4.0.jar
```

Use forward slashes. The path must not contain spaces — the launcher splits the field on
spaces. Prefer the **profile** field over the global one, so Weave only attaches to 1.8.9
and not to your other versions.

**4. Launch** version 1.8.9 with the module set to **Lunar** — not Forge.

## Use

- `/tabstats` (or `/ts`) opens the menu — paste your Hypixel key and save
- join a Bedwars, Duels or Skywars lobby and hold Tab
- scroll with the mouse wheel while Tab is held
- in a pre-game lobby, every player that writes something in chat is added to the tab list
  with their stats, even though the lobby gives them no tab entry — enough to decide
  whether to skip the lobby before it starts. Those rows disappear again once the game
  begins, or when the player leaves
- the menu also toggles the mod and the header/footer
- optional: an [Urchin](https://urchin.ws) key adds cheater tags, Bedwars only.
  Without one that column is just a grey `-`

Settings and keys live in `%APPDATA%\.minecraft\tabstats\config.json`, in plaintext.

## Did it work?

`~/.weave/logs/latest.log` should open with:

```
[..] Attached Weave
[..] Discovered 1 total mod files
[..] Weave initialized in ....ms
```

No `Attached Weave` means the JVM argument never reached the game — check
`* Custom JVM Arguments:` in `~/.lunarclient/logs/launcher/main.log`. Further
troubleshooting is in [TECHNICAL.md](TECHNICAL.md#9-debugging).

## Notes

- Not affiliated with or endorsed by Hypixel. API-based stat mods are permitted there.
- Injecting into Lunar Client conflicts with Lunar Client's Terms of Service. Moonsworth
  can break or act against this at any time. Your call.
- License: WTFPL, inherited from upstream. Credits to @exejar, @yabqy and @DirectivesMods
  for TabStats, and to the Weave-MC team for the loader.
