# Khoảng Cách Xe — đo khoảng cách tới xe phía trước bằng camera sau

Ứng dụng Android dùng camera sau của điện thoại để phát hiện xe phía trước,
tính khoảng cách (mét) và hiển thị trực tiếp trên màn hình camera, kèm tốc độ
tiếp cận và cảnh báo va chạm.

Tầm đo: **0 – 110 m**. Xa hơn app hiện `> 110 m` thay vì đưa ra con số không
đáng tin.

## Build ra file APK (không cần cài Android Studio)

1. Tạo repository mới trên GitHub (để Public hoặc Private đều được).
2. Tải toàn bộ thư mục này lên repo, nhánh `main`.
3. Vào tab **Actions** → workflow **Build APK** chạy tự động (hoặc bấm
   *Run workflow*).
4. Chờ khoảng 4–6 phút → mở lần chạy vừa xong → mục **Artifacts** →
   tải `KhoangCachXe-debug-apk`.
5. Giải nén, copy `app-debug.apk` vào điện thoại, bật *Cài từ nguồn không xác
   định* rồi cài.

## Cách dùng

- Gắn điện thoại lên kính lái, **nằm ngang** (app khoá chế độ landscape),
  camera sau hướng thẳng về phía trước.
- Cấp quyền **Camera** (bắt buộc) và **Vị trí** (không bắt buộc — dùng để lấy
  tốc độ xe mình từ GPS và tính khoảng cách an toàn theo quy tắc 2 giây).
- Màn hình hiển thị:
  - Số lớn góc trên trái: khoảng cách tới xe phía trước (mét).
  - Dòng nhỏ: đang tiến gần / giãn ra bao nhiêu km/h, thời gian va chạm dự kiến,
    khoảng cách an toàn, tốc độ xe mình.
  - Khung xanh quanh xe phía trước; chuyển đỏ + kêu bíp khi nguy hiểm.

## Nguyên lý tính khoảng cách

Mô hình camera lỗ kim:

```
khoảng_cách = (bề_ngang_thực_của_xe × tiêu_cự_pixel) / bề_ngang_khung_bao_pixel
```

Tiêu cự pixel lấy từ thông số phần cứng camera (`LENS_INFO_AVAILABLE_FOCAL_LENGTHS`
và `SENSOR_INFO_PHYSICAL_SIZE`) nên tự đúng theo từng máy.

## Ba điểm quyết định độ nhạy khi cả hai xe đang chạy

**1. Bộ lọc Kalman thay cho trung bình trượt.**
Trung bình trượt (EMA) luôn bám trễ sau giá trị thật: càng làm mượt càng trễ, nên
đúng lúc xe trước phanh gấp thì số hiển thị lại là số của 1–2 giây trước. App dùng
bộ lọc Kalman 2 trạng thái `[khoảng cách, tốc độ thay đổi]`: nó dự đoán trước bằng
vận tốc rồi mới hiệu chỉnh bằng số đo, nên gần như không trễ mà vẫn khử nhiễu.
Nhiễu đo được mô hình đúng theo vật lý:

```
sai_số(d) = d² × sai_số_pixel / (bề_ngang_thực × tiêu_cự_pixel)
```

Nghĩa là ở 20 m bộ lọc tin số đo (sai vài chục cm), ở 100 m nó tin dự đoán nhiều
hơn (sai vài mét). Số đo lệch quá 4 lần độ lệch chuẩn bị loại bỏ — tránh nhảy số
khi nhận diện bắt nhầm biển báo hay xe làn bên.

**2. Độ phân giải cao + vùng cắt phóng to để với tới 110 m.**
Ở 110 m một chiếc xe con chỉ rộng khoảng 26 pixel trên khung 1920, và chỉ 9 pixel
trên khung 640 — không thể đo. App vì vậy chạy phân tích ở 1920×1080 và mỗi khung
hình quét hai lần:

- Quét toàn khung: bắt xe ở gần và trung bình.
- Quét vùng cắt ~34%×42% bám theo vị trí xe lần trước: xe ở xa được phóng to
  khoảng 3 lần nên vẫn nhận diện được.

Kết quả từ vùng cắt chỉ được dùng khi khung bao ở quét toàn khung quá nhỏ.

**3. Hiển thị kèm sai số.** Dòng chi tiết luôn hiện `± x.x m` do bộ lọc tự tính,
để biết con số đang đáng tin tới mức nào. Vượt quá 110 m thì hiện `> 110 m` thay
vì đưa ra số vô nghĩa.

## Chế độ 2: đo khoảng cách tới vật bất kỳ

Bấm nút **Chế độ** ở góc trên phải để chuyển giữa hai chế độ.

Ở chế độ **Vật bất kỳ** (đo tivi, cửa, tủ, người, thùng hàng...):

1. Nhập **bề ngang thật của vật** theo cm, hoặc chọn nhanh trong danh sách
   (TV 32/43/50/55/65 inch, màn hình 24 inch, cửa đi, người, tủ lạnh, xe máy, giấy A4).
   Với tivi, bề ngang là chiều ngang của cả màn hình — TV 55 inch khoảng 122 cm.
2. **Chạm vào vật trên màn hình** để chọn. App khoá vào vật đó và bám theo, số đo
   hiện ngay góc trên trái. Dưới 1 m hiện theo cm, dưới 10 m hiện 2 chữ số thập phân.

Nếu bộ nhận diện không bắt được vật (tường, mép bàn, khung cửa, vật quá phẳng),
bật **Thước kẹp thủ công**: hai vạch vàng xuất hiện, kéo mỗi vạch trùng với một
mép của vật, app đo bằng khoảng cách giữa hai vạch. Cách này đo được mọi thứ,
miễn là bạn biết kích thước thật của nó.

Chế độ vật thể dùng chung hệ số hiệu chỉnh với chế độ xe, nên hiệu chỉnh một lần
là cả hai chế độ đều đúng. Ở chế độ này app không cảnh báo va chạm và không kêu bíp.

Độ chính xác ở gần rất tốt: đo một chiếc tivi cách 3 m thường sai dưới 5 cm, vì
ở khoảng cách gần vật chiếm nhiều pixel. Sai số tăng nhanh theo bình phương
khoảng cách, đúng như dòng `± x.xx m` hiển thị kèm.

## Hiệu chỉnh tự động bằng GPS (nên làm một lần)

Đúng với tình huống có xe đứng yên phía trước:

1. Khi đang tiến tới một xe **đang đứng yên**, khung xanh đã bám được xe đó →
   bấm **Hiệu chỉnh tự động bằng GPS** (mốc 1).
2. Chạy tới gần thêm ít nhất 10 m → bấm lần 2.

App lấy quãng đường GPS đã đi được `Δs` và hai bề ngang khung bao `w1`, `w2` để
giải trực tiếp hệ số `k` trong `d = k / w`:

```
k = Δs / (1/w1 − 1/w2)
```

Cách này cho hệ số đúng mà **không cần biết bề ngang thật của xe trước**, chính
xác hơn nhiều so với kéo thanh trượt bằng mắt. Làm một lần trên mỗi điện thoại là đủ.

## Chỉnh tay

- Ba nút **Xe con / SUV / Tải** đặt nhanh bề ngang xe trước (1.80 / 1.95 / 2.45 m).
  Chọn đúng loại xe đang bám là yếu tố ảnh hưởng lớn nhất tới sai số.
- Thanh **Hiệu chỉnh** (0.70–1.30) tinh chỉnh thêm nếu cần.

## Giới hạn cần biết

- Đây là ước lượng bằng **một camera**, không phải radar/LiDAR. Sai số thực tế
  khoảng 5–10% ở dưới 40 m, 10–20% ở 60–110 m sau khi đã hiệu chỉnh.
- Nhận diện được tới 110 m cần trời sáng, kính lái sạch, xe trước đủ lớn và nằm
  giữa khung hình. Trời tối, mưa, ngược sáng thì tầm nhận diện tụt xuống 50–70 m.
- Bộ nhận diện là ML Kit Object Detection (mô hình tổng quát), vẫn có thể bắt nhầm
  biển báo hoặc xe làn bên. Muốn chắc hơn thì thay bằng mô hình TFLite chuyên phát
  hiện xe (SSD MobileNet / YOLO COCO) để lọc đúng nhãn `car/truck/bus`.
- Phân tích 1080p hai lần mỗi khung hình khá nặng: máy tầm trung chạy khoảng
  8–15 khung/giây, đủ dùng nhưng máy quá yếu sẽ giật.
- **Không dùng thay cho quan sát của người lái.**

## Cấu trúc

```
app/src/main/java/com/hi/khoangcachxe/
  MainActivity.kt        camera 1080p, nhận diện, bám mục tiêu, GPS, hiển thị
  DistanceTracker.kt     bộ lọc Kalman khoảng cách + tốc độ tương đối
  OverlayView.kt         vẽ khung bao và nhãn lên preview
.github/workflows/build.yml   build APK tự động
```
