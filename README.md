# Protector

**Protector** is an all-in-one **land protection, forensic audit logging, and rollback** plugin for **Paper 26.3+**,
written in **Kotlin**.
Inspired by **GriefPrevention** and **CoreProtect**, it unifies preventive territory claims with high-throughput
asynchronous action logging and tick-throttled rollbacks—while showcasing modern Paper UI primitives (**ActionBar**,
**BossBar**, **Native Paper Dialogs**, and **Inventory GUI Menus**).

## Key Features

### 1. Preventive Land Protection

- **Golden Shovel & Stick Tools**: Right-click corners to create, resize, or subdivide claims; inspect boundaries with
  client-side fake block & particle visualization.
- **Hierarchical Trust Tiers**: `Access` < `Container` < `Build` < `Manage` < `Owner`, plus `public` trust and
  per-subclaim `inheritNothing` isolation.
- **Per-Claim Flags**: `PVP`, `EXPLOSIONS`, `FIRE_SPREAD`, `MOB_GRIEFING`, `PUBLIC_ACCESS`, `PISTON_CROSS_BORDER`,
  `ENTRY_NOTIFY`, plus custom entry greetings.
- **Comprehensive Grief Guards**:
    - Blocks, containers, beds, lecterns, armor stands, item frames/paintings, vehicles, and farmland trampling.
    - Cross-border liquid flow, piston push/retract, dispenser placement/liquids, hopper item extraction, and
      tree/structure growth.
    - Portal creation protection & Nether portal trap auto-rescue, fishing rod & harmful splash potion protection, and
      Frost Walker / snow golem block formation guards.
    - **Death Drop Protection**: Non-PvP death drops are tagged and locked against theft and hoppers until unlocked via
      `/unlockdrops`.
- **Claim Block Economy**: Initial blocks, AFK-aware hourly accrual, bonus adjustments, abandonment refunds, and
  optional unused-claim cleanup.

### 2. Forensic Audit Logging & Rollback

- **Asynchronous Batch Pipeline**: Event listeners enqueue lightweight records into a lock-free `ConcurrentLinkedQueue`
  flushed in SQLite WAL transactions.
- **Normalized Schema**: ID mapping cache for users, worlds, materials, entities, and block data (`pt_users`,
  `pt_worlds`, `pt_materials`, `pt_entities`, `pt_blockdata`).
- **Full Event Coverage**:
    - Block place/break, explosions (with `TNTPrimeEvent` player attribution), fire burn, liquid flow, structure growth,
      leaf decay, block fade/form/fertilize, falling/entity block changes, and block interactions.
    - Container inventory open/close snapshot diffs, hopper transfers (`#hopper`), lectern book removal, and broken
      container contents.
    - Sign front/back text snapshots, entity kills (capturing custom name, age, sheep color, villager profession),
      hanging place/break, armor stand manipulation, chat, commands, and login/logout sessions.
- **Preview, Rollback, Restore & Undo**:
    - Client-side preview rendering (`sendBlockChange`) before committing via `/pr apply` or `/pr cancel`.
    - Tick-throttled block restoration, **automatic sign text restoration**, **container item rollback**, **non-hostile
      entity respawning**, `/pr undo`, and `/pr purge`.

### 3. Modern Paper UI Layer (1.20.4 – 26.3+ Backward Compatible)

- **ActionBar HUD**: Real-time Wilderness vs. Claim ownership/permission badge, live Golden Shovel selection
  dimensions & available claim blocks, Inspector mode prompt, death-drop lock reminder, and cooldown-throttled denial
  alerts.
- **BossBar**: Live percentage progress bars for rollback/restore/purge operations, active preview/resize/subclaim mode
  banners, and an optional persistent territory status banner.
- **Paper Dialogs (`io.papermc.paper.dialog.Dialog`) on 1.21.6+ / 26.x — with Automatic GUI Fallback on 1.20.4 – 1.21.5**:
    - Claim Settings & Flags Form (`BooleanDialogInput` + `TextDialogInput`, falls back to Flag Toggle GUI)
    - Add Trust & Transfer Ownership Forms (falls back to Player-Head Selector GUI)
    - Audit Lookup & Rollback Filter Form (time preset, `NumberRangeDialogInput` radius slider, action scope, falls back to Rollback Preset GUI)
    - Inspector Detail Dialog (item icon, timeline, one-click rollback/teleport, falls back to clickable MiniMessage chat)
    - Confirmation Modals (`DialogType.confirmation`, falls back to Confirm/Cancel GUI)
- **GUI Inventory Menus**:
    - Dashboard (`/pr`), My Claims Browser, Claim Detail, Subclaims Manager, Player-Head Trust Matrix (click to cycle
      tiers), Admin Control Panel, and Tabbed Audit Log Browser
      (`All | Blocks | Containers | Entities | Signs | Chat/Cmd | Sessions`).

## Requirements

- **Server**: Paper `1.20.4` – `26.3+` or Folia (`folia-supported: true`; auto-detects Paper Dialogs on `1.21.6+` and falls back to Inventory GUIs on older versions; also compatible with Spigot chat events)
- **Java**: Java `17+` (compiled to Java 17 bytecode)

## Installation

1. Build the shaded plugin JAR (or download a release):
   ```bash
   gradle shadowJar
   ```
2. Copy `build/libs/Protector-1.0.0-SNAPSHOT.jar` into your server's `plugins/` folder.
3. Start the server to generate `plugins/Protector/config.yml`, `command-aliases.yml`, `aliases.yml`,
   `messages_en.yml`, `messages_ko.yml`, and `protector.db`.

## Commands & Dynamic Aliases

Protector registers `/protector` (aliases `/pr`, `/pt`) as its single `plugin.yml` root command and exposes standalone
commands (`/claim`, `/inspect`, `/rollback`, `/trust`, etc.) plus user-defined shortcuts through a **CommandMap-backed
dynamic alias system** (`command-aliases.yml` and `aliases.yml`).

### Built-in Commands & Subcommands

| Command | Standalone Aliases | Description | Permission |
| --- | --- | --- | --- |
| `/protector` | `/pr`, `/pt` | Open the main Protector Dashboard GUI | `protector.use` |
| `/pr claims` | — | Open your Claims Browser GUI | `protector.claim` |
| `/pr settings` | — | Open the Paper Dialog for the current claim/subclaim | `protector.claim` |
| `/pr admin` | — | Open the Admin Control Panel GUI | `protector.admin` |
| `/pr claim [radius]` | `/claim` | Create a claim centered on your position | `protector.claim` |
| `/pr extend <blocks>` | `/extendclaim`, `/expandclaim` | Extend or shrink the current claim in your facing direction | `protector.claim` |
| `/pr transfer [player]` | `/transferclaim` | Transfer claim ownership (opens Dialog if no player given) | `protector.claim` |
| `/pr restrict` | `/restrictsubclaim`, `/rsc` | Toggle `inheritNothing` isolation on a subclaim | `protector.claim` |
| `/pr abandon` | `/abandonclaim`, `/unclaim` | Abandon the current claim (with confirmation Dialog) | `protector.claim` |
| `/pr abandonall` | `/abandonallclaims` | Abandon all your claims (with confirmation Dialog) | `protector.claim` |
| `/pr trust [player] [tier]` | `/trust` | Grant `access`, `container`, `build`, or `manage` trust | `protector.claim` |
| `/pr untrust [player]` | `/untrust` | Remove trust or open the Trust Matrix GUI | `protector.claim` |
| `/pr unlockdrops` | `/unlockdrops` | Unlock your protected death drops | `protector.claim` |
| `/pr trapped` | `/trapped` | Escape to nearby wilderness if trapped in another's claim | `protector.claim` |
| `/pr inspect` | `/inspect`, `/coi` | Toggle Inspector mode (Shift+Click opens Inspector Dialog) | `protector.inspect` |
| `/pr near [radius]` | — | Open the tabbed Audit Browser GUI around you | `protector.lookup` |
| `/pr lookup [filters]` | `/pr l` | Open filter Dialog or query logs (`u:`, `t:`, `r:`, `a:`, `i:`, `e:`) | `protector.lookup` |
| `/pr rollback [filters]` | `/rollback`, `/rb`, `/pr rb` | Open Rollback Filter Dialog or start preview | `protector.rollback` |
| `/pr restore [filters]` | `/restore`, `/pr rs` | Open Restore Filter Dialog or start restore preview | `protector.restore` |
| `/pr apply` / `/pr cancel` | — | Apply or cancel an active rollback/restore preview | `protector.rollback` |
| `/pr undo` | — | Undo your last executed rollback or restore | `protector.rollback` |
| `/pr purge <duration>` | — | Purge audit logs older than `<duration>` (e.g., `30d`) | `protector.purge` |
| `/pr adjustblocks <player> <amount>` | `/adjustclaimblocks`, `/acb` | Adjust a player's bonus claim blocks (or open Dialog) | `protector.admin` |
| `/pr alias add\|remove\|list` | `/pr aliases` | Create, remove, or list dynamic custom aliases at runtime | `protector.admin` |
| `/pr status` / `/pr reload` | — | View claim/queue stats or reload configs and aliases | `protector.admin` |

### Dynamic Aliases (`command-aliases.yml` & `aliases.yml`)

- **`command-aliases.yml`** controls every built-in standalone shortcut (`base` and `subbase` aliases):
  - Set `Enabled: false` to disable a standalone shortcut such as `/coi` or `/claim`.
  - Set `BaseDisabled: true` on a built-in command to block `/protector <subcommand>` directly while routing through custom aliases.
- **`aliases.yml`** lets admins define custom aliases (either via file or `/pr alias add <alias> <command...>`):
  - Supports positional placeholders (`$1`, `$2`, `$1-`, `{args}`, `{player}`), `?` suffix wildcards, `Exact`, `RequiresPermission` (`protector.command.custom.<key>`), `AddTabs` / `CustomTabs`, and specialized action prefixes (`console:`, `player:`, `op:`, `msg:`, `broadcast:`, `actionbar:`, `bossbar:`, `sound:`, `delay:`).

### Lookup & Rollback Filter Syntax

Filters can be combined on the command line or configured visually in the Rollback Dialog:

- `u:<player>` — Filter by actor name (e.g., `u:Steve`)
- `t:<duration>` — Time window (e.g., `t:30m`, `t:2h`, `t:7d`)
- `r:<radius>` — Radius in blocks around your position (e.g., `r:20`)
- `a:<scope>` — Scope: `block`, `container`, `entity`, `sign`, `chat`, `session`
- `i:<mat1,mat2>` / `e:<mat1,mat2>` — Include or exclude materials (e.g., `i:DIAMOND_ORE e:STONE`)
- `apply` / `now` — Skip preview and execute immediately

## Configuration & Localization

Edit `plugins/Protector/config.yml` to configure:

- **`language`**: `en` (default) or `ko` (`messages_en.yml`, `messages_ko.yml`)
- **`command-aliases.register-standalone-aliases`**: enable/disable registering built-in standalone aliases onto Bukkit's `CommandMap`
- **`modules`**: toggle `modules.claim.enabled` and `modules.audit.enabled` independently
- **`claims`**: claim tools, initial/accrued/max claim blocks, min dimensions, death-drop protection, tree growth
  limits, unused-claim cleanup, and default claim flags
- **`audit`**: toggles for blocks, containers, hoppers, natural changes, interactions, entities, signs, chat, commands,
  sessions, container/entity rollback, and tick throttling
- **`ui`**: ActionBar refresh rate & denial cooldown, BossBar progress & territory banners

## Building & Testing

```bash
# Run unit/integration tests and build the shaded plugin JAR
gradle clean test shadowJar
```

- Output JAR: `build/libs/Protector-1.0.0-SNAPSHOT.jar`
- Main Class: `protector.Protector`
