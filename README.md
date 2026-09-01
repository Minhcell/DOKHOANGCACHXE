# Khoảng Cách Vật Thể — Đo khoảng cách tới vật chỉ bằng camera

Quay camera vào **bất cứ vật thể nào** (xe, người, tivi, quạt, tủ, ghế...) → app **tự động nhận diện loại vật** → tính **khoảng cách chính xác từ bạn tới vật** theo **mét**.

Không cần nhập kích thước, không cần kéo thước kẹp, không cần hiệu chỉnh — chỉ quay camera là có số.

## Build ra file APK (không cần cài Android Studio)

1. Tạo repository GitHub mới.
2. Tải toàn bộ thư mục này lên, nhánh `main`.
3. Tab **Actions** → workflow **Build APK** chạy tự động (~5 phút).
4. Tải artifact `KhoangCachXe-debug-apk` → cài `app-debug.apk`.

## Cách dùng

1. Cấp quyền **Camera**.
2. **Gắn máy lên kính lái hoặc cầm ngang** — app hỗ trợ cả hai.
3. **Quay vào vật cần đo** — app hiển thị ngay:
   - **Số lớn:** khoảng cách từ bạn tới vật (mét).
   - **Dòng nhỏ:** loại vật, sai số ± m, bề ngang vật (cm).
   - **Khung xanh:** vật đang được đo.

Vật nào cũng được: ô tô, người, con mèo, tivi, quạt, tủ, cánh cửa, biển báo, cây...

**Tầm đo:** 1 m - 110 m (chất lượng tốt tới 50 m, ở xa hơn sai số lớn hơn).

## Đo vật ở xa (tới 110 m)

App quét hai lần mỗi khung hình:

1. **Quét toàn khung** — bắt vật gần.
2. **Quét vùng cắt giữa khung hình** (phóng to ~3x) — bắt vật ở xa mà toàn khung không bắt được.

Vậy nếu bạn quay vào xe cách 50–110 m:
- Xe quá nhỏ trên toàn khung → ML Kit bỏ lỡ.
- Nhưng trên vùng cắt phóng to 3x, xe bình thường → ML Kit bắt được.
- App dùng kết quả từ vùng cắt để tính khoảng cách.

Cách này cho phép đo tới **110 m** mà vẫn nhận diện được vật.

## Nguyên lý hoạt động

1. **ML Kit phát hiện vật thể** (quét 2 lần: toàn khung + vùng cắt phóng to) → loại vật (person, car, cat, chair...) + khung bao.
2. **Tra cứu bề ngang tiêu chuẩn** của loại vật từ database 80 loại COCO.
3. **Tính khoảng cách** bằng công thức camera: `d = (W × pixel_width) / (2 × f × tan(θ))`.
4. **Làm mượt** bằng bộ lọc Kalman 2 trạng thái để số đo không nhảy.

Bề ngang tiêu chuẩn của một số loại:
- Người: 45 cm
- Xe con: 1.80 m
- Xe tải: 2.50 m
- Tivi 55 inch: 1.22 m
- Quạt: 40 cm
- Ghế: 45 cm
- Cửa đi: 82 cm

## Độ chính xác

Sau khi app nhận diện loại vật, sai số thường:
- **Dưới 2 m:** ± 5–10% (vật chiếm nhiều pixel).
- **2–10 m:** ± 10–15%.
- **10–50 m:** ± 15–20%.
- **Trên 50 m:** ± 20–30% (phụ thuộc vào điều kiện ánh sáng, chất lượng ảnh).

Độ chính xác phụ thuộc vào:
- Tiêu cự camera (được tính từ thông số phần cứng).
- Bề ngang tiêu chuẩn của vật — nếu vật lệch so với tiêu chuẩn thì sai.
- Khoảng cách camera — ở gần hơn thì chính xác hơn.
- Ánh sáng, sạch kính lái, vật nằm giữa khung.

## Giới hạn

- Chỉ hoạt động được với **vật nằm trong 80 loại COCO** (xe, người, động vật, nội thất, vật dụng thông dụng). Vật quá hiếm (thiết bị chuyên dụng) sẽ không nhận diện.
- Sai số tăng nếu **máy lắc, bóng mờ, trời tối, ngược sáng**.
- **Vật lệch rìa khung hình** sẽ bị loại để tránh sai số hình học.
- **Không thể đo được vật mà ML Kit không nhận diện** — nếu app báo "không thấy", có thể:
  - Vật quá nhỏ hoặc quá xa.
  - Vật bị che phủ.
  - Vật không nằm trong 80 loại COCO.

## Cấu trúc

```
app/src/main/java/com/hi/khoangcachxe/
  MainActivity.kt       camera, nhận diện (2 lần quét), tính toán, hiển thị
  DistanceEstimator.kt  bộ lọc Kalman + tính khoảng cách
  ObjectSize.kt         bề ngang tiêu chuẩn của 80 loại vật COCO
  OverlayView.kt        vẽ khung bao lên preview
.github/workflows/build.yml   build APK tự động
```

## Tự thêm loại vật mới

Nếu loại vật nào không có bề ngang đúng, sửa `ObjectSize.kt`:

```kotlin
"teddy bear" to 0.35f,  // thêm loại mới với bề ngang (mét)
```

Sau đó commit + push → workflow tự build lại APK.
