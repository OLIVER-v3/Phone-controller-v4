# Protocol v4
TCP 127.0.0.1:5555 (via `adb reverse`), one JSON object per line, both directions.

## Phone -> PC
**State** (continuous, resent every 500 ms as keep-alive)
`{"t":"s","mode":"pad|minecraft|roblox|touchpad|racing","ms":0,"kb":0,"lx":0,"ly":0,"rx":0,"ry":0,"lt":0.0,"rt":0.0,"b":["A","LB"]}`

| field | meaning |
|---|---|
| mode | `pad` = virtual Xbox pad · `minecraft` / `roblox` = keyboard + mouse profile · `touchpad` = mouse only |
| kb | Racing mode: 1 = keyboard output (profile `racing`: A/D steer, W gas, S brake), 0 = Xbox pad (steering = left stick X) |
| ms | Gamepad mode: 1 = mouse ON (right stick = cursor, RT = left click, LT = right click) |
| lx, ly, rx, ry | sticks -1..1 (up = positive). Right stick already includes gyro aiming |
| lt, rt | triggers 0..1 (analog in Gamepad mode, 0/1 otherwise) |
| b | held buttons: A B X Y LB RB START BACK UP DOWN LEFT RIGHT LS RS |

**Events** (reliable)
- `{"t":"key","k":"esc"}` tap · `{"t":"key","k":"lctrl","d":1|0}` hold / release
- `{"t":"text","s":"សួស្តី","enter":true}` type unicode text
- `{"t":"mv","dx":5,"dy":-3}` relative mouse move (touchpad)
- `{"t":"scroll","d":1}` wheel notch (+ = up) · `{"t":"click","b":"left|right"}` · `{"t":"mb","b":"left","d":1|0}` hold button
- `{"t":"ping","ts":<ms>,"p":<last rtt>}`

## PC -> Phone
- `{"t":"hello","v":4}` · `{"t":"pong","ts":<ms>}` · `{"t":"rumble","l":0..1,"r":0..1}`
