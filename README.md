# Offline Whitelist Login

Offline Whitelist Login is a production-ready Fabric server mod for Minecraft 1.21.1 that replaces the vanilla whitelist flow with a secure register/login process. It is designed for always-online servers that need to keep unofficial players out even when Mojang authentication is unavailable.

## Features

- Username-based whitelist that works whether or not the vanilla whitelist is enabled.
- Secure registration flow that stores BCrypt hashed passwords in `config/owl.json` and automatically maintains a backup at `config/owl_backup.json`.
- Players join in spectator mode, cannot move, and have all commands blocked except `/register` and `/login` until they authenticate.
- Configurable timeout, password policy (default minimum length 5), and failed attempt limit (default 5 tries) stored in `config/owl.json`.
- Automatic enforcement of login timeouts and failure limits with logging before kicking suspicious users.
- Console logging for every registration, login and failed login attempt.
- Minute-by-minute verification that every connected player is still whitelisted.
- Permission-gated management commands (`owl.add` defaults to allowed, `owl.remove` defaults to denied) with automatic kicking and data cleanup when removing a user, plus operator-only fallbacks when the Fabric Permissions API is unavailable.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/register <password>` | Registers a password for the current player and logs them in. | Player only |
| `/login <password>` | Logs in using the stored password hash. | Player only |
| `/owl add <username>` | Adds a username to the whitelist data file so they can register. | `owl.add` (default allow) |
| `/owl remove <username>` | Removes a username from the whitelist, deletes their credentials and kicks them if online. | `owl.remove` (default deny) |

All other commands are blocked for unauthenticated players by a command dispatcher guard.

## Data format

User data and runtime settings are stored in `config/owl.json` using the following structure:

```json
{
  "config": {
    "minPasswordLength": 5,
    "maxLoginAttempts": 5,
    "loginTimeoutSeconds": 300
  },
  "users": [
    "PlayerOne:$2a$12$...",
    "PlayerTwo:"
  ]
}
```

- Credentials are serialized as a single string per user (`<username>:<bcrypt-hash>`). Unregistered users appear with an empty hash (e.g., `PlayerTwo:`).
- Runtime settings clamp to safe minimums (password length ≥ 1, login attempts ≥ 1, login timeout ≥ 10 seconds) even if lower values are configured.
- Every write produces an updated backup file (`config/owl_backup.json`). If the primary file becomes unreadable, the backup is restored automatically on the next server start.

## Building

1. Install JDK 21 or newer.
2. Run `./gradlew build` (the first build downloads the toolchain and may take a few minutes).
3. The distributable jar will be located in `build/libs/offline-whitelist-login-<version>.jar`.

## Server configuration tips

- Keep the vanilla whitelist disabled or enabled—this mod enforces its own list regardless.
- Distribute initial whitelist access through `/owl add <username>` before players join.
- Encourage players to use strong passwords; BCrypt with a work factor of 12 is applied automatically.
- Monitor the console for registration/login events to audit player activity.
