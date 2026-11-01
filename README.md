# Chatterbox

An AI character for your **Paper 26.3** server that you talk to **with your voice**. It hears players through Simple Voice Chat, answers out loud from where it stands, walks around, follows you, emotes, and can act in the world (give items, fight mobs, change the weather, even use Shenanigans trolls).

It runs on **Gemini 3.8 Live**, which is free on Google's free tier.

## What you need

- A Paper **26.3** server (Java 25)
- **Simple Voice Chat** (plugin on the server + mod on every player's game). Without it the character still works, but only through text chat.
- A **Gemini API key** from https://aistudio.google.com/apikey
- Optional: **Shenanigans**, so the AI can troll people

## Setup

1. Build the jar (same as Shenanigans: push to GitHub and download it from the **Actions** tab, or run `gradlew build`).
2. Put `Chatterbox-1.0.0.jar` in `plugins/` and restart.
3. Open `plugins/Chatterbox/config.yml`, paste your key into `gemini.api-key`, and save.
4. In game: `/cb reload`, then `/cb spawn` where you're standing.
5. Wait for "is connected and listening" in chat, walk up and talk.

## Commands (`/chatterbox`, `/cb`, `/aibuddy`)

| Command | What it does |
|---|---|
| `/cb spawn [persona]` | Spawn the character where you stand |
| `/cb remove` | Remove it and disconnect from Gemini |
| `/cb here` | Teleport it to you |
| `/cb follow [player]` / `come` / `stay` | Move it around |
| `/cb persona [id]` | List personas or switch (fresh conversation) |
| `/cb direct <note>` | Secret instruction it acts on, e.g. `/cb direct act scared of Steve` |
| `/cb ask <message>` | Talk to it by command (anyone with `chatterbox.talk`) |
| `/cb emote <type>` | wave, jump, crouch, dance, spin, nod, shake_head, celebrate, scared |
| `/cb mute` / `unmute` | Pause listening and talking |
| `/cb reset` | Start a fresh conversation |
| `/cb skin <username>` / `name <name>` | Change the current persona |
| `/cb status` | Connection, model, voice chat and body status |
| `/cb reload` | Reload config.yml (also reconnects) |

Players can also type in chat. By default a message only goes to the AI if it contains the character's name (`chat.respond-to`).

## Personas

Five are included: `bobby` (clueless noob), `herobrine` (creepy ghost), `coach` (wrong-about-everything pro), `merchant` (greedy trader) and `cohost` (YouTube co-host). Add your own under `personas:` in the config: a name, a skin (any Minecraft username), a voice and a personality.

## What the AI can do

look around, follow, come, stay, emote, hold an item, break blocks (chop trees, mine stone or ores, dig, or "break what I'm looking at"; chests and other containers are protected), give items (with a blocklist and max amount), fight hostile mobs, set time and weather, harmless lightning, and use the Shenanigans trolls listed in `actions.trolls`. Turn any of these off under `actions:` in the config.

## Permissions

| Permission | Default | |
|---|---|---|
| `chatterbox.admin` | op | All `/cb` commands and connection messages |
| `chatterbox.talk` | everyone | Talk to it by voice or chat |

## How it works

`VoiceBridge` decodes nearby players' microphone audio from Simple Voice Chat, converts it to 16 kHz and streams it to Gemini Live over a WebSocket (`LiveSession`). Gemini answers with 24 kHz speech, which is converted to 48 kHz and played through an entity audio channel on the character, so it fades with distance like a real player. Function calls from Gemini run in `ToolBox` on the main thread. Connections are refreshed automatically every ~10 minutes with session resumption, so long recordings keep their memory.

## Troubleshooting

- **"No Gemini API key"**: paste it in config.yml and run `/cb reload`.
- **It never says "connected"**: run `/cb status` to see the error. A wrong key, a typo in `gemini.model` or an invalid `voice` name are the usual causes. Try `voice: ""`.
- **It hears itself / talks to itself**: everyone should use headphones, and keep `ignore-mic-while-speaking: true`.
- **It answers chat but not voice**: check the player has the Simple Voice Chat mod connected (no red icon), is within `hearing-range`, and try `announce-speakers: false`.
- **"quota" or "rate limit" errors**: the free tier ran out for now. Wait, or enable billing in Google AI Studio.
