# Giải thích code tra mã vạch (BR-77), JOB-02 và JOB-03

Tài liệu này giải thích ba phần:

| Phần | Là gì | Ai dùng |
|---|---|---|
| `GET /api/v1/products/barcode/{code}?storeId=` | BR-77: máy quét đọc ra một mã, API trả đúng 1 phụ tùng | Owner, Branch Manager, Staff |
| `LowStockWarningJob` (JOB-02) | 06:00 mỗi ngày báo BM phụ tùng dưới ngưỡng tồn tối thiểu (UC-51) | Chạy nền, không có API |
| `ExpiryWarningJob` (JOB-03) | 00:10 mỗi ngày đánh dấu lô hết hạn, báo BM lô hết hạn và lô sắp hết hạn (BR-76, UC-104) | Chạy nền, không có API |

Thông báo của hai job hiện ở màn thông báo sẵn có (`GET /api/v1/notifications`).

## 1. File mới và file đã sửa

Đường dẫn tính từ `src/main/java/fu/hl/g64/motorflow/`, trừ khi ghi khác.

### File mới

| File | Làm gì |
|---|---|
| `catalog/dto/BarcodeLookupResponse.java` | Response tra mã vạch: phụ tùng, trạng thái, kho đang xem và tồn khả dụng; không có giá |
| `common/config/SchedulingConfig.java` | Bật `@EnableScheduling`, chỉ khi `motoflow.jobs.enabled` khác `false` |
| `inventory/job/LowStockWarningJob.java` | JOB-02 |
| `inventory/job/ExpiryWarningJob.java` | JOB-03 |
| `inventory/helper/ExpiryWarning.java` | Đọc số ngày cảnh báo N của UC-104, dùng chung cho màn tồn kho và JOB-03 |
| `inventory/dto/LowStockRow.java` | Một phụ tùng dưới ngưỡng ở một kho |
| `inventory/dto/ExpiringLotRow.java` | Tồn khả dụng của một lô ở một kho, kèm SKU, hạn dùng, tên kho |
| `src/test/.../catalog/BarcodeLookupApiTest.java` | 9 test API tra mã vạch |
| `src/test/.../inventory/LowStockWarningJobTest.java` | 5 test JOB-02 |
| `src/test/.../inventory/ExpiryWarningJobTest.java` | 6 test JOB-03 |
| `postman/collections/MotoFlow - Iteration 2/Mã vạch (BR-77)/` | 6 request Postman dạng yaml |

### File đã sửa

| File | Sửa gì |
|---|---|
| `catalog/controller/ProductController.java` | Thêm endpoint `/barcode/{code}` |
| `catalog/service/ProductService.java`, `impl/ProductServiceImpl.java` | Thêm `findByBarcode` |
| `inventory/repository/StockBalanceRepository.java` | Thêm `findBelowThreshold` |
| `inventory/repository/LotRepository.java` | Thêm `findIdsToExpire`, `markExpired` |
| `inventory/repository/LotBalanceRepository.java` | Thêm `findNearExpiry`, `findAvailableOf` |
| `inventory/service/impl/InventoryServiceImpl.java` | `expiryWarningDays()` gọi `ExpiryWarning` thay vì tự đọc `pricing_policy`; kết quả không đổi |
| `operation/repository/NotificationRepository.java` | Thêm `findSent`: thông báo đã gửi theo loại, loại tham chiếu và người nhận |
| `src/main/resources/messages.properties` | Thêm `MSG91=Không tìm thấy phụ tùng có mã vạch {0}.` |
| `src/main/resources/application.properties` | Thêm `motoflow.jobs.enabled`, `motoflow.jobs.low-stock.cron`, `motoflow.jobs.expiry.cron` |
| `src/test/resources/application-test.properties` | `motoflow.jobs.enabled=false` |
| `postman/MotoFlow-Iteration2.postman_collection.json` | Thêm thư mục Mã vạch, biến `adminUsername` |

Không có migration mới, không đổi schema.

## 2. Tra mã vạch (BR-77)

- Mã được cắt khoảng trắng hai đầu rồi so **đúng nguyên chuỗi** với `product_identifier` loại `BARCODE`. Máy quét luôn trả đủ mã, nên không tìm gần đúng như ô tìm kiếm UC-13. Mã OEM không được nhận là mã vạch.
- Không có phụ tùng nào mang mã đó: 404 `MSG91`.
- Phụ tùng ngừng kinh doanh vẫn trả về, kèm `status`. Màn bán hàng tự chặn; R3 không cấm tra cứu.
- Kho đang xem:
  - BM và Staff luôn là kho của mình, tham số `storeId` bị bỏ qua. Người đứng quầy quét hàng ở chính kho mình. Điều này đúng cả với BM kho tổng, khác UC-13 nơi họ xem được mọi kho.
  - Owner: theo `storeId`. Không truyền thì `storeId` và `availableQty` là `null`.
  - Kho chưa có dòng `stock_balance` cho phụ tùng đó: `availableQty = 0`.
- Không trả giá hay giá vốn: màn bán hàng đã có API giá riêng.

## 3. Giờ chạy và lý do

| Job | Cron (giờ Việt Nam) | Lý do |
|---|---|---|
| JOB-02 | `0 0 6 * * *` (06:00) | Trước giờ mở cửa: BM vào ca là thấy ngay, kịp lập yêu cầu bổ sung trong ngày |
| JOB-03 | `0 10 0 * * *` (00:10) | Ngay sau nửa đêm: "hôm nay" đã sang ngày mới, nên lô hết hạn hôm qua bị đánh dấu trước khi cửa hàng bán. Lệch 10 phút để tránh đúng mốc 00:00 |

Cả hai `@Scheduled` đều có `zone = "Asia/Ho_Chi_Minh"`. "Hôm nay" trong code lấy từ bean `Clock` (cũng múi giờ Việt Nam), nên test đổi được ngày. Muốn đổi giờ chạy thì sửa property, không cần sửa code.

Mỗi job là một method `run()` có `@Transactional`. Method `scheduled()` chỉ gọi `run()`, để `run()` trả kết quả cho test kiểm. Mỗi lần chạy ghi 1 dòng log INFO.

## 4. Cách chống gửi lặp

Không thêm bảng. Job đọc lại bảng `notification` để biết đã báo gì.

### JOB-02: mỗi phụ tùng một thông báo

- Mỗi phụ tùng dưới ngưỡng ở một kho tạo 1 thông báo cho **từng** BM đang hoạt động của kho đó:
  - `notification_type = LOW_STOCK`, `ref_type = PRODUCT`, `ref_id = productId`;
  - message ví dụ: `"Phụ tùng MA-PHANH - Má phanh trước dưới ngưỡng tồn tối thiểu: còn 2, ngưỡng 5."`
- Không báo lại khi BM đó **còn thông báo LOW_STOCK chưa đọc** cho phụ tùng này. Mỗi BM chỉ thuộc một kho, nên cặp (BM, phụ tùng) đủ để phân biệt kho.
- BM đã đọc mà hàng vẫn dưới ngưỡng thì sáng hôm sau được báo lại. Đây là chủ ý: hàng chưa được bổ sung thì vẫn phải nhắc.
- Bỏ qua: phụ tùng ngừng kinh doanh, kho không ACTIVE, ngưỡng chưa đặt (`null`). Không gửi cho Owner, vì Owner xem được ở màn tồn kho.
- Vì sao không gom mỗi kho một thông báo: message chỉ dài 500 ký tự và chỉ liệt kê được vài SKU. Nếu so message để chống lặp thì sẽ bỏ sót khi chỉ phụ tùng thứ 6 trở đi thay đổi. Gửi theo từng phụ tùng thì không sót, và bấm vào là mở đúng phụ tùng.
- Cả job chỉ chạy 3 câu truy vấn để đọc dữ liệu: phụ tùng dưới ngưỡng, BM đang hoạt động, thông báo chưa đọc. Không truy vấn lặp theo từng dòng.

### JOB-03: mỗi lô một thông báo

- `ref_type = LOT`, `ref_id = lotId`, nên bấm vào là mở đúng lô (UC-98).
- **LOT_EXPIRED:** chỉ báo các lô vừa đổi sang EXPIRED **trong lần chạy này**. Lô đã EXPIRED thì không bị đánh dấu lại, nên tự nhiên chỉ được báo 1 lần.
  - Message ví dụ: `"Lô #12 (NHOT-10W40) đã hết hạn ngày 06/11/2026, còn 3 tại Long Biên, không được bán hoặc chuyển kho."`
- **NEAR_EXPIRY:** báo các cặp (lô, kho) còn hàng AVAILABLE với hạn trong `[hôm nay, hôm nay + N]`. Không báo lại khi BM đó **đã từng** nhận NEAR_EXPIRY cho lô này, dù đã đọc hay chưa. Hạn dùng không đổi, nên nhắc lại mỗi ngày chỉ gây nhiễu.
  - Lô được chia ra nhiều kho thì BM của từng kho đều được báo, mỗi người với số lượng ở kho mình.
  - Chưa đặt N (UC-104) thì bỏ qua phần này; phần đánh dấu hết hạn vẫn chạy.

## 5. Vì sao không trừ kho khi lô hết hạn

Job chỉ đổi `lot.status` từ `IN_STOCK` sang `EXPIRED` bằng 1 câu update hàng loạt (`LotRepository.markExpired`):

- Không động vào `lot_balance`, `stock_balance`, không tạo `stock_movement`. Hàng hết hạn vẫn **nằm thật trong kho** cho tới khi có người xử lý: trả nhà cung cấp, hủy, ghi giảm. Mỗi việc đó là một chứng từ riêng, có người chịu trách nhiệm và có giá vốn. Nếu job tự trừ kho thì số trên hệ thống lệch với hàng trên kệ, và kiểm kê sẽ ra chênh lệch giả.
- Hết hạn chỉ có nghĩa là **không được bán hoặc chuyển kho**.
  - `InventoryPostingServiceImpl.pick` chặn qua `Lot.isExpiredOn(today)`, tức chặn khi `status == EXPIRED` **hoặc** `expiryDate < today`.
  - Job chỉ đặt EXPIRED cho lô đã có `expiryDate < today`, nên hai điều kiện luôn cho cùng kết quả. MSG86 hoạt động như trước, kể cả khi job chưa kịp chạy.
  - Test `markedLotIsStillBlockedFromSaleAndListedAsExpired` chứng minh: sau khi job chạy, bán lô đó vẫn ra MSG86 và tab EXPIRED của UC-97 vẫn có lô.
- Lô đã `DEPLETED` (hết hàng) thì giữ nguyên. Nếu sau này lô có hàng trở lại, `refreshDepletion` đưa nó về `IN_STOCK` và lần chạy kế tiếp sẽ đánh dấu.
- LOT_EXPIRED và NEAR_EXPIRY chỉ tính phần hàng **AVAILABLE**. Hàng hỏng hay chờ kiểm vốn đã không bán được nên không báo.

## 6. Cách tắt job trong test

- `SchedulingConfig` có `@ConditionalOnProperty(name = "motoflow.jobs.enabled", havingValue = "true", matchIfMissing = true)`.
- `src/test/resources/application-test.properties` đặt `motoflow.jobs.enabled=false`. Khi đó `@EnableScheduling` không được nạp, nên không job nào tự chạy giữa lúc test.
- Test gọi thẳng `job.run()` rồi kiểm giá trị trả về và bảng `notification`. Ngày được cố định bằng `MutableClock` của test.
- Muốn tắt job ở một máy chạy thật (ví dụ chạy nhiều instance, chỉ để một máy chạy job): đặt biến môi trường `MOTOFLOW_JOBS_ENABLED=false`. Spring tự đọc biến này thay cho property.

## 7. Cách chạy Postman

1. Chạy `scripts/seed/seed_demo_data.sql` trên DB đã migrate.
2. Import collection Iteration 2. Chạy thư mục `0. Đăng nhập` trước, sau đó chạy thư mục `Mã vạch (BR-77)`.

| Request | Kiểm gì |
|---|---|
| BM quét mã đúng | Mã `8936012340011` ra `BUG-NGK-CPR8`, kho là kho tổng của `bm.kho`, có tồn, không có giá |
| Quét mã có khoảng trắng | `%20%208936012340011%20` vẫn khớp, `barcode` đã cắt khoảng trắng |
| Mã không tồn tại trả MSG91 | 404 `MSG91` |
| Owner không chọn kho | `storeId` và `availableQty` là `null` |
| Admin đăng nhập | Lưu `adminToken` (tài khoản `admin2` của dữ liệu demo) |
| Admin bị 403 | Admin không xem được dữ liệu nghiệp vụ |

Thư mục này chỉ đọc dữ liệu, chạy lại nhiều lần được. Job không có API nên không có request Postman. Muốn xem job chạy thật trên máy: tạm đặt cron vài giây, ví dụ `motoflow.jobs.low-stock.cron=*/30 * * * * *`, rồi xem log INFO.
