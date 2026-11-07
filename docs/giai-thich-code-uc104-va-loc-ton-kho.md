# Giải thích code UC-104 và lọc tồn kho (UC-47)

Tài liệu này mô tả phần BE mới của màn hình tồn kho: cấu hình số ngày cảnh báo hết hạn (UC-104), các bộ lọc mới của danh sách tồn, 4 số tổng và danh sách khu vực.

| API | Dùng cho | Ai được gọi |
|---|---|---|
| `GET /api/v1/inventory/expiry-warning` | UC-104: xem cấu hình | Owner |
| `PUT /api/v1/inventory/expiry-warning` | UC-104: đặt số ngày cảnh báo | Owner |
| `GET /api/v1/inventory/stock` | UC-47: danh sách tồn, thêm tham số mới | Owner, Branch Manager |
| `GET /api/v1/inventory/stock/summary` | UC-47: 4 số tổng | Owner, Branch Manager |
| `GET /api/v1/inventory/stock/storage-areas` | UC-47: danh sách khu vực cho ô lọc | Owner, Branch Manager |

Vai trò khác nhận 403. Không có migration mới: cột `expiry_warning_days` đã có sẵn trong `V1`.

## 1. File mới và file đã sửa

Đường dẫn tính từ `src/main/java/fu/hl/g64/motorflow/inventory/`, trừ khi ghi khác.

### File mới

| File | Làm gì |
|---|---|
| `dto/ExpiryWarningRequest.java` | Body của `PUT /expiry-warning` |
| `dto/ExpiryWarningResponse.java` | Response UC-104, gồm record con `Version` |
| `dto/StockSummaryResponse.java` | 4 số tổng của màn hình tồn kho |
| `service/ExpiryWarningService.java` | Interface của UC-104 |
| `service/impl/ExpiryWarningServiceImpl.java` | Đọc và lưu cấu hình UC-104 |
| `src/test/.../inventory/ExpiryWarningApiTest.java` | Test API của UC-104 |

### File đã sửa

| File | Sửa gì |
|---|---|
| `controller/InventoryController.java` | Thêm 4 endpoint, thêm tham số của `/stock` |
| `dto/StockSearch.java` | Thêm các bộ lọc, `scope(...)`, `hidesZeroStock()` |
| `dto/StockRowResponse.java` | Thêm `productStatus`, `onHandQty` |
| `entity/StockBalance.java` | Thêm `onHandQty()` |
| `repository/StockBalanceRepository.java` | Thêm `findStorageAreas` |
| `service/InventoryService.java` | Thêm `stockSummary`, `storageAreas` |
| `service/impl/InventoryServiceImpl.java` | Tách điều kiện lọc thành `stockScope`, `hasAvailable`, `lowStock`, `unavailable`, `expiryAlert` |
| `pricing/entity/PricingPolicy.java` | Thêm factory `expiryWarning`. Không đổi code khác của module `pricing` |
| `src/test/.../inventory/InventoryApiTest.java` | Thêm test lọc, số tổng, khu vực |
| `postman/` | Thêm folder `Cảnh báo hết hạn (UC-104)` và `Tồn kho (UC-47)` |

## 2. UC-104: cấu hình số ngày cảnh báo

### `GET /inventory/expiry-warning`

```json
{
  "inEffect": { "days": 30, "effectiveFrom": "2026-11-07T14:10:53.226", "setBy": 1, "setByName": "Chủ chuỗi" },
  "waiting": { "days": 60, "effectiveFrom": "2026-12-01T00:00:00", "setBy": 1, "setByName": "Chủ chuỗi" },
  "history": []
}
```

- `inEffect`: phiên bản đang áp dụng, `null` nếu chưa có.
- `waiting`: phiên bản chờ tới ngày, `null` nếu không có.
- `history`: tối đa 20 phiên bản mới nhất, mới nhất trước, **gồm cả phiên bản đang chờ**. FE không nên cộng `waiting` vào đầu danh sách này.

### `PUT /inventory/expiry-warning`

```json
{ "days": 30, "effectiveDate": "2026-11-07" }
```

Response giống `GET`.

| Trường | Quy tắc | Lỗi |
|---|---|---|
| `days` | Bắt buộc, từ 1 đến 365 | 400 `MSG02` |
| `effectiveDate` | Bắt buộc, định dạng `yyyy-MM-dd` | 400 `MSG02` |
| `effectiveDate` | Từ hôm nay trở đi | 400 `MSG125` |
| `effectiveDate` | Năm không quá 9999 | 400 `MSG02` |

Cách lưu giống `setSalesLimit` của pricing:

1. Giữ khoá `EXPIRY_WARNING`, để hai lần lưu cùng lúc chạy lần lượt.
2. Tính thời điểm bắt đầu: hôm nay thì áp dụng ngay, ngày sau thì từ 00:00 ngày đó.
3. Nếu lưu cho ngày sau, xoá phiên bản đang chờ. Mỗi lúc chỉ có một phiên bản chờ.
4. Lưu phiên bản mới, ghi `audit_log` với action `EXPIRY_WARNING_SET`. Giá trị bị thay nằm trong `old_data`.

Lưu cho hôm nay thì phiên bản đang chờ vẫn giữ nguyên.

Tab `NEAR_EXPIRY` của màn hình lô và `nearExpiryQty` của `/stock` đọc cấu hình đang áp dụng, nên đổi cấu hình có tác dụng ngay, không cần chạy job.

Giới hạn năm 9999 chặn trường hợp client gửi số thay vì chuỗi ở `effectiveDate`. Số đó được đọc thành số ngày tính từ 1970, ra một năm rất lớn, và PostgreSQL từ chối khi ghi. Không chặn thì API trả 500.

## 3. UC-47: danh sách tồn kho

### Tham số mới của `GET /inventory/stock`

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `storageArea` | Không lọc | Đúng khu vực, không phân biệt hoa thường. `Kệ` không khớp `Kệ A` |
| `includeZero` | `false` | `true` hiện cả dòng có `availableQty = 0` |
| `unavailableOnly` | `false` | Chỉ dòng có hàng chờ kiểm, sai hàng, hỏng hoặc chờ trả NCC |
| `expiryAlertOnly` | `false` | Chỉ dòng có hàng khả dụng trong lô đã hết hạn hoặc trong khoảng cảnh báo |

`q` tìm thêm mã OEM và ô kệ, ngoài SKU và tên phụ tùng. Barcode không nằm trong phạm vi tìm. Các tham số cũ giữ nguyên. Nhiều bộ lọc bật cùng lúc thì dòng phải thoả tất cả.

### Field mới của mỗi dòng

- `productStatus`: `ACTIVE` hoặc `DISCONTINUED`.
- `onHandQty`: khả dụng + chờ kiểm + sai hàng + hỏng + chờ trả NCC. Không gồm hàng đang chuyển, vì hàng đó chưa thuộc kho nào.

### Vì sao ẩn dòng khả dụng 0

Thay đổi này **đổi hành vi mặc định** của `/stock`. Dòng `stock_balance` có thể tồn tại mà không có hàng, ví dụ khi đặt ngưỡng hoặc vị trí trước lần nhập đầu tiên. Các dòng đó làm dài danh sách mà không có gì để xem, nên mặc định bị ẩn.

Quy tắc ẩn **không áp dụng** khi bật `lowStockOnly`, `unavailableOnly` hoặc `expiryAlertOnly`:

- Mã khả dụng 0 có ngưỡng là mã cần bổ sung gấp nhất, phải hiện ở `lowStockOnly`.
- Mã chỉ còn hàng hỏng có khả dụng 0, nhưng đó chính là thứ `unavailableOnly` cần tìm.
- `expiryAlertOnly` luôn có hàng khả dụng, nên quy tắc này không ảnh hưởng.

### Cùng một điều kiện hết hạn

`expiryAlertOnly` dùng cùng điều kiện với `sumExpiryByStoreAndProduct`, câu tính `nearExpiryQty` và `expiredQty`. Vì vậy dòng được lọc ra luôn có một trong hai số này lớn hơn 0.

Điều kiện được viết thành subquery trên `lot_balance` và `lot`: hàng `AVAILABLE`, số lượng lớn hơn 0, lô có hạn dùng, và hạn nằm trong khoảng cảnh báo hoặc `lot.status = EXPIRED`. Chưa cấu hình số ngày thì chỉ lô đã hết hạn được tính.

## 4. 4 số tổng và danh sách khu vực

### `GET /inventory/stock/summary`

Tham số: `storeId`, `q`, `categoryId`, `storageArea`.

```json
{ "withStock": 120, "lowStock": 8, "unavailable": 3, "expiryAlert": 5 }
```

| Số | Đếm dòng có | Khớp với |
|---|---|---|
| `withStock` | `onHandQty > 0` | Các dòng `onHandQty > 0` của `/stock?includeZero=true` |
| `lowStock` | Có ngưỡng và khả dụng dưới ngưỡng | `totalItems` của `/stock?lowStockOnly=true` |
| `unavailable` | Có hàng không khả dụng | `totalItems` của `/stock?unavailableOnly=true` |
| `expiryAlert` | Có hàng trong lô hết hạn hoặc sắp hết hạn | `totalItems` của `/stock?expiryAlertOnly=true` |

Số tổng đếm trên toàn bộ phạm vi lọc, không theo trang. Mỗi số là một câu `count` trong DB, không tải dòng về Java. Danh sách và số tổng dùng chung `stockScope` và các điều kiện lọc, nên không lệch nhau.

FE cần lưu ý:

- Một dòng có thể nằm ở nhiều số, nên không cộng 4 số lại.
- `withStock` có thể khác `totalItems` của `/stock` mặc định, vì danh sách mặc định chỉ lấy khả dụng lớn hơn 0.
- Dòng chỉ có hàng đang chuyển không được tính vào `withStock`.

### `GET /inventory/stock/storage-areas?storeId=`

Trả mảng chuỗi không trùng, xếp A đến Z, bỏ giá trị trống:

```json
["Kệ A", "Kệ B"]
```

Phạm vi kho giống `/stock`: Owner không truyền `storeId` thì lấy mọi kho. BM nhận kho của mình, truyền kho khác nhận 403 `MSG05`. Owner truyền kho không tồn tại nhận 404 `MSG52`.

## 5. Test đã thêm

`ExpiryWarningApiTest`:

- Chưa có cấu hình trả `null` và lịch sử rỗng.
- Đặt 30 ngày cho hôm nay: lô còn 10 ngày hiện ở tab `NEAR_EXPIRY` và `nearExpiryQty` lớn hơn 0.
- Bản cho ngày mai nằm ở `waiting`, tới ngày thì thành `inEffect`.
- Lưu 2 lần cùng ngày tương lai còn 1 bản chờ, `audit_log` có giá trị bị thay.
- Lịch sử dừng ở 20 dòng.
- `days` bằng 0 và 366, thiếu ngày, ngày quá khứ (`MSG125`), số ở `effectiveDate`.
- BM và Staff nhận 403.

`InventoryApiTest`:

- Lọc `storageArea` không phân biệt hoa thường.
- Mặc định ẩn dòng khả dụng 0, `includeZero=true` và `lowStockOnly=true` hiện lại.
- `unavailableOnly` hiện dòng khả dụng 0 có hàng hỏng.
- `expiryAlertOnly` khi có cấu hình, khi không có, và với lô đánh dấu `EXPIRED`.
- `q` tìm OEM và ô kệ, không tìm barcode.
- `productStatus` là `DISCONTINUED`, `onHandQty` không gồm hàng đang chuyển.
- `storage-areas` không trùng, đúng kho, chặn BM kho khác.
- 4 số tổng bằng số dòng của danh sách, và đi theo bộ lọc và kho.

## 6. Cách chạy Postman

1. Migrate DB rồi chạy `scripts/seed/seed_demo_data.sql`.
2. Chạy app.
3. Import `postman/MotoFlow-Iteration2.postman_collection.json` và chạy cả collection bằng Runner, đúng thứ tự từ trên xuống.

Folder `Cảnh báo hết hạn (UC-104)` ghi một phiên bản chờ cho ngày `2099-01-01`, không đổi số ngày đang áp dụng, nên chạy lại được nhiều lần.
