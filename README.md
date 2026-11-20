# Phone Controller v4 (USB) 📱🎮

ប្រើទូរស័ព្ទ Android ជា Controller សម្រាប់ PC តាម USB (latency ទាប)។

| Mode | ប្រើសម្រាប់ |
|---|---|
| **Gamepad (Xbox 360)** | Roblox, Minecraft Bedrock, ហ្គេមទាំងអស់ដែល support controller |
| **Minecraft Java** | keyboard + mouse ដោយមិនបាច់ mod |
| **Roblox (KB+Mouse)** | Roblox បែប keyboard/mouse |
| **Touchpad** | ទូរស័ព្ទជា touchpad (tap = click, 2 ម្រាម = scroll / right-click) |
| **Racing** | ហ្គេមឡាន៖ ងាកទូរស័ព្ទជាកង់ចង្កូត + pedal analog + ប៊ូតុងឡានទាំងអស់ |

## ថ្មីក្នុង v4
**App:** UI neon ស្អាតជាងមុន + 6 theme · LT/RT **analog** (អូសតាមបណ្តោយ bar) · **Rumble** (ហ្គេមញ័រ ទូរស័ព្ទញ័រ) · **Gyro aiming** (ប៊ូតុង GYRO) · **Touchpad mode** · latency (ms) · keyboard: Ctrl/Shift/Alt, Paste, ព្រួញ · Settings (sensitivity, haptic, rumble) · icon
**PC:** UI ថ្មីជា **HTML + CSS + JavaScript** (backend Python) · live gamepad · ADB/phone info (ម៉ាក, Android, battery) · Connect/Disconnect · Open app / Install APK · Console · Key mapping editor · sliders · Tools

## 🏎️ Racing mode (Gyropad សម្រាប់ហ្គេមឡាន)
ជ្រើស mode **RACING** → កាន់ទូរស័ព្ទដេក (landscape) ដូចកង់ចង្កូត ហើយ **ងាក** ដើម្បីបត់។ ចុច `CALIBRATE` ដើម្បីកំណត់ទីតាំងត្រង់។
**Pedal** ទាំងពីរជា analog៖ ចុច/អូសម្រាមចុះក្រោមលើ pedal កាន់តែចុះ = កាន់តែខ្លាំង។

| ប៊ូតុងលើ app | ផ្ញើជា (Xbox pad) | Keyboard output |
|---|---|---|
| ងាកទូរស័ព្ទ | Left stick X (ចង្កូត) | `A` / `D` |
| **GAS** | RT (analog) | `W` |
| **BRAKE / REVERSE** | LT (analog) | `S` |
| **HANDBRAKE** | A | `Space` |
| **NITRO** | B | `Left Shift` |
| **HORN** | X | `H` |
| **CAMERA** | Y | `C` |
| **SHIFT −  /  SHIFT +** | LB / RB | `Q` / `E` |
| **LIGHTS** | D-pad ឡើង | `L` |
| **SIGNAL <  /  SIGNAL >** | D-pad ឆ្វេង / ស្តាំ | `Z` / `X` |
| **LOOK BACK** | D-pad ចុះ | `V` |
| **RESET** | BACK | `R` |
| **PAUSE** | START | `Esc` |

- ហ្គេមដែលគាំទ្រ controller (Forza, NFS, Assetto Corsa, BeamNG, GTA, ETS2…) → ប្រើលំនាំដើម **Xbox pad**។
- ហ្គេមឡាន Roblox / ហ្គេមដែលប្រើ keyboard → បើក `Keyboard output (W A S D)` ក្នុង MENU ហើយកែ key ក្នុង UI របស់ PC (tab *Key mapping* → Racing)។
- MENU មាន៖ មុំងាកសម្រាប់ចង្កូតពេញ (តូច = រហ័ស), Analog pedals, Invert steering។
- ប្រសិនបើបត់ខុសទិស បើក `Invert steering`។

## PC server (web UI)
`PhoneControllerServer.exe` បើកជាបង្អួច native (Edge WebView2)។ បើគ្មាន វាបើក browser ជំនួស។
ឬ Python: `cd pc-server && pip install -r requirements.txt` ហើយ `python app.py`
(បង្អួច native: `pip install -r requirements-ui.txt` · បង្ខំ browser: `python app.py --browser` · គ្មាន UI: `python server.py`)

## 1) Build លើ GitHub
1. Push folder នេះទៅ branch `main` → tab **Actions**
2. ទាញ `PhoneController-apk` និង `PhoneController-PC-server` (zip: exe + config + adb)
3. Release: `git tag v4.0 && git push origin v4.0`

## 2) PC (Windows)
1. ដំឡើង **ViGEmBus**: https://github.com/nefarius/ViGEmBus/releases
2. Unzip `PhoneController-PC.zip` → រត់ `PhoneControllerServer.exe` (adb នៅក្នុង `adb/` រួចហើយ)
3. បើហ្គេមរត់ជា Administrator → ត្រូវរត់ server ជា Administrator ដែរ

## 3) ប្រើ
1. ទូរស័ព្ទ: បើក **USB debugging** → ភ្ជាប់ USB → Allow
2. PC: ជ្រើសទូរស័ព្ទ → **Connect** → បើក app (ឬ *Open app on phone*)
3. ឃើញ `App connected` ✔ ហើយលើទូរស័ព្ទបង្ហាញ `● Connected · xx ms`

### ប៊ូតុងលើ app
`KEYS` វាយអក្សរ · chip ឈ្មោះ mode (ចុចប្តូរ) · `MOUSE ON/OFF` (Gamepad: stick ស្តាំ = cursor) · `GYRO` · `MENU` (theme, sensitivity, rumble)

### Key mapping
កែក្នុង UI (tab *Key mapping*) ឬ `config.json`។ តម្លៃ: `w..z`, `0-9`, `f1-f12`, `space`, `lshift`, `lctrl`, `esc`, `tab`, `enter`, `slash`, `up/down/left/right`, `mouse:left|right|middle`, `scroll:up|down`។
