# The editing assistant (bring your own key)

Edit Pro Studio can drive itself. Open **Tools → Editing assistant**, paste an API key, and describe
the edit you want in plain English.

This is the opposite trade to the [MCP server](MCP.md): instead of exposing your tablet to an
outside agent, **the app calls the model outbound**. Nothing listens on a port, nothing needs a
tunnel, and nothing about your project is reachable from the internet.

## Setup

1. **Tools → Editing assistant**.
2. Pick a preset, or fill in any endpoint that speaks the OpenAI chat-completions API.
3. Paste your key and press **Save**. It is stored encrypted (see below).

| Preset | Base URL | Notes |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` is a good cheap default |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | Google's OpenAI-compatible endpoint |
| OpenRouter | `https://openrouter.ai/api/v1` | Hundreds of models, several free ones |
| Groq | `https://api.groq.com/openai/v1` | Fast, generous free tier |
| Local (Ollama) | `http://127.0.0.1:11434/v1` | Runs on the tablet, no key, no cost |

Anything else works too — DeepSeek, Mistral, Together, LM Studio, vLLM, a proxy. The only
requirement is `POST {baseUrl}/chat/completions` with `tools` support.

## What it can do

Exactly what the MCP tools can do, because it *is* the same tool layer: read the timeline, add and
trim clips, move things between tracks, set the full colour grade, add titles and markers, apply
looks, keyframe, undo and redo, and `fetch_url` a media file from the web into the library.

It cannot export, and it cannot clear the whole timeline.

## How it stays sane

- **At most 8 model round-trips per message.** If the agent hasn't converged by then it stops and
  says so, rather than burning your tokens in a loop.
- **Tool output is truncated** before going back into context, so listing a big timeline can't blow
  the window.
- **Errors go back to the model as text**, so it can correct a bad call instead of the turn dying.
- **Stop** cancels the run immediately.
- Every edit goes through the same view-model operations as the UI, so **Ctrl+Z undoes the agent**.

## Where the key lives

The API key is encrypted with an AES-256 key generated inside the **Android Keystore** and never
leaves it. Only the ciphertext and its IV are written to preferences, so copying the preferences
file off the device does not reveal the key. **Remove key** deletes it.

The key is sent only to the base URL you configure — nowhere else, and never to this project's
maintainers. You pay the provider directly; there is no proxy and no shared quota.

## What to expect from the models

Agentic editing is harder than chatting, and quality tracks the model closely. A small or heavily
quantised model will often call the wrong tool, pass a string where a number belongs, or stop after
one step instead of finishing. If results feel poor, try a stronger model before assuming the
feature is broken — and use the transcript, which shows every tool call and its result, to see
exactly where it went wrong.

## Relationship to the MCP server

They are alternatives, not rivals, and both share one tool layer:

- **Assistant** — self-contained, no exposure, needs a key. Best default.
- **[MCP](MCP.md)** — lets an agent you already use (Gemini, Claude, Cursor) drive the editor. Off
  by default; if you turn it on, running and securing it is your responsibility. See
  [TUNNEL.md](TUNNEL.md) to reach it from a cloud agent.
