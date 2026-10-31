# Reaching the MCP server from the internet

By default the MCP server binds to `127.0.0.1`, which means only the tablet can talk to it. That is
the right default — but it also means a cloud agent (Gemini, ChatGPT, Claude, anything hosted) can
never reach it, because `127.0.0.1` on your tablet is not an address the internet can dial.

A tunnel fixes that. **`cloudflared` runs on the tablet, connects to `127.0.0.1` locally, and
Cloudflare gives you a public HTTPS URL that forwards to it.** The socket itself never has to be
exposed on your Wi-Fi, so this is meaningfully safer than binding the app to `0.0.0.0`.

```
cloud agent ──HTTPS──> Cloudflare edge ──tunnel──> cloudflared (tablet) ──127.0.0.1──> Edit Pro
```

---

## Option A — quick tunnel (no account, no domain)

This is the one to start with. It needs nothing from Cloudflare except their free edge, and gives
you a random hostname that changes every time you start it.

### 1. Install Termux

Get it from **F-Droid** (`f-droid.org`), not the Play Store — the Play Store build is abandoned and
its package manager no longer works.

### 2. Install the Cloudflare tunnel client

Open Termux and run, one line at a time:

```bash
pkg update -y && pkg upgrade -y
pkg install -y wget
```

Then download the ARM64 build and make it executable:

```bash
cd ~
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 -O cloudflared
chmod +x cloudflared
```

Check it runs:

```bash
./cloudflared --version
```

### 3. Make sure Edit Pro is serving

Open Edit Pro at least once. Agent access is on by default, so the server is already listening and
you should see the "Agent access is on" notification. If you stopped it, restart it from
**Tools → Agent access (MCP)**.

### 4. Start the tunnel

```bash
./cloudflared tunnel --url http://127.0.0.1:8765
```

It prints a box like this:

```
+--------------------------------------------------------------------------------------------+
|  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |
|  https://random-words-here.trycloudflare.com                                               |
+--------------------------------------------------------------------------------------------+
```

That URL is your endpoint. **Append `/mcp`:**

```
https://random-words-here.trycloudflare.com/mcp
```

Leave that Termux session running. `Ctrl+C` stops the tunnel — which is also the quickest way to
switch agent access off completely, since the app stays loopback-only behind it.

### 5. Point the agent at it

Give the agent (or its MCP config) the URL and your pairing token from
**Tools → Agent access (MCP)**:

```json
{
  "mcpServers": {
    "editpro": {
      "type": "http",
      "url": "https://random-words-here.trycloudflare.com/mcp",
      "headers": { "Authorization": "Bearer YOUR-TOKEN" }
    }
  }
}
```

If the client cannot send headers, put the token in the query string instead:
`https://random-words-here.trycloudflare.com/mcp?token=YOUR-TOKEN`

---

## Option B — named tunnel (stable hostname, needs a domain)

A quick tunnel URL changes every restart, which is annoying if you want to configure an agent once.
A named tunnel gives you something like `mcp.yourdomain.com` that never changes.

It needs a domain managed by Cloudflare, and you must do the DNS step in the Cloudflare dashboard —
the API token this assistant holds is scoped too narrowly for DNS (it returns `403 Invalid access
token` when asked to list zones), so this part is manual.

In Termux:

```bash
./cloudflared tunnel login          # opens a browser to authorise; paste the URL it prints
./cloudflared tunnel create editpro
./cloudflared tunnel route dns editpro mcp.yourdomain.com
./cloudflared tunnel run --url http://127.0.0.1:8765 editpro
```

Then use `https://mcp.yourdomain.com/mcp` in your client config.

---

## Security: read this before you expose it

Once tunnelled, your project editor is reachable by anyone who has both the URL and the token. The
token is the only lock on the door.

What the app already does about that:

- **24-character token** (~120 bits), generated on first run. Tokens shorter than 16 characters
  from older builds are replaced automatically.
- **Constant-time comparison**, so timing cannot be used to guess it character by character.
- **Brute-force brake** — five wrong tokens in a row pause authentication for 30 seconds.
- **Rejected attempts are logged** in the Agent access panel with the caller's IP.

What you should do:

- **Treat the token like a password.** Don't paste it into a chat with a hosted assistant you don't
  trust with edit access to your project.
- **Rotate it** with *New token* if it ever leaks. That instantly cuts off anyone holding the old one.
- **Kill the tunnel when you're done** — `Ctrl+C` in Termux. The app goes back to being reachable
  only from the tablet.
- **Prefer Option B's stable hostname if you expose it often.** Quick-tunnel URLs are publicly
  listed in certificate transparency logs and are not secret.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `401 Unauthorised` | Wrong or stale token. Rotate it in the app and update the client. |
| `405` on a `GET` | The client insists on opening an SSE stream with GET. This server answers POST (and now replies in SSE framing when asked), but it does not hold a long-lived GET stream. |
| `Connection refused` | The app isn't running, or agent access is stopped. Open Edit Pro and check the notification. |
| `cloudflared: not found` | You're in a different Termux session; `cd ~` first. |
| Works from the tablet, not from outside | The tunnel printed the URL before the app was serving. Restart `cloudflared`. |
| Client says "no tools" | It connected but never sent `initialize`. Most clients do this automatically; check the Agent access log to see whether requests are arriving at all. |

The **Recent activity** log in the Agent access panel is the first place to look for any of these —
it shows every request the server accepted, rejected or failed.
