# Protector

**Protector** is a modular server protection, forensic audit logging, rollback engine, and death-drop protection plugin
for Paper (`1.20.4–26.3+`) and Folia.

## Key Features

### 1. World & Explosion Guard (`GuardModule`)

- **TNT & Explosives Control**:
    - `tnt.prevent-prime`: Block flint-and-steel / redstone TNT priming (bypassable with `protector.bypass.guard`).
    - `tnt.prevent-block-damage` / `tnt.prevent-entity-damage` / `tnt.prevent-minecart-tnt`: Fine-grained control over
      TNT and TNT minecart damage.
    - `explosions.prevent-creeper-block-damage`, `explosions.prevent-wither-damage`,
      `explosions.prevent-fireball-block-damage`, `explosions.prevent-end-crystal-damage`,
      `explosions.prevent-bed-anchor-explode`: Independent explosion toggles.
- **Fire & Mob Griefing**:
    - `fire.prevent-fire-spread`, `fire.prevent-block-burn`, `fire.prevent-lava-fire-spread`,
      `fire.prevent-lightning-fire`.
    - `mobs.prevent-enderman-grief`, `mobs.prevent-ravager-grief`, `mobs.prevent-wither-block-break`.
    - `world.prevent-non-player-portals`: Prevent non-player entity portal creation.
- **Death-Drop Protection (`protect-death-drops`)**:
    - Protects player death drops from pickup by other players until unlocked via `/protector unlockdrops`
      (`/unlockdrops`).
- **Interactive Configuration UI**:
    - `/protector guard` opens an interactive Paper Dialog (`1.21.6+`) or Inventory GUI (`1.20.4+`) to inspect and
      toggle guard rules live.

### 2. Forensic Audit & Rollback (`AuditModule`)

- **Async SQLite Audit Pipeline**:
    - Batched background queue (`LogQueueConsumer`) backed by HikariCP + SQLite (`protector.db`, `pt_*` tables).
    - Logs blocks, containers, hopper transfers, natural events, interactions, entities (kills/spawns), signs, chat,
      commands, and sessions.
- **Inspector Mode**:
    - `/protector inspect` (`/inspect`, `/i`, `/coi`) toggles block/container inspection mode with live ActionBar &
      BossBar HUD indicators; `/protector near` inspects nearby changes.
- **CoreProtect-Style Filter & Query Engine**:
    - Supports multi-user & exclude-user (`u:Alice,Bob`, `-u:Bot`), time windows & ranges (`t:1h30m`, `t:2d-1h`,
      `t:1mo`), radius & global scope (`r:20`, `r:#global`, `r:<world>`), action filters
      (`a:block|+block|-block|container|+container|-container|entity|sign|chat|session`), include/exclude materials
      (`i:`, `e:`), text filters (`f:`), `#preview` (`#p`), and `#count` (`#c`).
- **Time-Sliced Rollback, Preview & Restore**:
    - `/protector rollback`, `/protector apply`, `/protector cancel`, `/protector restore`, `/protector undo`,
      `/protector purge`, `/protector consumer`.
    - Packet-level client block previews via `player.sendBlockChange` before committing changes, with
      block/container/entity restoration and undo history.

### 3. Multilingual & Hybrid UI

- Supports **English (`en`)** and **Korean (`ko`)** out of the box (`messages_en.yml`, `messages_ko.yml`).
- Uses Paper Dialog API (`1.21.6+`) with seamless Chest Inventory GUI fallback (`1.20.4–1.21.5`) via `PlatformCompat`.

## Commands & Permissions

Root command: `/protector` (aliases: `/pr`, `/pt`). Standalone subcommand aliases are registered dynamically when
`command-aliases.register-standalone-aliases: true`.

| Command | Alias(es) | Description | Permission |
| --- | --- | --- | --- |
| `/protector` | `/pr`, `/pt` | Open the Protector dashboard GUI | `protector.use` |
| `/protector guard` | `/tntguard`, `/worldguard` | Open the world/TNT/fire/mob guard GUI | `protector.guard` |
| `/protector unlockdrops` | `/unlockdrops` | Unlock your protected death drops | `protector.unlockdrops` |
| `/protector inspect` | `/i`, `/coi` | Toggle audit inspector mode | `protector.inspect` |
| `/protector near` | — | Inspect audit logs within 5 blocks | `protector.inspect` |
| `/protector lookup <filters>` | `/l` | Search audit history (`#count`, pagination) | `protector.lookup` |
| `/protector rollback <filters>` | `/rb` | Rollback matching logs (supports `#preview`) | `protector.rollback` |
| `/protector apply` / `cancel` | — | Confirm or cancel active rollback preview | `protector.rollback` |
| `/protector restore <filters>` | `/rs` | Re-apply rolled-back logs | `protector.restore` |
| `/protector undo` | — | Undo the most recent rollback/restore | `protector.restore` |
| `/protector purge <time>` | — | Purge logs older than `<time>` | `protector.purge` |
| `/protector consumer <pause\|resume\|status\|flush>` | — | Control or flush the async log queue | `protector.consumer` |
| `/protector status` / `reload` / `alias` | — | Diagnostics, reload config, manage aliases | `protector.admin` |

## Building

Requires **Java 25** (to run Gradle/Paper tooling; compiles Java 17 compatible bytecode).

```bash
./gradlew clean test shadowJar
```

Output JAR: `build/libs/Protector-1.0.0-SNAPSHOT.jar` (all runtime dependencies relocated under `protector.libs.*`).
