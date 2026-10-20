# Deploy on Windows

Your Windows PC can run all of lumnik — the hub, the database, the login server,
even the AI. This page walks you there step by step: each one says **where to
type, what to type, and what you should see** before moving on.

Total time: about an hour, most of it downloads. You need Windows 10 (version
21H2 or later) or Windows 11, and around 20 GB of free disk.

## The two windows you will type into

Everything below happens in one of two command windows — worth telling apart
before starting:

- **PowerShell** — Windows' own command window. Open it from the Start menu:
  type `powershell`, click the result. You'll use it for Windows-side things
  (installing tools, running `lm.exe`).
- **Ubuntu** — a small Linux that lives inside Windows (installed in step 1).
  Open it from the Start menu: type `ubuntu`. lumnik itself runs here.

!!! tip "Three habits that save an hour"
    - **Paste with a right-click.** In these windows Ctrl+V often does nothing;
      copy the command here, then right-click inside the window — it pastes.
    - **One command, then Enter, then wait.** The command is done when the
      *prompt* (the line ending in `>` or `$`) comes back.
    - **Passwords are typed blind.** When Ubuntu asks for a password, nothing
      appears while you type — no dots, no stars. That's normal: type it and
      press Enter.

## Step 1 — Install WSL2, the Linux inside Windows (~10 min, one restart)

1. Open PowerShell **as administrator**: Start menu → type `powershell` →
   right-click the result → **Run as administrator** → answer *Yes*.
2. Type:
   ```
   wsl --install
   ```
   Windows downloads WSL2 and Ubuntu. When it asks, **restart the PC**.
3. After the restart, an Ubuntu window opens on its own (if not: Start menu →
   `ubuntu`). It asks you to pick a **username** and a **password** — this is a
   new, Linux-side account, not your Windows one. Remember the password: Ubuntu
   asks for it whenever a command starts with `sudo`.
4. Check it worked — in a normal PowerShell window:
   ```
   wsl --status
   ```
   You should see `Default Version: 2`.

!!! warning "It complains about virtualization or the BIOS?"
    Your PC's virtualization switch is off. Check: Ctrl+Shift+Esc →
    Performance → CPU → look for *Virtualization: Enabled*. If it says
    Disabled, it must be enabled in the BIOS (usually F2/Del at power-on,
    then an option named VT-x, SVM or "Virtualization Technology").
    A search for "*enable virtualization* + your PC model" gives the exact keys.

## Step 2 — Install Docker Desktop (~10 min)

Docker runs lumnik's services in containers — you never have to install Java, a
database, or anything else by hand.

1. Download **Docker Desktop for Windows** from
   [docker.com](https://www.docker.com/products/docker-desktop/) and run the
   installer. Keep every default. Restart if it asks.
2. Open Docker Desktop. It suggests creating an account — you don't need one:
   look for the small **Continue without signing in** link.
3. Two settings to verify (gear icon ⚙, top right):
    - **General** → *Use the WSL 2 based engine* — should already be checked.
    - **Resources → WSL integration** → turn the **Ubuntu** switch on →
      *Apply & restart*.
4. Prove it — in the **Ubuntu** window:
   ```
   docker run --rm hello-world
   ```
   You should see `Hello from Docker!` in the output. If instead you get
   `docker: command not found`, revisit the WSL-integration switch above.

## Step 3 — Get lumnik (in Ubuntu, ~5 min)

From here on, work in the **Ubuntu** window. One rule to know: keep the code in
Ubuntu's own home folder (`~`), never under `/mnt/c` — the Windows disk seen
from Ubuntu is slow and mangles scripts.

1. Install the small tools lumnik's installer uses (Ubuntu arrives almost
   empty):
   ```
   sudo apt update && sudo apt install -y git curl python3 openssl gawk
   ```
   (`sudo` asks for your Ubuntu password — typed blind, see above.)
2. Fetch the code:
   ```
   cd ~ && git clone https://github.com/icreated/lumnik-open.git && cd lumnik-open
   ```

!!! info "Which edition you just cloned"
    This is the **open edition**: every step on this page applies to it **except step 5**.
    It has no chat and no natural-language *ask*. See also the
    [Deploy page](deploy.md#self-host-one-command).

## Step 4 — Start the stack (one command; ~10–20 min the first time)

```
./deploy/selfhost/up.sh --build
```

This single command builds and starts everything **inside Docker** — nothing
else to install on your PC. The first run compiles lumnik from source: it's the
long one, a coffee's worth. Later runs take seconds.

**What the end must look like** — the last lines, in this order:

```
==> hub is healthy
  hubadmin: per-install password set (HUBADMIN_PASSWORD in .env)
  integrator: per-install password set (INTEGRATOR_PASSWORD in .env)

  Hub:       http://localhost:8080   (health: /q/health)
```

Those two `password set` lines matter: they create your login accounts, with
passwords unique to your install (stored in a file named `.env`).

!!! warning "The script stopped straight away, naming missing tools?"
    That's its preflight doing its job — run the `sudo apt install` line from
    step 3, then run `./deploy/selfhost/up.sh --build` again. It's safe to
    re-run as often as you like.

    If instead the very end says `!! could not obtain a Keycloak admin token`,
    the login server was slow to finish its first start — simply re-run
    `./deploy/selfhost/up.sh --build`; the passwords get set on the next pass.

**See it live**: in your normal Windows browser, open
`http://localhost:8080/q/health`. It starts like this (Windows sees Ubuntu's
services on its own `localhost` — nothing to configure):

```
{
    "status": "UP",
    "checks": [
        {
            "name": "Database connections health check",
            "status": "UP",
```

## Step 5 — The AI, on the Windows side (~20 min, ~8 GB of downloads)

Ingestion and the REST API work without this step. The **chat and the
natural-language "ask"** need a local AI runtime:
[Ollama](https://ollama.com), installed on Windows itself.

!!! warning "On the open edition, skip this step entirely"
    The modules that call Ollama are not in the tree you cloned at step 3, so nothing in
    your install will ever reach it — the ~8 GB of downloads below buy you nothing. Come
    back to this step only on a build that carries the chat extensions.

!!! warning "Graphics drivers first — the trap that looks like nothing"
    If your PC has an NVIDIA card, **update its drivers before the first model
    run** (GeForce Experience, or your PC maker's support page). Ollama uses
    the graphics card, and outdated drivers make the model silently fail to
    load — lumnik's ask then errors after ~20 seconds with no useful message.

1. Download and run the **Ollama for Windows** installer from
   [ollama.com](https://ollama.com/download). No settings to change.
2. In **PowerShell**, download the three models lumnik uses **at runtime** (the names must
   be exactly these):
   ```
   ollama pull nomic-embed-text
   ollama pull llama3.2
   ollama pull qwen2.5-coder:7b
   ```
   A fourth model, `gemma4`, is used only by an offline evaluation; skip it.
3. Prove it:
   ```
   ollama run llama3.2 "say hello"
   ```
   A slow-ish sentence back means everything works. The hub finds Ollama on its
   own — nothing to wire.

!!! note "16 GB of RAM or less? Give Windows room to breathe"
    Ubuntu/WSL grabs up to half your RAM by default, and the AI model needs
    ~6 GB free on the Windows side. Cap WSL once: in PowerShell,
    `notepad $env:USERPROFILE\.wslconfig` (say *Yes* to create the file), put
    exactly this in it, save and close:
    ```
    [wsl2]
    memory=6GB
    ```
    Then `wsl --shutdown` in PowerShell, reopen Docker Desktop, and wait a
    minute for the stack to come back.

## Step 6 — `lm`, the control room, on Windows

`lm.exe` is lumnik's command-line and terminal UI for Windows. Download the
Windows x86-64 `.zip` from the
[Releases page](https://github.com/icreated/lumnik-open/releases/latest) and
unpack `lm.exe` anywhere, e.g. your Downloads folder.

!!! note "Windows protected your PC?"
    The first launch may show a blue SmartScreen box — the file is not signed.
    Click **More info**, then **Run anyway**.

In **PowerShell**, from the folder holding the file:

```
.\lm.exe config set-context local --server http://localhost:8080 --oidc-issuer http://localhost:8180/realms/lumnik
.\lm.exe config use-context local
.\lm.exe login
```

`lm login` shows a web address and a code — open the address in your browser,
enter the code, and sign in as **integrator**. The password was minted for your
install at step 4; read it in the **Ubuntu** window with:

```
grep INTEGRATOR_PASSWORD ~/lumnik-open/.env
```

Then just:

```
.\lm.exe
```

— the full terminal UI. Press `?` inside for help, `:` for the command palette.

## Where to next

Walk **[The WOW, hands-on](demo/wow-customer/README.md)** — ingest two fake
legacy systems, watch lumnik fuse them, and ask the result a question in plain
language. One habit to take along: after every `lm connector run …`, check
`lm run list` — *"run scheduled" is not "run succeeded"*, and the list is where
the truth lives.

- **A phone on this stack:** `http://localhost:8080` is reachable from your PC only. Put the
  [TLS façade](deploy.md#one-https-origin-the-tls-facade) in front.
- **Another port, an external PostgreSQL:** see [How to…](deploy.md#how-to) — the port stays,
  use the façade; an external database is not an option on this path.

## Troubleshooting, Windows edition

| Symptom | What it means → what to do |
|---|---|
| `wsl --install` is "not recognized" | Windows too old — run Windows Update until version 21H2+, or install WSL from the Microsoft Store. |
| WSL complains about virtualization / BIOS | The VT switch is off — see the note in step 1. |
| `docker: command not found` in Ubuntu | The WSL-integration switch for Ubuntu is off — step 2, setting 3. |
| `!! could not obtain a Keycloak admin token` at the end of up.sh | Not a missing tool — the preflight would have stopped the script before this point. The login server was still finishing its first start; `up.sh` retries for ~25 s, then warns and leaves both accounts without a password. Re-run `./deploy/selfhost/up.sh --build` — the passwords get set on the next pass (step 4). |
| `http://localhost:8080` suddenly unreachable (often after the PC slept) | WSL's plumbing went stale — in PowerShell `wsl --shutdown`, reopen Docker Desktop, wait a minute. |
| Logins fail with 401 after the laptop slept | Ubuntu's clock drifted during sleep — same cure: `wsl --shutdown`, reopen Docker Desktop. |
| The ask/chat errors after ~20 s | The model failed to load on the Windows side: update your **NVIDIA drivers** (the classic), then check the RAM note in step 5. |
| SmartScreen blocks `lm.exe` | Expected: the file is not signed — **More info → Run anyway** (step 6). |

For everything not Windows-specific: [Troubleshooting, by symptom](recipes/troubleshooting.md).
