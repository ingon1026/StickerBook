from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import List

FORMAT_VERSION = 1


@dataclass
class StickerEntry:
    id: str
    name: str
    motion: str
    duration_ms: int
    fps: int
    frame_count: int
    width: int
    height: int
    frames_dir: str
    gif_path: str
    texture_path: str
    source_path: str


@dataclass
class Manifest:
    format_version: int
    generated_at: str
    stickers: List[StickerEntry] = field(default_factory=list)

    def upsert(self, entry: StickerEntry) -> None:
        for i, e in enumerate(self.stickers):
            if e.id == entry.id:
                self.stickers[i] = entry
                return
        self.stickers.append(entry)

    def save(self, path: Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "format_version": self.format_version,
            "generated_at": self.generated_at,
            "stickers": [asdict(s) for s in self.stickers],
        }
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2))

    @classmethod
    def load(cls, path: Path) -> "Manifest":
        data = json.loads(Path(path).read_text())
        v = int(data.get("format_version", -1))
        if v != FORMAT_VERSION:
            raise ValueError(
                f"unsupported manifest format_version={v}, expected {FORMAT_VERSION}"
            )
        stickers = [StickerEntry(**s) for s in data.get("stickers", [])]
        return cls(
            format_version=v,
            generated_at=str(data.get("generated_at", "")),
            stickers=stickers,
        )
