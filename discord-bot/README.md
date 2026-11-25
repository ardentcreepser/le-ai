# BobbyBot: Bobby as a Discord bot

Bobby from the Chatterbox plugin, now living in your Discord server. He uses the same brain (Google's free
Gemini Live voice AI), so he works the same way:

- **Voice:** he joins a voice channel and talks with everyone out loud. You can interrupt him, he knows who
  is talking, and he says hi when people join.
- **Text:** @mention him, reply to his message, or just type "Bobby" and he answers in chat. He also answers DMs.
- **Actions:** he can react with emoji, type messages (and ping people), start polls, change his status and
  nickname, play soundboard sounds, flip coins and roll dice, and join or leave voice channels by himself.
- **Subtitles:** everything he says in voice is also posted in the text channel where you used `/bobby join`
  (handy for editing videos).

It runs on your own PC, like your Minecraft server. Bobby is only online while the bot window is open.

---

## Setup (about 10 minutes)

### 1. Make the Discord bot

1. Go to <https://discord.com/developers/applications> and click **New Application**. Name it `Bobby`.
2. Optional: on the **General Information** page, give him a profile picture.
3. Open the **Bot** tab:
   - Click **Reset Token**, then **Copy**. This is the bot's password. Keep it secret.
   - Scroll down to **Privileged Gateway Intents** and turn on **Message Content Intent**. Click **Save Changes**.

### 2. Get a Gemini API key

Use the same free key as the Chatterbox plugin, or make a new one at <https://aistudio.google.com/apikey>.

### 3. Download BobbyBot

1. On GitHub, open the **Actions** tab of this repo.
2. Click the newest green run called **Build BobbyBot (Discord bot)**.
3. Scroll down to **Artifacts**, download **BobbyBot**, and unzip it into a new folder (for example `Documents\BobbyBot`).

### 4. Paste in your token and key

Open `config.yml` in that folder with Notepad and paste:

```yaml
discord:
  token: "your bot token here"

gemini:
  api-key: "your Gemini key here"
```

Save the file. Never share it or upload it anywhere.

### 5. Start it

Double-click **start.bat** (Mac/Linux: run `./start.sh`). It needs **Java 25**, the same Java your Minecraft
server uses.

When it's running, the window shows an **invite link**. Open it, pick your server, and click **Authorize**.

### 6. Talk to Bobby

- Join a voice channel and type **`/bobby join`**. Bobby joins and says hi.
- In any text channel, type `@Bobby hey what's up` or `bobby do you like creepers?`.
- To make him leave: `/bobby leave`, or just tell him to leave.

---

## Commands

| Command | Who | What it does |
|---|---|---|
| `/bobby join` | everyone | Bobby joins your voice channel (or the one you pick). Subtitles go to that text channel. |
| `/bobby leave` | everyone | Bobby leaves the voice channel. |
| `/bobby status` | everyone | Shows whether his brain is connected, and the last problem if there was one. |
| `/bobby-admin direct` | Manage Server | Secretly tell Bobby what to do next ("pretend you just saw a ghost"). Only you see the reply. |
| `/bobby-admin reset` | Manage Server | Bobby forgets the whole conversation. |
| `/bobby-admin mute` / `unmute` | Manage Server | Bobby stops (or starts) listening, talking and answering. |

You can change who can use the commands in **Server Settings > Integrations > Bobby**.

## Settings worth knowing (config.yml)

- `bobby.personality`, `bobby.voice`, `bobby.name`: who he is and how he sounds. Same voices as Chatterbox.
- `chat.respond-to`: `mention` (default), `all` (every message he can see), or `off`.
- `chat.chat-channels`: channel IDs where he answers every message, like a "talk-to-bobby" channel.
- `voice.end-of-speech-ms`: raise it (e.g. 800) if he jumps in while you're still talking.
- `voice.greet-people`: turn off if the greetings get annoying.
- `actions`: switch off anything you don't want him doing.
- `gemini.idle-minutes`: when nobody talks to him for this long (and he isn't in a call), he disconnects from
  Gemini to save your free quota. He wakes up as soon as someone talks to him and still remembers the chat.

Restart the bot after changing settings.

## Problems?

- **"MESSAGE CONTENT INTENT is switched off"**: step 1.3, then start again.
- **"Discord didn't accept the bot token"**: copy the token again (Reset Token) and paste it between the quotes.
- **No `/bobby` commands**: open the invite link from the bot window again (it includes the commands permission),
  then restart Discord.
- **Bobby joins voice but doesn't hear anyone**: run `java -jar BobbyBot.jar --selftest` in the folder. Every line
  should say `[OK]`; a `[FAIL]` line says what is wrong.
- **"Bobby's brain isn't working right now"**: the message says why. Usually the Gemini key is wrong or the free
  limit for the day ran out.
- Each server Bobby is in (and each person DMing him) uses its own Gemini connection. On the free tier, keep it to
  a couple at once, including the Chatterbox plugin.
