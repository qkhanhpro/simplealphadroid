# SimpleAlphaDroid



https://github.com/user-attachments/assets/56512408-1b0d-452b-8be5-d4a56ebab3f8



[English](#english) · [Tiếng Việt](#tiếng-việt)

## English

Control older Sony cameras remotely by connecting them to an Android device through USB.

```
 ┌──────────┐   USB cable   ┌──────────────┐   Wi-Fi or hotspot   ┌────────────────┐
 │  Sony    │◄─────────────►│ Android      │ ))) ~ ~ ~ ~ ~ ~ (((  │ Second device  │
 │  camera  │               │ phone        │                      │ (any browser)  │
 └──────────┘               │ (this app)   │                      └────────────────┘
                            └──────────────┘                       see the picture,
                                                                   tap to focus, take
                                                                   the shot: selfies,
                                                                   group photos, ...
```

### Features

- See what the camera sees on the second device
- Tap the picture to pick the focus point
- Separate focus button (back-button focus)
- Change shutter speed, aperture, ISO and exposure
- Every photo is copied to the phone right away, and you can look at it on the second device
- Copy photos from the camera's card to the phone
- Nothing to install on the second device, just open the browser
- No Sony account, no internet needed
- Vietnamese and English

On first start the app shows the camera settings you need.

### ⚠️ Disclaimer

- **Known to work with the Sony A6400 only.** Only tested with a few Android phones.
- **This is vibe-coded.** Don't expect production quality.
- **I may or may not maintain this repo.**

### Thanks

The camera commands come from Sony's own apps for Android and Windows, cross-checked with these open-source projects:

- [libgphoto2](https://github.com/gphoto/libgphoto2)
- [alphawire](https://github.com/watsonmw/alphawire)

The QR codes use the [QR Code generator library](https://github.com/nayuki/QR-Code-generator) by Project Nayuki (MIT).

---

## Tiếng Việt

Điều khiển máy ảnh Sony đời cũ từ xa bằng cách cắm vào điện thoại Android qua cáp USB.

```
 ┌──────────┐   Cáp USB     ┌──────────────┐   Wi-Fi / điểm phát   ┌────────────────┐
 │ Máy ảnh  │◄─────────────►│ Điện thoại   │ ))) ~ ~ ~ ~ ~ ~ (((   │ Thiết bị       │
 │ Sony     │               │ Android      │                       │ thứ hai        │
 └──────────┘               │ (app này)    │                       │ (trình duyệt)  │
                            └──────────────┘                       └────────────────┘
                                                                    xem hình, chạm để
                                                                    lấy nét, bấm chụp:
                                                                    selfie, ảnh nhóm, ...
```

### Tính năng

- Xem hình từ máy ảnh trên thiết bị thứ hai
- Chạm vào hình để chọn điểm lấy nét
- Nút lấy nét riêng (lấy nét bằng nút sau)
- Chỉnh tốc độ, khẩu độ, ISO và bù sáng
- Ảnh chụp được chép ngay sang điện thoại, xem được trên thiết bị thứ hai
- Chép ảnh từ thẻ nhớ máy ảnh sang điện thoại
- Không cần cài gì trên thiết bị thứ hai, chỉ cần mở trình duyệt
- Không cần tài khoản Sony, không cần internet
- Tiếng Việt và tiếng Anh

Lần đầu mở, app sẽ hiện các cài đặt cần chỉnh trên máy ảnh.

### ⚠️ Lưu ý

- **Mới biết chạy được với Sony A6400.** Chỉ thử trên vài điện thoại Android.
- **Code kiểu "vibe".** Đừng mong chất lượng như sản phẩm thật.
- **Có thể mình sẽ duy trì repo này, cũng có thể không.**

### Cảm ơn

Các lệnh điều khiển máy ảnh lấy từ app của Sony trên Android và Windows, và được kiểm tra chéo với các dự án mã nguồn mở này:

- [libgphoto2](https://github.com/gphoto/libgphoto2)
- [alphawire](https://github.com/watsonmw/alphawire)

Mã QR dùng thư viện [QR Code generator](https://github.com/nayuki/QR-Code-generator) của Project Nayuki (MIT).

---

Build: `./gradlew assembleDebug` (JDK 17+, Android SDK). License: [GPL-3.0](LICENSE), see [THIRD_PARTY.md](THIRD_PARTY.md).
Sony and Alpha are trademarks of Sony Group Corporation. Not affiliated with Sony.
