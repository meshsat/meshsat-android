# Third-Party Notices — MeshSat Android

MeshSat Android is GPL-3.0 (see `LICENSE` and the README §License). Library
dependencies and their licences are declared in the Gradle build files. In
addition, the repository vendors or ships the following third-party material:

| Asset | Origin | Licence |
|---|---|---|
| `app/src/main/proto/meshtastic/` (7 `.proto` files) | Meshtastic protobuf definitions (`meshtastic/protobufs`) | GPL-3.0 — compiled into the shipped binary; this is why the app as a whole is GPL-3.0 |
| `app/src/main/assets/encoder.onnx` | INT8-quantised ONNX export derived from `sentence-transformers/all-MiniLM-L6-v2` | Apache-2.0 |
| `app/src/main/assets/vocab.txt` | WordPiece vocabulary (30.522 tokens) from the BERT uncased tokenizer | Apache-2.0 |
| `app/src/main/assets/world.mbtiles` | Natural Earth raster world basemap (z0–z3) | Public domain (Natural Earth) |
| `app/src/main/res/font/plex_*.ttf` (IBM Plex Sans 400/500/600, IBM Plex Mono 400/500) | IBM Plex (`github.com/IBM/plex`), the typeface of the MeshSat Bridge and brand | SIL Open Font License 1.1, Copyright 2017 IBM Corp., Reserved Font Name "Plex" |
| `app/src/main/res/drawable*/brand_*.png`, `mipmap*/ic_launcher_*.png` | The approved MeshSat mark, extracted from `meshsat-website/brand/` without redrawing | MeshSat brand asset, not covered by the GPL; do not alter the mark |

`codebook_v1.bin` and `corpus_index.bin` are project-generated artefacts of
the MSVQ-SC semantic codec, not third-party material.

All four MSVQ-SC assets (`encoder.onnx`, `vocab.txt`, `codebook_v1.bin`,
`corpus_index.bin`) are data, not code, and come from one script in the
MeshSat Bridge repository (GPL-3.0): `sidecar/msvqsc/train.py` at
`github.com/meshsat/meshsat`. It exports the encoder from all-MiniLM-L6-v2
with INT8 quantisation, trains the residual codebook (8 stages of 1024
entries, 384 dimensions) and embeds the corpus. The training corpus is the
list built into that script, or a text file given with `--corpus`. Training
starts from random values, so a rerun gives an equivalent codebook, not a
byte-identical one.
