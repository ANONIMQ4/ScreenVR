# ScreenVR

ScreenVR транслирует экран Mac на Android-телефон с низкой задержкой. Телефон показывает поток в обычном режиме или в SBS/Cardboard-режиме с линзовой маской, чтобы использовать его как простой VR-дисплей.

```text
Mac ScreenCaptureKit -> ffmpeg H.264 RTP -> LAN UDP 5004 -> Android MediaCodec -> OpenGL SBS view
```

## Что Есть В Проекте

```text
android-app/ Android-приложение
server/      RTP-сервер трансляции с Mac
scripts/     сборка, установка, запуск и уборка
docs/        заметки по запуску и диагностике
tools/       диагностика и настройка ETS2
```

## Требования

- macOS
- Android-телефон с включенной USB-отладкой
- Android SDK с `adb`
- JDK 21
- Homebrew `ffmpeg`
- разрешение Screen & System Audio Recording для терминала или приложения, из которого запускается сервер

## Сборка Android

```bash
scripts/build-android.sh
```

Установка на подключенный телефон:

```bash
scripts/install-android.sh
```

Пакет приложения:

```text
dev.screenvr
```

## Рабочий Запуск Стрима

Основной рабочий путь сейчас: RTP/H.264 по локальной сети.

```bash
scripts/start-working-rtp.sh
```

Скрипт подставляет в Android-приложение рабочий URL:

```text
rtph264://0.0.0.0:5004?w=1200&h=800&fps=60
```

Текущий хороший профиль:

```text
1200x800, 60 fps, 3000k, queue-depth 3, screencapturekit
```

Если картинка не появилась сразу, нажми `Play` в Android-приложении.

## ETS2 И Head Tracking

В ветке с ETS2 телефон может работать как Bluetooth HID-джойстик для head tracking. Для подготовки настроек управления ETS2:

```bash
python3 tools/ets2-bthid/apply_controls.py
```

Хелпер ищет последнее S23 Bluetooth HID-устройство в `global_controls.sii` и прописывает его во все профили ETS2:

```text
trackiryaw   = joy2.x
trackirpitch = joy2.rx
trackirroll  = 0
```

Подробности по рабочему запуску: `docs/working-rtp-launch.md`.

## Диагностика

Проверки узких мест лежат в:

```text
tools/diagnostics/
```

Примеры:

```bash
python3 tools/diagnostics/pipeline_diagnostics.py processes
python3 tools/diagnostics/pipeline_diagnostics.py sck --size 900x600 --fps 60
python3 tools/diagnostics/pipeline_diagnostics.py rtp --dest 192.168.0.202 --size 900x600 --fps 60 --bitrate 3000k
```

## Уборка Рабочей Папки

```bash
scripts/clean-workspace.sh
```

Скрипт удаляет Gradle output, временные файлы, `.DS_Store` и пустые папки.
