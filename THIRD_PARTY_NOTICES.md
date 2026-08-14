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

`codebook_v1.bin` and `corpus_index.bin` are project-generated artefacts of
the MSVQ-SC semantic codec, not third-party material.
