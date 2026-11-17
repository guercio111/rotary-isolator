# Vocal Protect diagnostic experiment

This branch is isolated from the stable Build 833 commit.

The diagnostic APK adds a second launcher entry, "Vocal Protect Test". It loads only the StemgenRT VOCALS partition, validates its published SHA-256 and ONNX input/output contract, then benchmarks a synthetic 44.1 kHz stereo stream on the device CPU.

This is deliberately not the audio-processing integration. It does not request microphone or MediaProjection permissions and does not touch BeatEngine, BPM/clock timing, FREQUALIZER capture, routing, or existing effects.

Model weights are included only in the private diagnostic build, with the upstream CC BY-NC-SA 4.0 notice. Commercial use or redistribution is not authorized by this experiment.
