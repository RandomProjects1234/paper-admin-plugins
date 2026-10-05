# Paper Admin Plugins

Three small, self-contained admin plugins for a Paper Minecraft server. Built and tested against
**Paper 26.3** (Java 25). No external dependencies, no database, nothing to configure to get going.

| Plugin | What it does |
| --- | --- |
| **AutoBackup** | Zips the server only on demand with `/savebackup`, and prunes old archives |
| **Offend** | Temporary bans measured in minutes |
| **InvSee** | Opens another player's inventory or ender chest as a live view |

---

## AutoBackup

Backs the server up only when you run `/savebackup`. No automatic backups are scheduled.

When upgrading, replace the old AutoBackup jar and restart the server. Any `interval-minutes`
setting left in an existing config is ignored and can be removed.

Archives land in `backups/` as `backup-YYYY-MM-DD_HH-mm-ss.zip`.

**What goes in the archive:** every world folder, the whole `plugins/` directory, the `config/`
directory, and the server's own config files (`server.properties`, `ops.json`, `whitelist.json`,
`banned-players.json`, and friends).

**What deliberately doesn't:** the server jar, `cache/`, `libraries/`, `versions/`, `logs/`, and the
`backups/` folder itself. Those are large and re-downloadable, and including `backups/` would make
each archive contain every previous one.

Auto-save is switched off for the duration of the zip and restored afterwards, so region files
aren't being rewritten while they're read.

| Command | Permission | Default |
| --- | --- | --- |
| `/savebackup` | `autobackup.savebackup` | op |

```yaml
# config.yml
keep-backups: 48          # oldest archives beyond this are deleted
include-plugin-jars: true # configs in plugins/ are always included either way
```

---

## Offend

Temporary bans, in minutes.

```
/offend test123 120 being rude
```

> Offended test123 for 2h (until 2026-09-19 17:06)
> Reason: being rude

The player is kicked immediately if they're online, and blocked at login until the ban expires -
the kick screen shows the reason, the time remaining and the exact expiry.

Bans live in the plugin's own `bans.yml`, not the vanilla ban list. That means you can ban someone
who has **never joined the server** (they're matched by name, and pinned to their UUID the first
time they try to connect, so a name change won't shake it off). Expiry is enforced on login, so
there's no unban task to miss.

| Command | Description |
| --- | --- |
| `/offend <player> <minutes> [reason]` | Ban for a number of minutes |
| `/unoffend <player>` | Lift a ban early |
| `/offends` | List everyone currently banned, with time remaining |

| Permission | Default | |
| --- | --- | --- |
| `offend.use` | op | Use the three commands above |
| `offend.exempt` | false | Holder cannot be offended |

---

## InvSee

```
/invsee <player>
/endersee <player>
```

Opens the target's real inventory, so anything you move takes effect immediately. Editing is gated
behind `invsee.edit`; without it the view is look-only. Set `read-only: true` in `config.yml` to
force that for everyone, including ops.

If the player you're watching logs out, your view closes rather than going stale.

Online players only - reading an offline player's inventory means parsing their player data file,
which this deliberately doesn't do.

| Permission | Default | |
| --- | --- | --- |
| `invsee.use` | op | Open a view |
| `invsee.edit` | op | Move items in a view |

---

## Building

Requires JDK 25 and Maven.

```bash
mvn clean package
```

Three jars come out of `*/target/`:

```
autobackup/target/AutoBackup-1.0.1.jar
offend/target/Offend-1.0.1.jar
invsee/target/InvSee-1.0.1.jar
```

Drop whichever ones you want into `plugins/` and restart. They're independent - none of them needs
the others.

## Licence

MIT - see [LICENSE](LICENSE).
