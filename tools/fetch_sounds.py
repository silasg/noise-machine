#!/usr/bin/env python3
"""Pinned downloads and the original library's two distinct wrap recipes."""
import argparse
import array
import hashlib
import math
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile

PIN = "2eaca9ba5a1b624adf90b6ccee1008efc46f0273"
BLANKET = f"https://raw.githubusercontent.com/rafaelmardojai/blanket/{PIN}/data/resources/sounds"
# App name, upstream name, original SHA-256, overlap seconds.
SOURCES = [
    ("ocean", "waves", "20e1aec7c7542f71645275871650881ea33bc92bd9816b243ea920bb1daa51a0", 0),
    ("wind", "wind", "cfdc81be69cd5f434ebfeb708bdf094725f3052d76691f550ff0e10eee3e32d2", 0),
    ("fireplace", "fireplace", "a6395379a32c2dc30dd870c4571ded4a5311435aacfe354b3a82a9b3ab98620b", 0),
    ("storm", "storm", "7e2a9bee62273b1deb169b6f7a308b70ead6ae450d3f6c75b854a44e4bef7b5a", 0),
    ("forest", "birds", "e59b19c2a377d5835d07e7ec8f8c876b9f40ae7f703738b55d616ef96a1638b2", .75),
    ("stream", "stream", "ce372c5db561168636647dd7d4aac39ef1785ae5d415ad0e589186ce1b7c5796", 0),
    ("cafe", "coffee-shop", "397b5d95f06df69ba2989147cf9c37a417b2dc5950c1a16fbe6be6fa372d25ef", .15),
]
FAN_URL = "https://cdn.freesound.org/previews/546/546174_9453283-hq.mp3"
FAN_SHA = "35f20d4df730c480be49515898ae78a2c00c1acaa6c8b2d99aa8766e401fbcbc"
AMBIENT_URL = "https://opengameart.org/sites/default/files/Ambient-Loop-isaiah658_0.ogg"
AMBIENT_SHA = "3c328794b77d3f3742a5a8fa36606c3b780d15b0b8f6148b29a78ec6caffcd9e"


def run(*args: str) -> bytes:
    return subprocess.check_output(args)


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", *args], check=True)


def download(url: str, target: Path, expected: str) -> None:
    print(f"Download {url}", flush=True)
    # Keep HTTP response bodies on errors (not curl --fail's silent discard).
    status = run("curl", "--disable", "--silent", "--show-error", "--location", "--proto", "=https",
                 "--proto-redir", "=https", "--connect-timeout", "30", "--max-time", "300",
                 "--output", str(target), "--write-out", "%{http_code}", url).decode()
    if status != "200":
        body = target.read_bytes().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {status} fetching {url}\n{body}")
    actual = hashlib.sha256(target.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"SHA-256 mismatch for {url}: expected {expected}, got {actual}")


def blanket_wrap(source: Path, output: Path, fade: float) -> None:
    # Preserve the original format-duration based trims and FFmpeg qsin curves.
    duration = float(run("ffprobe", "-v", "error", "-show_entries", "format=duration",
                         "-of", "csv=p=0", str(source)))
    end = f"{duration - fade:.9f}"
    filters = (
        f"[0:a]asplit=3[a][b][c];[a]atrim=start={fade}:end={end},asetpts=PTS-STARTPTS[mid];"
        f"[b]atrim=start={end},asetpts=PTS-STARTPTS[tail];"
        f"[c]atrim=end={fade},asetpts=PTS-STARTPTS[head];"
        f"[tail][head]acrossfade=d={fade}:c1=qsin:c2=qsin[wrap];"
        "[mid][wrap]concat=n=2:v=0:a=1[out]"
    )
    ffmpeg("-i", str(source), "-filter_complex", filters, "-map", "[out]",
           "-c:a", "libvorbis", "-q:a", "5", str(output))


def fan_wrap(source: Path, output: Path) -> None:
    samples = array.array("f")
    samples.frombytes(run("ffmpeg", "-v", "error", "-i", str(source),
                          "-f", "f32le", "-acodec", "pcm_f32le", "-"))
    if sys.byteorder != "little":
        samples.byteswap()
    channels, fade = 2, 2205
    frames = len(samples) // channels
    if frames != 69168:
        raise RuntimeError(f"Unexpected fan decoded frame count: {frames}")
    result = samples[fade * channels:(frames - fade) * channels]
    for i in range(fade):
        t = i / (fade - 1)
        for c in range(channels):
            result.append(samples[(frames - fade + i) * channels + c] * math.cos(t * math.pi / 2)
                          + samples[i * channels + c] * math.sin(t * math.pi / 2))
    if sys.byteorder != "little":
        result.byteswap()
    pcm = output.with_suffix(".f32le")
    pcm.write_bytes(result.tobytes())
    ffmpeg("-f", "f32le", "-ar", "44100", "-ac", "2", "-i", str(pcm),
           "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "5", str(output))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[1]
                        / "app/src/main/assets/sounds")
    parser.add_argument("--rain-file", type=Path, help="manually downloaded Pixabay MP3")
    parser.add_argument("--force", action="store_true", help="replace existing outputs, not original inputs")
    args = parser.parse_args()
    for tool in ("curl", "ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            parser.error(f"need {tool} in PATH")
    output = args.output_dir.resolve()
    if (args.force or not (output / "rain.ogg").is_file()) and args.rain_file is None:
        parser.error("Rain needs a manual download from https://pixabay.com/sound-effects/nature-calming-rain-loop-398653/; pass --rain-file PATH (or keep existing rain.ogg without --force).")
    if args.rain_file is not None and not args.rain_file.is_file():
        parser.error("--rain-file must name an existing manual download")
    output.mkdir(parents=True, exist_ok=True)
    # Stage every result on the destination filesystem. Failures before publication
    # leave every existing asset intact; TemporaryDirectory cleans on exceptions.
    with tempfile.TemporaryDirectory(prefix=".fetch-sounds-", dir=output) as tmp:
        stage = Path(tmp)
        pending: list[str] = []

        def needed(name: str) -> bool:
            return args.force or not (output / name).is_file()

        for name, upstream, digest, fade in SOURCES:
            dest = f"{name}.ogg"
            if not needed(dest):
                continue
            source = stage / f"source-{upstream}.ogg"
            download(f"{BLANKET}/{upstream}.ogg", source, digest)
            if fade:
                blanket_wrap(source, stage / dest, fade)
            else:
                shutil.copyfile(source, stage / dest)
            pending.append(dest)
        if needed("ambient.ogg"):
            download(AMBIENT_URL, stage / "ambient.ogg", AMBIENT_SHA)
            pending.append("ambient.ogg")
        if needed("fan.ogg"):
            download(FAN_URL, stage / "fan.mp3", FAN_SHA)
            fan_wrap(stage / "fan.mp3", stage / "fan.ogg")
            pending.append("fan.ogg")
        # Always regenerate PCM when replacing OGG; otherwise derive missing PCM
        # from the user's retained OGG (no claim that arbitrary local audio is pinned).
        if needed("fan.pcm") or "fan.ogg" in pending:
            fan = stage / "fan.ogg" if "fan.ogg" in pending else output / "fan.ogg"
            ffmpeg("-i", str(fan), "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le",
                   "-f", "s16le", str(stage / "fan.pcm"))
            pending.append("fan.pcm")
        if needed("rain.ogg"):
            ffmpeg("-i", str(args.rain_file.resolve()), "-c:a", "libvorbis", "-q:a", "5",
                   str(stage / "rain.ogg"))
            pending.append("rain.ogg")
        for name in pending:
            if name.endswith(".ogg"):
                ffmpeg("-i", str(stage / name), "-f", "null", "-")
        for name in pending:
            (stage / name).replace(output / name)
            print(f"Installed {output / name}")
    print("Done (existing assets skipped unless --force).")


def terminate(signum: int, frame: object) -> None:
    """Unwind TemporaryDirectory on termination as well as Ctrl-C/errors."""
    raise SystemExit(128 + signum)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, terminate)
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
