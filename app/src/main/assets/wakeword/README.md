# Wake-word model assets

On-device "Hey Hermes" detection. These files are data, not code; they are
loaded once by `WakeWordDetector` and shipped inside the APK.

| File | Purpose | Source | License |
| --- | --- | --- | --- |
| `melspectrogram.tflite` | 16 kHz PCM16 -> 32-band mel frames | pyopen-wakeword 1.1.0; 6-byte tensor shape/signature metadata patch (see below) | Apache-2.0 |
| `embedding_model.tflite` | mel frames -> 96-dim embedding windows | pyopen-wakeword 1.1.0, byte-identical | Apache-2.0 |
| `hey_hermes.tflite` | 16 x 96 embedding windows -> wake probability | Hermes Agent bundled hotword model, trained with the openWakeWord pipeline | Apache-2.0 |
| `fixtures/hey_hermes.wav` | positive self-test fixture (16 kHz, mono, PCM16) | macOS `say -v Samantha --data-format=LEI16@16000 "Hey Hermes"` | sample |
| `fixtures/hello_there.wav` | negative self-test fixture (16 kHz, mono, PCM16) | macOS `say --data-format=LEI16@16000 "hello there, how are you"` | sample |

The upstream mel model input was dynamic `[1,1]`; Java TFLite 2.16.1 attempted a huge allocation because its model signature was also dynamic. The shipped asset changes only the input shape/signature metadata to `[1,1760]` (bytes changed: 6; SHA-256 `a3736e7a5fd425dbc90739fedaacec0830ef0a6f5985015278b79544a6003b3c`, upstream SHA-256 `96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8`). Weights are unchanged.

openWakeWord: https://github.com/dscripka/openWakeWord (Apache-2.0)
pyopen-wakeword: https://github.com/rhasspy/pyopen-wakeword (Apache-2.0)

The preprocessing port must match `OpenWakeWordFeatures.process_streaming`
and `OpenWakeWord.process_streaming` from pyopen-wakeword exactly; see
`docs/HEY_HERMES_WAKE_WORD_PLAN.md`. Identical model files alone do not imply
identical scores across inference engines or platforms.

Fixture SHA-256: positive `d60f5c99e6643b514b19342d533d73c3e4a41f0fe4ceb9335f93f4a3cc26e2e4`;
negative `5ca4c0236f319b23fc208a0a87f179c5916eb465096d2b862b6462d1317e7c1d`.
