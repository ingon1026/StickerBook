# Stickerbook 갤탭/Unity 호환 자산 포맷 v1

## 디렉토리 트리

```
stickerbook_assets/
├── manifest.json
└── stickers/
    └── <sticker_id>/
        ├── meta.json
        ├── source.png        원본 그림 사진
        ├── texture.png       누끼된 캐릭터 (정지, BGRA)
        ├── animation.gif     호환용 / 미리보기
        └── frames/
            ├── 0001.png
            ├── 0002.png
            └── ...
```

## manifest.json

```json
{
  "format_version": 1,
  "generated_at": "2026-05-14T10:00:00",
  "stickers": [
    {
      "id": "s001",
      "name": "사자",
      "motion": "dance_1",
      "duration_ms": 2000,
      "fps": 30,
      "frame_count": 60,
      "width": 512,
      "height": 512,
      "frames_dir": "stickers/s001/frames",
      "gif_path": "stickers/s001/animation.gif",
      "texture_path": "stickers/s001/texture.png",
      "source_path": "stickers/s001/source.png"
    }
  ]
}
```

## meta.json (스티커 1 개)

```json
{
  "id": "s001",
  "name": "사자",
  "motion": "dance_1",
  "created_at": "2026-05-14T10:00:00",
  "fps": 30,
  "frame_count": 60,
  "width": 512,
  "height": 512
}
```

## 규칙

- `id` 는 자산 폴더 내 유일. 영문 소문자 + 숫자 + `_` 만.
- `frames/` PNG 는 알파 채널 보존 (RGBA). 8-bit 알파.
- `animation.gif` 는 호환용. 갤탭/Unity 는 PNG seq 우선.
- `texture.png` 는 정지 캐릭터 (모션 적용 전 상태).
- 모든 경로는 자산 root 기준 상대.

## 버전 정책

- `format_version` 이 호환 깨질 때 +1.
- 갤탭/Unity 는 자기가 모르는 version → 안전하게 에러 표시.
