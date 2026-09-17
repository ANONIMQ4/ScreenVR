# ScreenVR

ScreenVR транслирует экран Mac на Android-телефон с низкой задержкой. Телефон показывает поток в обычном режиме или в SBS/Cardboard-режиме с линзовой маской, чтобы использовать его как простой VR-дисплей.

```text
Mac ScreenCaptureKit -> ffmpeg H.264 RTP -> LAN UDP 5004 -> Android MediaCodec -> OpenGL SBS view
```

## ETS2 И Head Tracking

В ветке с ETS2 телефон может работать как Bluetooth HID-джойстик для head tracking. Для подготовки настроек управления ETS2:

```bash
python3 tools/ets2-bthid/apply_controls.py
```

Хелпер ищет последнее Bluetooth HID-устройство в `global_controls.sii` и прописывает его во все профили ETS2:

```text
trackiryaw   = joy2.x
trackirpitch = joy2.rx
trackirroll  = 0
```

Подробности по рабочему запуску: `docs/working-rtp-launch.md`.

