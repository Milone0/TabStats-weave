# TabStats on Lunar Client — technical notes

Everything a maintainer needs: why the Forge build cannot work on Lunar, how Weave gets
in, what the port changed and why, and how to debug it. Claims here were verified against
the shipped bytecode and runtime logs on Lunar Client 1.8.9 (2026-09), not inferred from
documentation.

## 1. Why Lunar's Forge module cannot load this mod

Lunar Client ships a real Forge 1.8.9 and offers a `Forge` module in its launcher, so the
obvious approach is to drop the Forge jar into a mods folder. It does not work, and no
folder, manifest tweak or launcher setting changes that.

What Lunar ships in `~/.lunarclient/offline/multiver/`:

- `Forge_v1_8.jar` — the genuine Forge universal jar: FML, `forge.srg`,
  `deobfuscation_data-1.8.9.lzma`, `ClientCommandHandler`, `RenderGameOverlayEvent`
- `forge-0.1.0-SNAPSHOT-all.jar` — Lunar's `com.moonsworth.lunar.forge` integration,
  containing `forge/mcp_searge_1.8.9.kin`, an SRG mapping table that even includes
  `field_175196_v`, the field this mod reflects on
- `lunar-platform-mappings-v1_8.jar` — `v1_8_inflight_forge.kin`

So the pieces are all present. The module still refuses. Decompiled
(`RuntimeForgeIchorModule`, obfuscated names replaced with readable ones):

```java
public List<IchorMod> getMods(LaunchArgs args) {
    IchorAPI.doNotCacheClasses();
    Mode mode = Mode.from(args...);
    ArrayList<IchorMod> list = new ArrayList<>();
    if (mode == FORGE || mode == OPTIFORGE) {
        addLoadingPlugin("net.minecraftforge.fml.relauncher.FMLCorePlugin");
        addLoadingPlugin("net.minecraftforge.classloading.FMLForgePlugin");
    }
    return list;          // always empty; no directory is ever scanned
}
```

Three findings, together conclusive:

1. The contributed mod list is **hardcoded empty**. Only the two FML core plugins are
   registered.
2. The method holding the string `Loading local mod file ` is `private synthetic` — a
   lambda body that nothing in the class calls. Dead code, apparently inherited from the
   Fabric module. That is why the line never appears in any log.
3. Mods are registered by explicit `(name, class)` pair:
   `loadMod(args, name, modClass, ModDiscoverer)` → `ModContainerFactory.build(...)` →
   `Loader.instance().mods.add(container)`. The only mod name referenced anywhere in the
   module is `com/replaymod/`.

Consistent with that, Ichor exposes `ichor.fabric.localModPath` and no Forge counterpart
(the full property list can be grepped out of `genesis-0.1.0-SNAPSHOT-all.jar`), and
Lunar documents custom mod loading as Fabric-only, 1.16.5+.

FML's own directory scan does run and logs `Searching …\.minecraft\mods for mods`, which
is misleading: the result is never turned into mod containers. The counter stays at
`Forge Mod Loader has identified 4 mods to load` — `mcp, FML, Forge, replaymod` — whether
or not a jar is present.

Ruled out experimentally, each with a launch and a log check: `.minecraft\mods`,
`.minecraft\mods\1.8.9`, `profiles\1.8\mods\`, `…\mods\forge-1.8.9\`,
`…\mods\ichor-1.8.9\`, the launcher's own "install mod" UI, and a rebuilt jar whose
manifest was stripped to `Manifest-Version: 1.0` (ruling out `ModSide`, `ForceloadAsMod`
and `TweakOrder: 0` as the cause).

**Lunar's Forge 1.8.9 module exists to run Lunar's own Forge-based integrations, chiefly
ReplayMod — not third-party mods.**

## 2. Why Weave works instead

Weave is not a mod loader that Lunar has to cooperate with. It is a plain JVM agent, so
the decision to load it is made outside Lunar's code and cannot be filtered by it.

`Weave-Loader-Agent-1.4.0.jar` declares `Premain-Class:
net.weavemc.loader.impl.bootstrap.AgentKt`. Boot sequence from a real run
(`~/.weave/logs/latest.log`, game log starts at `18:00:15` — Weave was 22 s early):

```
[17:59:53] Attached Weave                                   <- premain, before Minecraft
[17:59:53] Discovered 1 total mod files
[17:59:53] Loading merged mappings for 1.8.9 (105ms)
[17:59:53] Remapping JAR '1.8.9.jar' from namespace 'official'...
[17:59:56] Remapping JAR 'TabStats-1.3.0.jar' from namespace 'mcp-named'...
[17:59:56] Calling tweakers
[18:00:08] Bootstrapping Weave Loader...
[18:00:11] SpongePowered MIXIN … (within GenesisClassLoader(v1_8))
[18:00:12] Successfully registered Minecraft API (net.weavemc.api:api-v1_8:1.4.0)
[18:00:12] Weave initialized in 3258ms
```

Step by step:

1. **premain** runs before Minecraft's main class exists.
2. Mods are collected from `~/.weave/mods` plus a version subfolder named after the
   running version (`FileManager.walkMods`). Only `*.jar` directly inside those folders,
   non-recursive.
3. Mappings for 1.8.9 are built from Forge's MCP artifacts (`mcp-1.8.9-srg.zip` plus
   `methods.csv`/`fields.csv`) merged with the vanilla jar for descriptor recovery, and
   cached as Tiny v2 in `~/.weave/.cache/mappings`. **This is the only reason the vanilla
   `1.8.9.jar` is required.**
4. Each mod jar is remapped from its declared namespace to the runtime namespace, cached
   in `~/.weave/.cache/jars/<namespace>_<version>/`.
5. Lunar-specific: `Agent.kt` sets `ichor.prebakeClasses=false` for
   `MinecraftClient.LUNAR`, because Ichor's prebaked classes would otherwise bypass
   transformation.
6. `Bootstrap` waits until `net/minecraft/client/main/Main` is loaded by Lunar's own
   loader, injects Weave into `GenesisClassLoader(v1_8)`, and starts `WeaveLoader`.
7. The event API matching the running version is resolved at runtime over Maven into
   `~/.weave/.maven-repository` — it is not shaded into the mod.
8. Each mod's `entryPoints` class is instantiated and `preInit(Instrumentation)` / `init()`
   are called.

Also worth knowing, since it has security implications: `ArgumentSanitizer` retransforms
`sun.management.RuntimeImpl` so that `getInputArguments()` filters out any argument
containing `"javaagent"`. Weave deliberately hides itself from in-process argument
inspection.

## 3. Mappings — the reason this port was cheap

The single most important fact:

> **Lunar Client ships Minecraft deobfuscated.** Below 1.16.5 it runs under MCP *named*
> mappings — `net.minecraft.client.Minecraft`, `getMinecraft()`, `thePlayer`,
> `GuiPlayerTabOverlay.renderPlayerlist`. Only Lunar's *own* code
> (`com.moonsworth.lunar.*`) is obfuscated.

Confirmed three independent ways: Lunar's `.kin` tables map `net/minecraft/...` onto
`net/minecraft/...`; `Mappings.kt:46` reads
`MinecraftClient.LUNAR -> if (version < V1_16_5) MCP.named else MOJANG.named`; and Weave's
bootstrap waits on the literal class name `net/minecraft/client/main/Main`.

Namespaces involved:

| Namespace | Members look like | Who uses it |
|---|---|---|
| `official` | `ave`, `bib` | the vanilla jar on disk |
| `mcp-srg` | `func_71410_x`, `field_71439_g` | production Forge mods |
| `mcp-named` | `getMinecraft`, `thePlayer` | **Lunar 1.8.9 runtime, and this source tree** |

The TabStats *source* is written in MCP-named (upstream builds with
`de.oceanlabs.mcp:mcp_stable:22-1.8.9`; Essential Loom only reobfuscates to SRG at
package time). Lunar's runtime is also MCP-named. So `mcpMappings()` in `build.gradle.kts`
declares `namespace = "mcp-named"` and Weave's remap of our jar is effectively an identity
pass — visible in the log as `from namespace 'mcp-named'`.

**Consequence: no SRG-to-named rewrite of the sources was needed.** That was expected to be
the bulk of the work and it disappeared entirely. If you ever retarget this at plain Forge,
set `namespace = "mcp-srg"` instead — supported by the plugin, but untested here.

Never mixin or reflect into `com.moonsworth.lunar.*`: its obfuscation is re-randomised on
Lunar updates. Hooking only `net.minecraft.*` is what makes this port survive them.

## 4. What the port changed

Roughly 90% of the code — `playerapi/**`, most of `render/StatsTab`, `config/**`,
`gui/**`, `util/**` — is untouched, because it only talks to `net.minecraft.*`, Gson,
Apache HttpClient and LWJGL. Only the Forge glue was replaced.

| File | Change | Why |
|---|---|---|
| `TabStats.java` | `@Mod` + three `@Mod.EventHandler` → `ModInitializer`; `MinecraftForge.EVENT_BUS` → `EventBus`; `ClientCommandHandler.instance.registerCommand` → `CommandBus.register` | Weave entrypoint model |
| `listener/GameOverlayListener.java` | dropped the `RenderGameOverlayEvent.Pre` handler; overlay swap moved into a `TickEvent.Post` handler; render logic extracted into `renderTab(Scoreboard, ScoreObjective)` | see §5 |
| `render/StatsTab.java` | added a `renderPlayerlist(int, Scoreboard, ScoreObjective)` override; removed `@SideOnly(Side.CLIENT)` | see §5 |
| `listener/GuiOpenListener.java`, `playerapi/WorldLoader.java` | `TickEvent.ClientTickEvent` + `if (phase != Phase.END) return;` → `TickEvent.Post` | Weave splits Pre/Post into distinct event types instead of a phase field |
| `listener/InputListener.java` | `MouseEvent.dwheel` → `MouseEvent.getDWheel()`; `mc` resolved per call instead of in a field | Kotlin `@get:JvmName`; field init would run too early |
| `command/TabStatsCommand.java` | `CommandBase` → Weave `Command("tabstats", "ts")`, `processCommand` → `execute(String[])` | Weave command bus. Note `args[0]` is the command name itself |
| `util/Reflect.java` | **new** — replaces Forge's `ReflectionHelper`, also used by `gui/MaskedGuiTextField` | that class ships with Forge and does not exist here |
| `util/References.java` | Blossom `@ID@`/`@NAME@`/`@VERSION@` tokens → literals | the Blossom plugin was dropped |
| `gui/AbstractApiKeyGui.java`, `gui/TabStatsGui.java` | removed `throws IOException` from the `keyTyped` / `mouseClicked` overrides | see §6 |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Essential Loom + Blossom + pack200 → `net.weavemc.gradle`; dropped `loom.platform`; deleted `mcmod.info` and `install.local.gradle.kts` | Loom builds Forge jars; the install script depended on Loom's `remapJar` |

`util/Reflect.java` resolves a field by trying each candidate name in order, walking up the
class hierarchy, and — as a last resort — by unique declared type. That fallback exists
because a bytecode remapper never rewrites reflection *name strings*: if a future Lunar
build renamed the field, the name lookup would silently fail while the type lookup still
finds it. Names are tried MCP-first (`overlayPlayerList`), SRG second
(`field_175196_v`), which is the order that matters on Lunar.

Generated metadata, from `weave { configure { ... } }`:

```json
{ "name": "TabStats", "modId": "tabstats",
  "entryPoints": ["tabstats.TabStats"], "namespace": "mcp-named" }
```

`compiledFor` is intentionally absent — `ConfigurationBuilder` in plugin 1.4.0 does not
expose it, and Weave hard-fails a mod whose `compiledFor` mismatches the running version.

## 5. How the tab list is replaced

The Forge build cancelled exactly one HUD element:

```java
@SubscribeEvent
public void onOverlayRender(RenderGameOverlayEvent.Pre event) {
    if (event.type != ElementType.PLAYER_LIST) return;
    ...
    event.setCanceled(true);          // then draw our own
}
```

Weave's `RenderGameOverlayEvent` has **no** `ElementType` — it covers the whole overlay, so
cancelling it would remove the entire HUD. The port therefore replaces the overlay
*object* instead, which is also what Seraph (the other Weave stats mod for Lunar 1.8.9)
does:

1. A `TickEvent.Post` handler writes our `StatsTab` instance into
   `GuiIngame.overlayPlayerList` via reflection, keeping the original in
   `originalOverlay`, and copies the current header/footer across.
2. `StatsTab extends GuiPlayerTabOverlay`, so vanilla `GuiIngame.renderPlayerList` calls
   `renderPlayerlist(width, scoreboard, objective)` **on our object** every frame.
3. Our override delegates to `GameOverlayListener.renderTab(...)`, which resolves the
   gamemode from the scoreboard sidebar, fetches the cached `HPlayer` stats, computes its
   own width and calls `renderNewPlayerlist(...)`. It returns `false` when the mod is
   disabled, and the override then calls `super` — the vanilla list.
4. Disabling the mod restores `originalOverlay` from the same tick handler.

Deliberately **no Mixin**: fewer moving parts, no mixin config or access widener, and it
keeps working if Lunar ever subclasses `GuiIngame`, since the reflection walks the
hierarchy. `setAccessible(true)` is enough even though the field is `final` — the JVM
permits writing non-static final fields through reflection once access is granted.

Note the width argument from vanilla is ignored; the mod computes a wider one to fit the
stat columns, exactly as the Forge build did.

### Which stat columns are drawn

The game classes (`Bedwars`, `Skywars`, `Duels`) always build their full stat list. What the
tab actually shows is decided one step later by `StatColumnLayout`, which reorders that list
and drops the columns switched off in `/tabstats` -> *Stat Columns...*. It has to be applied in
**both** places that touch stats, or headers and values drift apart:

- `GameOverlayListener.renderTab` — the local player's list, which supplies the header labels
  and the column widths;
- `StatsTab.resolveStats` — every other row.

Columns are matched by stat name, trimmed and upper-cased, because DUELS pads `"TITLE"` with
trailing spaces to widen its column. A stat the layout has no entry for is appended rather than
dropped, so a column added in a later version shows up instead of silently disappearing. The
order is stored per gamemode under `StatColumns` in `config.json`.

### Players a pre-game lobby hides

A pre-game lobby gives away nobody: no usable tab entry, no named entity. The one moment a
name becomes known is when that player writes something, so `ChatListener` feeds
`ChatEvent.Received` through `ChatNameParser` and hands the name to
`StatWorld.revealFromChat`, which:

1. resolves the name to a UUID against `api.mojang.com` — the Hypixel v2 player endpoint
   takes UUIDs only, and a chat line carries a name. A 404 there means no such account,
   which on Hypixel means a nick: the player is kept under a locally derived placeholder
   UUID and drawn as `[NICKED]`, and no skin is requested for it.
2. runs the normal stat fetch, so those players end up in the same cache as everyone else.

`StatsTab.appendChatRevealed` then makes up a `NetworkPlayerInfo` per revealed player and
appends it below the real entries, skipping anyone the server does list. Those rows are
drawn from the API rank instead of a team prefix (they have no team) and get no scoreboard
column (they are not on the scoreboard).

Only a player writing something reveals them. A join line names one too, but Hypixel
anonymises it — a real lobby logs `c8oxqbN5pHN has joined (13/16)!` — so joining reveals
nobody. Party, guild and private messages are rejected as well, since their sender need not
be in this lobby; so are the player's own messages and anyone the server already lists in the
tab list, there being nothing left to uncover there. A reveal then only happens while the
scoreboard sidebar names a supported game, the same condition under which stat columns appear
at all.

What a player line is cannot be read off the colours. Hypixel writes a rankless player as
`§7Name§7: message` and a server label as `§eStore: §b…` — the colon is coloured in both, and
an earlier version of this that insisted on the `§f: ` of a ranked player's line silently
revealed nobody. So the shape carries it: everything before the first `": "`, with bracketed
tags stripped, has to collapse to exactly one token that is a valid username and not one of
the labels in `NON_PLAYER_LABELS`. A stray label that is not on that list costs one bogus row
and two API calls, which is the right way round — the failure that matters is revealing
nobody.

Reveals are retired three ways, because a pre-game lobby does not always sit on its own
world and the game starting is therefore not always a world change:

- `StatsTab.appendChatRevealed` drops a player the moment the server lists them in the tab
  list itself, which is exactly what happens when the game starts. Their own entry, drawn
  with the server's formatting, takes over.
- `ChatListener` clears the lot on the `▬▬▬…` bar Hypixel draws around a game's start and end
  summary, which also catches players who never made it into this game.
- a world change clears the lot as well, for when the lobby really is left behind.

A quit line drops that one name again.

## 6. Lifecycle: the one real trap

Weave calls `ModInitializer.init()` from the **head of `Minecraft.main`**. At that moment
`Minecraft.getMinecraft()` still returns `null` and `mc.ingameGUI` does not exist. This
mod was full of code that assumed otherwise:

- `GameOverlayListener`, `WorldLoader` and `InputListener` each had
  `private final Minecraft mc = Minecraft.getMinecraft();` as a **field initialiser**
- `GameOverlayListener`'s constructor calls `new StatsTab(this.mc, this.mc.ingameGUI)`
- `ModConfig.getFile()` reads `mc.mcDataDir`, and *caches* the result — called too early it
  would silently and permanently fall back to `~/.minecraft/tabstats`

Constructing any of it from `init()` therefore NPEs, or worse, quietly writes the config to
the wrong place. So `init()` only subscribes, and everything real happens on
**`StartGameEvent.Post`**, which fires at the tail of `Minecraft.startGame()` and is the
true equivalent of Forge's `FMLInitializationEvent`:

```java
@Override
public void init() {
    tabStats = this;
    EventBus.subscribe(this);      // nothing game-related yet
}

@SubscribeEvent
public void onStartGame(StartGameEvent.Post event) {
    ModConfig.getInstance().init();
    this.statWorld = new WorldLoader();
    this.gameOverlayListener = new GameOverlayListener();
    this.registerListeners(statWorld, gameOverlayListener,
                           new GuiOpenListener(), new InputListener());
    this.applyModEnabled(ModConfig.getInstance().isModEnabled());
    CommandBus.register(new TabStatsCommand());
}
```

**If you add a feature, put anything touching Minecraft behind this event, or later.**
This is the most likely source of future NPEs in this codebase.

## 7. Build environment

- **Gradle 9.4.0**, run on **JDK 17**
- No Java 8 toolchain is provisioned. `options.release = 8` emits Java 8 bytecode against
  the Java 8 API using the JDK 17 compiler. The reason is a hard incompatibility: the
  `foojay-resolver-convention` version that would download a JDK 8 references
  `JvmVendorSpec.IBM_SEMERU`, which Gradle 9 removed, and fails with
  `NoSuchFieldError: IBM_SEMERU`. Either drop the toolchain (done here) or use a
  Gradle-9-compatible foojay release.
- Weave artifacts come from `https://gitlab.com/api/v4/projects/80566527/packages/maven`,
  which must be listed in **both** `settings.gradle.kts` (`pluginManagement`) and
  `build.gradle.kts` (`repositories`).
- `throws IOException`: in the mcp-named jar the plugin remaps for compilation,
  `GuiScreen.keyTyped` and `GuiScreen.mouseClicked` do **not** declare `throws IOException`,
  while MCP's own sources do. Three overrides had to drop the clause. This is safe in both
  directions, because `throws` is not part of the JVM method descriptor — the override still
  overrides whatever the runtime class declares.
- Runtime dependencies that are **not** shaded: Gson, Apache HttpClient, commons-lang3,
  Guava and LWJGL. All are vanilla 1.8.9 libraries and verified present on Lunar's runtime
  classpath — no `NoClassDefFoundError` appears in the game log. If a future Lunar build
  trims them, shade with relocation.

## 8. Where things live

| Path | What |
|---|---|
| `~/.weave/mods/1.8.9/*.jar` | installed mods, version-specific |
| `~/.weave/mods/*.jar` | installed mods, **all** versions |
| `~/.weave/logs/latest.log` | Weave's own log — first place to look |
| `~/.weave/.cache/mappings/` | generated Tiny v2 mappings |
| `~/.weave/.cache/jars/` | remapped mod and vanilla jars |
| `~/.weave/.maven-repository/` | event API fetched at runtime |
| `~/.lunarclient/logs/launcher/main.log` | launcher log, incl. `* Custom JVM Arguments:` |
| `~/.lunarclient/profiles/1.8/logs/latest.log` | the game log |
| `~/.lunarclient/profiles/1.8/logs/config.xml` | log4j config — **regenerated every launch** |
| `~/.lunarclient/db/profiles.db` | SQLite; per-profile `jvm_arguments` column |
| `~/.lunarclient/settings/launcher.json` | `settings.jvmArgs` (global), `settings.advancedMode` |
| `%APPDATA%/.minecraft/tabstats/config.json` | mod settings and API keys, plaintext |
| `%APPDATA%/.minecraft/versions/1.8.9/1.8.9.jar` | vanilla jar Weave needs for mappings |

## 9. Debugging

**Triage in order.** Each step tells you which layer failed:

| Symptom | Look at | Meaning |
|---|---|---|
| no `~/.weave/logs/latest.log` at all | `main.log` → `* Custom JVM Arguments:` shows `N/A` | the launcher never passed the agent |
| `Attached Weave` but `Discovered 0 total mod files` | `~/.weave/mods/1.8.9/` | jar in the wrong folder, or not a `.jar` |
| `Could not find vanilla jar for version 1.8.9` | `%APPDATA%/.minecraft/versions/1.8.9/` | missing vanilla jar; or set `-Dweave.vanilla.jar.path=` |
| Weave initialises, `/tabstats` unknown | game log for a `tabstats` stacktrace | entrypoint threw; most likely a too-early Minecraft access (§6) |
| `/tabstats` works, Tab unchanged | whether `ensureCustomOverlayInjected` succeeded | the overlay field was not found or not written — check `Reflect.field` names |
| Tab renders, all stats empty | `api.hypixel.net` reachable, key valid | API layer, not the loader |
| `NoClassDefFoundError: com/google/gson/...` | §7 | a runtime library vanished from Lunar's classpath |

**Useful system properties**, all settable in the same JVM Arguments field:

```
-Dweave.mods.directory=<dir>          # override the mods folder
-Dweave.mods.override=<jar>[;<jar>]   # bypass discovery entirely
-Dweave.vanilla.jar.path=<jar>        # if the vanilla jar is elsewhere
-Dweave.dump.bytecode.directory=<dir> # dump transformed classes
```

**Getting FML/Lunar debug output** — relevant only if you go back to investigating the
Forge path. Lunar passes `-Dlog4j.configurationFile=<profileLogs>/config.xml` and
**rewrites that file on every launch**, so editing it in place is useless. Write your own
copy elsewhere with `<Root level="debug">`, or a targeted
`<Logger name="FML" level="trace"/>`, and override the property in the JVM Arguments field;
a later `-D` on the command line wins. Lunar's Forge module also honours
`-Dichor.forgeDebugMappings=true` and `-Dichor.forgeDebugBinpatch=true`.

**Reproducing the Lunar analysis without a JDK.** Lunar bundles its own JRE 17, which is
enough to run a decompiler:

```bash
JAVA=~/.lunarclient/jre/*/bin/java.exe
curl -sSLO https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar
"$JAVA" -jar cfr-0.152.jar <extracted-class-file> --comments false
```

Lunar's own classes are ProGuard-obfuscated into names built from the letters I, C, H, O, R
— but **string constants survive**, so `grep`-ing extracted class files for readable
strings (`Loading mod `, `mcmod.info`, `ichor.`) locates the interesting classes fast,
before decompiling anything.

## 10. Known risks

- **Lunar updates.** Low risk by construction: the port hooks only `net.minecraft.*`,
  which Lunar keeps deobfuscated and stable. Real risk lies in Genesis/Ichor or
  classloader changes, which would be Weave's problem to fix, not this mod's.
- **Hypixel API auth.** `HypixelAPI` calls
  `https://api.hypixel.net/v2/player?key=%s&uuid=%s`. Query-parameter auth is deprecated
  in favour of an `API-Key` header; it works today and is the most likely future breakage.
  One-line fix in `playerapi/api/HypixelAPI.java`.
- **`jvm_arguments` lives in SQLite.** A launcher schema migration, or deleting and
  recreating the profile, drops the setting. It is then re-entered under
  Settings → Game → JVM Arguments (needs Advanced Mode).
- **Global vs per-profile JVM args.** Set globally, Weave attaches to *every* Lunar
  version, including modern ones where it will look for mappings it does not need. Use the
  profile field.
- **Terms of Service.** Injecting into Lunar Client conflicts with Lunar's ToS; Solar
  Tweaks was shut down in 2023 after a cease-and-desist from Moonsworth. Hypixel permits
  API-based stat mods. Weave additionally hides `-javaagent` from
  `RuntimeMXBean.getInputArguments()` (§2) — know that before you rely on this setup.
- **API keys are stored in plaintext** in `config.json`, as upstream does.

## 11. Versions this was verified against

| Component | Version |
|---|---|
| Lunar Client | 1.8.9, module `lunar`, launcher 3.7.17-ow, Ichor/Genesis, OptiFine 1.8.9 HD U M6_pre2 |
| Lunar's JRE | Zulu 17.0.18 |
| Weave Loader | 1.4.0 (agent), `net.weavemc.api:api-v1_8:1.4.0` |
| Weave Gradle plugin | 1.4.0 |
| TabStats upstream | 1.3.0 (`451c5b6`) |
| Build JDK / Gradle | Temurin 17.0.20.1 / Gradle 9.4.0 |
| Decompiler used | CFR 0.152 |
| Date | 2026-09-10 |
