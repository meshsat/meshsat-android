# Voice notes and photos (MESHSAT-1735)

The Android half, written so the iOS port can mirror it screen for screen and byte for byte.
The wire is the Bridge's `docs/LXMF_ATTACHMENTS.md` (meshsat repo); nothing MeshSat-specific is
on it, a note from Sideband or MeshChat plays here and one from here plays there.

## What travels

| LXMF field | Value | Android writes | Android reads |
|---|---|---|---|
| `FIELD_AUDIO` (0x07) | `[mode, bytes]` | mode 0x10 (Opus OGG), the bytes a whole RFC 7845 Ogg Opus file: mono, 48 kHz, 16 kbit/s ceiling, what `MediaRecorder` (OGG + OPUS, API 29+) writes and Sideband records | any Opus mode 0x10-0x19 plays (`MediaPlayer`); a Codec2 mode 0x01-0x09 is kept and shown as "Voice note (Codec2 700C), playback not supported on this phone" (raw packed frames, no header, 700C = 4 B per 40 ms; no decoder in the app) |
| `FIELD_IMAGE` (0x06) | `[format, bytes]` | `"webp"`, the picked photo scaled to a 640 px long edge and compressed until it fits (`media/PhotoShrinker.kt`) | any format `BitmapFactory` decodes, shown inline; another is named with its size |

Caps, as the Bridge and the Hub apply them: one audio and one image per message, together at most
**64 KiB** (`LxAttachments.MAX_BYTES`); a note is at most **30 s** (`AttachmentPolicy.MAX_NOTE_MS`,
the recorder stops itself there). A received message over the cap keeps its text and loses the
bytes, logged. The code is `lxmf/LxAttachments.kt` (the Bridge's `internal/lxmf/fields.go`, same
map order, same msgpack bytes: the image entry is read first, the audio entry second; a field of
another shape is ignored and left where it is).

A message with attachments never fits one packet, so the router sends it **direct, as a resource on
the link** (`LxmfRouter.send` picks `Representation.RESOURCE` above 319 bytes of content; an
opportunistic choice falls back to direct). The mid field (`meshsat.mid/1`, MESHSAT-1641) stays
on the message with the attachments after it.

## Policy: Reticulum links only

`lxmf/AttachmentPolicy.kt`, unit-tested:

- `carries(transport)` is true for `lxmf` only. A message by SMS, satellite, the mesh, the Hub route
  or TAK carries its text alone; the note or photo is not sent and the person is told so **before**
  sending, one amber line under the box: "By SMS only the text goes: a voice note 12 s, 18 KB travels
  by Reticulum alone, so it is not sent." Sizes and seconds, never a price.
- On a Reticulum conversation the line reads "With a voice note 12 s, 18 KB, by Reticulum; the far end
  sees it once the whole message is across."
- `canSend`: a text goes anywhere; without one, only a Reticulum message with an attachment has
  something to send. So a note alone cannot go by SMS, its transcript can.

## Data model

Room table `message_attachments` (migration 22 -> 23, `data/MessageAttachmentEntity.kt`):

| column | |
|---|---|
| `id` | row id |
| `message_id` | the `messages` row it belongs to (index) |
| `kind` | `audio` or `image` |
| `mode` | audio: the LXMF audio mode; 0 for an image |
| `format` | image: `webp`; empty for audio |
| `size` | bytes |
| `data` | the bytes as they travel (BLOB, at most 64 KiB) |
| `created_at` | ms (index) |

Bytes live in the row, not in a file: a message and its attachments live and die together, and the
iOS port keeps one table. The store is capped at **16 MiB** (`MessageAttachments.STORE_BYTES`, the
Bridge's default), received and sent alike; `AppDatabase.insertAttachments` files the rows and trims
the oldest (`created_at`, then id) in the same transaction, never the rows just written, so one
large note is always kept. The `messages.text` of a text-less note is empty; the ledger row
(`lxmf_0`) previews it as `[audio Opus OGG 1024 B]`.

## Send

`GatewayService.ACTION_SEND_LXMF` takes, beside `EXTRA_TEXT` and `EXTRA_RECIPIENT`, `EXTRA_AUDIO`
(bytes) with `EXTRA_AUDIO_MODE` (int, 0x10) and `EXTRA_IMAGE` (bytes) with `EXTRA_IMAGE_FORMAT`
(`webp`). `sendLxmf(dest, text, method, attachments)` puts them in the fields, inserts the `messages`
row and the attachment rows, and the delivery ledger row on `lxmf_0`; the row's `forwardedTo` follows
the LXMF state (queued, sent, delivered, failed) as every LXMF send does. The composer sends attachments
only when the conversation's transport is `lxmf` (the policy above); every other transport intent is
unchanged.

The local API (`/api/lxmf/send`) takes `audio: {mode, data_b64}` and `image: {format, data_b64}` like
the Bridge's route (`content` may be empty when the message carries one; 413 over the cap);
`GET /api/lxmf/messages/{row id}/attachments` lists them (`kind`, `mode` or `format`, `size`,
`content_type`, `url`) and `.../attachments/{n}` is the bytes (`audio/ogg`, `audio/codec2` with
`X-LXMF-Audio-Mode`, `image/webp`).

## Receive

`initRnsStack.onMessage`: after the row is filed (and the mid dedup passed), `LxAttachments.attachmentsOf`
reads the fields; within the cap the rows are written, over it the text stays and the bytes go with a
warning. The notification says "Voice note" or "Photo" when the text is empty.

## UI (Messages, one conversation)

- **Composer row**: microphone, photo, text box, send. The microphone **records while held**
  (`ui/components/VoiceNote.kt`, `RecordNoteButton`): a haptic on start and stop, "Recording, 4.2 s" as
  its label, red tint while recording; lifting the finger ends the note, 30 s ends it by itself; a press
  that recorded nothing says "Hold the microphone while you speak." With TalkBack the click action
  starts and a second click stops. RECORD_AUDIO is asked for on the first hold (`rememberPermissionAsk`).
  On Android 9 and older the button says voice notes need Android 10.
- **Pending attachments**: a row per item under the chips, "Voice note, 4.2 s, 7 KB" with play/stop (a
  preview through the same player) and "Photo, 3 KB" with a thumbnail, each with a remove cross. One
  of each at most (the buttons disable); the photo's byte budget is the cap minus the note.
- **Transcript**: while recording, the platform `SpeechRecognizer` listens beside the recorder when the
  phone has one (`audio/SpeechTranscriber.kt`; the manifest `<queries>` names `RecognitionService`).
  What it heard is shown as "Heard: ..." with **Use as text** (into the text box, appended to what is
  there) and **Discard**. Nothing is ever sent without the sender taking it. No recogniser, or one that
  gives nothing (the emulator), leaves the text as typed. Two microphone clients at once is what Android
  12+ allows for the same app; on an older phone the recogniser may lose and simply give nothing.
- **Hint lines**: the policy line (amber when the bearer drops the attachment) above the bearer's own line.
- **Bubbles**: a voice note is a card with play/stop, "Voice note, 4.2 s, 7 KB"; a Codec2 note the same
  card without a button and the "playback not supported" line; a photo is shown inline, full bubble width,
  at most 320 dp tall. A text-less message shows the card alone. One note plays at a time
  (`VoiceNotePlayer`, `MediaPlayer` on a temporary file, deleted after).

## Codec facts for the port

- Recording: `MediaRecorder` with `OutputFormat.OGG` and `AudioEncoder.OPUS`, 1 channel, 48 000 Hz,
  16 000 bit/s, `setMaxDuration(30 000)`; the output file's bytes are the field value as they are. iOS has
  no Opus encoder in AVFoundation for Ogg; the port needs libopus + an Ogg writer (`audio/OggOpus.kt`
  is a 200-line writer/reader to copy: pages, lacing, the CRC with 0x04c11db7 unreflected, OpusHead,
  OpusTags, granule = 48 kHz samples from each packet's TOC byte).
- Playback: `MediaPlayer` plays Ogg Opus since Android 5; on iOS `AVAudioPlayer` does not read Ogg,
  so the port decodes with libopus (the reader above gives the packets) and plays PCM.
- `audio/OpusVoice.kt` drives `MediaCodec` for PCM a program made (the tests' tone): 20 ms frames,
  codec-config buffer parsed for the OpusHead (pre-skip), then `OggOpus.write`. Decoding there sets
  `csd-0` = OpusHead, `csd-1` = pre-skip in ns, `csd-2` = 80 ms pre-roll.

## Tests

- JVM: `lxmf/LxAttachmentsTest` (field bytes, read-back order, caps, pack/unpack, resource size),
  `lxmf/AttachmentPolicyTest`, `audio/OggOpusTest` (CRC vectors computed bitwise outside the code,
  TOC durations, lacing, corruption refused).
- Emulator: `MessageAttachmentRoomTest` (migration 22 -> 23 keeps messages, eviction order, the
  conversation query) and `VoiceNoteHubTest`: a 2 s tone becomes a note through Android's encoder,
  Android's `MediaExtractor` and decoder take it back; the note travels as an LXMF message with
  `FIELD_AUDIO` between two real endpoints on the device, direct as a resource, byte for byte
  (`local_lxmf_hash` in the instrumentation status); and the same message goes to the Hub's address
  over `rns.meshsat.net:4242` (`-e hub_dest <32 hex> -e hub_host ... -e hub_port ...`, default the
  platform workspace `a37933806ad447c68789f29d02186e60`), direct as a resource, asserting DELIVERED
  and printing `lxmf_hash` for the Hub session to check. A workspace on a Private network answers no
  path request from a client without its access code, which this app does not speak (no IFAC): the
  Hub leg is then skipped with the reason and `paths_heard` in the status, never passed by pretending.
  On 28 Oct 2026 the public port gave neither the Android node nor stock Python RNS 1.5.5 a path to
  that address or to the test customer's (`rxb` 0 and 201 bytes in 70 s, no announce heard).

## Not done

- No Codec2 on the phone (no maintained Android build in the dependencies; vendoring the LGPL library
  needs the NDK). A Codec2 note is kept and named, not played; nothing records Codec2.
- Photos keep no EXIF orientation on Android 8/9 (`BitmapFactory` path); 10+ uses `ImageDecoder`.
- The conversation list previews a text-less note as an empty line.
- Attachments do not go through the access rules or any other bearer; a relay to another LXMF
  address carries the text only, as on the Bridge.
