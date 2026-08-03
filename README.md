# Voice Subtitles

Live subtitles for [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat): who is speaking, and what they are saying, in a caption feed along the bottom of the screen.

**Voice chat leaves some players out.** If you are deaf or hard of hearing, proximity voice chat is a conversation happening around you that you cannot join — on roleplay servers, where most of the story is told out loud, that means missing the game itself. The same is true if you play with the sound off, in a noisy room, or in a language you read more easily than you hear. This mod puts that conversation on screen.

Everything runs on your own machine. Nothing is recorded, nothing is uploaded, and no server-side mod is needed.

![The caption feed during a conversation](docs/screenshots/feed-conversation.png)

## What it does

- **Caption feed** in the spirit of Minecraft: Story Mode — the speaker's head, their name, and their speech, stacked above the hotbar and fading out when the conversation moves on.
- **Roleplay names.** The name comes from the player list, so a character you know shows up as their character name and one you have not met shows up exactly as the server calls them.
- **Per-speaker colours,** derived from each player's UUID, so the same person is the same colour for everybody running the mod. Can be set to plain white, or assigned by hand.
- **Above the speaker's head** as an alternative to the feed, drawn independently of name tags so it works on servers that hide them.

  ![A caption above a speaker's head](docs/screenshots/over-head.png)

- **Distance is visible, not filtered.** Everything the client can hear gets a caption; the further away and the quieter the voice, the fainter its text. Whispers are fainter still. Nothing is ever hidden for being far away.
- **Where the voice came from,** optionally: a small arrow in front of each line points at the speaker — up for ahead, down for behind you. Off by default.
- **Text chat too, if you want it** — voice only by default, or voice and chat together, or chat alone.
- **A conversation log** you can scroll back through and save to a file. Spoken lines come from the recogniser, so treat it as a reminder of the conversation rather than a transcript; text chat lines are exact and marked as such.

## Speech recognition

Recognition runs entirely on your machine, through [Vosk](https://alphacephei.com/vosk/).

**This mod downloads a file after installation.** Recognition needs a language model, and models are far too large to ship inside the mod — the Russian one alone is 45 MB. The first time it is needed, the mod fetches the model matching your game language from `alphacephei.com` (the official Vosk model repository, Apache-2.0 licensed) and unpacks it into `config/voice_subtitles/models`. That is the only network request the mod ever makes: nothing is uploaded, nothing is reported, and no audio ever leaves your machine.

If you would rather it did not: turn off **Download the model automatically** in the settings and either fetch a model yourself with `/vsub model download <id>`, or unpack one from [the Vosk model list](https://alphacephei.com/vosk/models) into that folder by hand. Without a model the mod still shows who is speaking, just without the words.

Pick your language once:

```
/vsub model list
/vsub model download ru
```

Words appear as they are recognised and settle when the sentence ends. If no model is installed, or the machine cannot keep up, captions still show who is speaking — just without the words.

### Accuracy, and the bigger model

The default model is the small one — around 45 MB, loads in a second, costs almost nothing to run. It is also the least accurate of the two: on spontaneous speech through a compressed voice channel, expect roughly every third word to come out wrong. The gist of a sentence reads fine; a word-for-word transcript it is not. Character names are the worst case — a model trained on ordinary Russian has never seen "Элиандрэль фон Сильверхейм" and will never produce it.

If you want better and can afford it, there is a large model:

```
/vsub model download ru-large
```

**Read this before you do.** It is **1.8 GB** to download and unpack, and it wants roughly the same again in memory, on top of whatever Minecraft is already using. Loading it takes noticeably longer, and on a modest machine you will hear the fans: after switching settings or reloading the model, captions can lag behind the speech for a while until it settles. In exchange, accuracy roughly doubles — from about a third of words wrong down to a fifth, and markedly better on clear speech.

The recommendation stands as it is: **the small model for everyone, the large one for those who want it sharper and have the machine for it.** Nothing about the mod changes between them except the model in use, so switching back is one command.

## Commands

| Command | What it does |
|---|---|
| `/vsub color <player> <#RRGGBB\|reset>` | Assigns a colour by hand |
| `/vsub model list\|download <id>\|use <id>\|status` | Manages recognition models |
| `/vsub log` | Opens the conversation log |

## Settings

Mod Menu opens the settings screen: the richer Cloth Config version when Cloth is installed, the built-in one otherwise. Either way `config/voice_subtitles.json` is the source of truth.

Three keys are available under **Voice Subtitles** in the game's controls — turn subtitles on and off, switch between the feed and above the head, and open the conversation log. All three ship unbound: whatever key this mod could claim is a key somebody already uses.

## Requirements

- Minecraft 1.21.6 – 1.21.8, Fabric
- Fabric API
- Simple Voice Chat

## Thanks

Thanks to **Contik** for testing.

## Third-party components

Bundled inside the mod jar:

- [Vosk](https://github.com/alphacep/vosk-api) by Alpha Cephei Inc — Apache License 2.0
- [JNA](https://github.com/java-native-access/jna) — Apache License 2.0 / LGPL 2.1+

Downloaded on demand: [Vosk speech models](https://alphacephei.com/vosk/models) — Apache License 2.0.

## Licence

LGPL-3.0-or-later
