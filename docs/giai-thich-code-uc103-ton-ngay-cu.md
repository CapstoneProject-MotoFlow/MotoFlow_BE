# Giải thích code tra tồn kho ngày đã qua (UC-103)

Tài liệu này giải thích API tra tồn kho cuối một ngày trong quá khứ của một kho. Căn cứ là R3 bản 05/11 (UC-103, BR-87) và quyết định của nhóm ngày 29/10 (bỏ bảng tồn theo ngày `daily_stock_balance` và JOB-04).

| API | Dùng cho |
|---|---|
| `GET /api/v1/inventory/past-date-stock` | UC-103: tồn cuối ngày D của từng phụ tùng trong một kho |

## 1. API, tham số, quyền, response

### Tham số

| Tham số | Bắt buộc | Ý nghĩa |
|---|---|---|
| `date` | Có | Ngày cần tra, dạng `yyyy-MM-dd`. Phải là ngày đã qua (nhỏ hơn hôm nay theo giờ Việt Nam) |
| `storeId` | Owner: có. BM: không | Kho cần tra |
| `q` | Không | Tìm theo SKU hoặc tên phụ tùng (không phân biệt hoa thường) |
| `categoryId` | Không | Lọc theo danh mục, gồm cả danh mục con |
| `includeZero` | Không, mặc định `false` | `true` thì hiện cả phụ tùng có tồn cuối ngày bằng 0 |
| `page`, `size` | Không | Mặc định 0 và 20, `size` tối đa 100. Luôn xếp theo SKU |

### Ai được gọi

| Vai trò | Kết quả |
|---|---|
| Owner | Phải truyền `storeId`. Thiếu thì 400 `MSG02`. Kho không tồn tại thì 404 `MSG52` |
| Branch Manager (kể cả BM kho tổng) | Chỉ xem kho của mình. Không truyền `storeId` thì lấy kho của mình. Truyền kho khác thì 403 `MSG05` |
| Staff, Admin | 403, bị `@PreAuthorize` ở controller chặn |

BM kho tổng cũng bị giữ ở kho của mình, giống UC-47 và UC-97. Chỉ màn tạo lệnh điều chuyển (UC-48) cho BM kho tổng xem kho khác.

Thứ tự kiểm tra: ngày trước, rồi `MSG02` của Owner, rồi quyền kho. Vì vậy `date` là hôm nay luôn ra `MSG92` dù vai trò nào.

### Response

| Field | Ý nghĩa |
|---|---|
| `date` | Ngày đã tra |
| `storeId`, `storeName` | Kho đã tra |
| `basedOnPeriod` | Kỳ kho dùng làm mốc (`yyyy-MM`), hoặc `null` nếu cộng từ đầu. Xem mục 3 |
| `page` | Trang kết quả (`items`, tổng số, ...) |
| `page.items[]` | `productId`, `sku`, `partName`, `baseUnit`, `closingQty` |

Response **không có giá hay giá trị**, nên Owner và BM nhận cùng một định dạng.

### Lỗi

| Mã | Khi nào |
|---|---|
| `MSG92` (400) | `date` là hôm nay hoặc tương lai: "Chỉ tra được tồn kho của ngày đã qua." |
| `MSG02` (400) | Owner thiếu `storeId`, thiếu `date`, hoặc `date` sai định dạng |
| `MSG52` (404) | Owner truyền kho không tồn tại |
| `MSG05` (403) | BM truyền kho khác kho của mình |

## 2. Công thức

```
tồn cuối ngày D = tổng quantity_change của stock_movement
                  tại kho đó
                  với stock_status <> 'IN_TRANSIT'
                  và occurred_at < 00:00 của ngày D+1 (giờ Việt Nam)
```

- Tính **hàng thực sự nằm trong kho**, gồm cả các trạng thái không bán được (hỏng, giữ lại, ...). Đây cùng định nghĩa với `StockBalance.onHandQty`, tức cột "Tồn thực tế" của UC-47.
- Hàng **đang vận chuyển** (`IN_TRANSIT`) nằm ở kho đích và bị loại. Kho nguồn đã trừ khi xuất, kho đích chỉ tính khi hàng nhận xong.
- Chuyển trạng thái trong cùng kho (ví dụ sang hỏng) tạo hai dòng cộng trừ nhau, nên không đổi tồn cuối ngày.
- Mốc `< 00:00 của D+1` nghĩa là giây cuối 23:59:59 của D được tính, đúng 00:00:00 của D+1 thì không.

### Ví dụ

Kho A, phụ tùng X:

| Ngày | Sự kiện | `quantity_change` |
|---|---|---|
| Ngày 1 | Nhận hàng 10 | +10 |
| Ngày 3 | Bán 3 | -3 |
| Ngày 5 | Chuyển 2 sang kho B | -2 (kho A) |

| Tra ngày | Kho A | Kho B |
|---|---|---|
| Ngày 1 | 10 | 0 |
| Ngày 2 | 10 | 0 |
| Ngày 3 | 7 | 0 |
| Ngày 5 | 5 | 0 |

Kho B vẫn 0 vì 2 cái chuyển đi nằm `IN_TRANSIT` tại B, bị loại. Khi kho B nhận xong thì dòng nhập mới được cộng, từ ngày nhận.

## 3. Vai trò của kỳ kho (inventory period)

Cộng từ đầu lịch sử sẽ chậm dần. Nên khi có kỳ đã đóng thì dùng làm mốc:

1. Lấy kỳ `CLOSED` mới nhất có `to_date < D`.
2. Mốc là `period_stock_balance.closing_qty` của kỳ đó (theo kho và phụ tùng).
3. Chỉ cộng thêm các movement từ `to_date + 1` đến hết ngày D.
4. `basedOnPeriod` cho biết kỳ nào đã dùng (ví dụ `2026-09`). Không có kỳ nào thì là `null` và cộng từ năm 1970.

Quy tắc:

- Kỳ `OPEN` **không bao giờ** được dùng làm mốc.
- Ngày D nằm **trong** một kỳ đã đóng thì `to_date` của kỳ đó lớn hơn hoặc bằng D, nên không thỏa `to_date < D`. Hệ thống dùng kỳ đóng trước đó, hoặc cộng từ đầu.
- Phụ tùng không có dòng ở kỳ mốc thì mốc bằng 0.

**Hiện tại chưa có kỳ nào bị đóng** (UC-100 thuộc iteration 3), nên thực tế mọi truy vấn đang cộng từ đầu. Code mốc kỳ đã có test riêng, xem mục 8.

## 4. Vì sao không có bảng tồn theo ngày

R3 BR-87 nói hệ thống lưu tồn cuối ngày của từng SKU mỗi ngày (`daily_stock_balance`, JOB-04). Ngày 29/10 nhóm quyết định bỏ, vì:

| | Bảng tồn theo ngày + job đêm | Một truy vấn có index |
|---|---|---|
| Số dòng | Số SKU × số kho × 365 mỗi năm | Không thêm dòng nào |
| Demo (30 SKU × 3 kho) | khoảng 33.000 dòng/năm | Không có |
| Chuỗi thực tế (5.000 SKU × 5 kho) | khoảng 9 triệu dòng/năm | Không có |
| Vận hành | Job đêm không được bỏ sót đêm nào. Bỏ sót một đêm thì để lại lỗ hổng hoặc số sai, các ngày sau cộng dồn sai theo | Không có job, số luôn khớp với sổ biến động |
| Sửa dữ liệu | Phải tính lại mọi ngày sau | Không cần |

Công thức dùng `group by` trên `stock_movement` theo `store_id`, `product_id`, `occurred_at` nên đi qua index `ix_stock_movement_3 (store_id, product_id, occurred_at)`. Khi có kỳ đóng thì chỉ quét phần movement sau kỳ đó (mục 3).

## 5. Điều kiện cho UC-100 (đóng kỳ kho, iteration 3)

Mốc kỳ chỉ đúng khi `period_stock_balance.closing_qty` được tính **đúng định nghĩa ở mục 2**:

- loại `IN_TRANSIT`;
- cộng đến hết `to_date` (đến 00:00 của `to_date + 1`, giờ Việt Nam).

Nếu UC-100 tính khác (ví dụ lấy `stock_balance` lúc bấm đóng kỳ, hoặc tính cả hàng đang vận chuyển) thì số UC-103 sẽ lệch ngay từ kỳ đó trở đi và không có lỗi nào báo ra.

Lưu ý thêm: `period_stock_balance` **không tách theo trạng thái** hàng, chỉ có một `closing_qty`. Đủ cho UC-103 vì công thức cũng cộng mọi trạng thái trừ `IN_TRANSIT`.

## 6. Chi phí truy vấn

Mỗi lần gọi chạy:

| # | Truy vấn | Ghi chú |
|---|---|---|
| 1 | Tìm kỳ đóng mới nhất trước D | Tra bảng `inventory_period` nhỏ |
| 2 | Truy vấn trang chính | `product` left join mốc kỳ và left join tổng movement đã `group by` |
| 3 | Truy vấn đếm tổng | Dùng chung đoạn `FROM` với truy vấn 2 (hằng `PAST_DATE_FROM`) nên hai bên không lệch nhau |
| 4 | Tên kho | `findById` |
| 5 | Danh mục con | Chỉ khi có `categoryId` |

`includeZero=false` được lọc **trong SQL** (điều kiện `closing_qty + movement <> 0`), nên tổng số dòng và số trang đúng, không cắt sau khi lấy trang.

Phụ tùng được liệt kê nếu thỏa một trong ba: có dòng `stock_balance` ở kho, từng có movement ở kho trước hết ngày D, hoặc có dòng ở kỳ mốc.

## 7. Câu hỏi cho BA

1. **BR-87 cần sửa.** R3 BR-87 ghi hệ thống lưu tồn cuối ngày của từng SKU vào cuối mỗi ngày. Code làm theo quyết định ngày 29/10 (không có bảng tồn theo ngày, không có JOB-04), tính từ sổ biến động kho và mốc kỳ đóng. Đề nghị BA sửa BR-87 cho khớp.
2. **`includeZero=true` hiện phụ tùng chưa từng có ở kho đó.** Một phụ tùng có dòng `stock_balance` ở kho hiện tại sẽ hiện với tồn 0 cho ngày D, kể cả khi vào ngày D nó chưa từng xuất hiện ở kho. Có chấp nhận được không? Nếu không, bỏ điều kiện `stock_balance` và chỉ liệt kê phụ tùng đã có movement hoặc có dòng kỳ mốc trước ngày D.

## 8. Test và Postman

### Test (`src/test/java/fu/hl/g64/motorflow/inventory/PastDateStockApiTest.java`)

Chạy trên PostgreSQL thật, giờ hệ thống cố định bằng `Clock`.

| Test | Kiểm tra |
|---|---|
| `closingQuantityFollowsSalesAndTransfersAndExcludesInTransit` | Nhận, bán, chuyển cho ra 10, 7, 5; kho đích 0 vì hàng đang vận chuyển |
| `movingToDamagedKeepsTheClosingQuantity` | Chuyển sang trạng thái hỏng không đổi tồn cuối ngày |
| `startsFromTheLatestClosedPeriodThatEndedBeforeTheDate` | Dùng kỳ đóng mới nhất trước D làm mốc, `basedOnPeriod` đúng |
| `anEarlierClosedPeriodIsUsedWhenTheLaterOneEndsAfterTheDate` | D nằm trong kỳ đã đóng thì dùng kỳ đóng trước đó |
| `anOpenPeriodIsNeverUsedAsABase` | Kỳ `OPEN` không bao giờ làm mốc |
| `todayAndFutureDatesAreRejected` | Hôm nay và tương lai bị `MSG92` |
| `permissionsAndRequiredParameters` | `MSG02`, `MSG52`, `MSG05`, Staff và Admin 403, BM kho tổng chỉ kho mình |
| `zeroQuantitiesAreHiddenUnlessAskedForAndPagesFollowTheSku` | `includeZero`, tổng số dòng đúng, phân trang xếp theo SKU |
| `filtersBySearchTextAndCategoryWithDescendants` | Lọc `q` và `categoryId` gồm danh mục con |
| `theLastSecondOfTheDayCountsAndMidnightDoesNot` | 23:59:59 của D được tính, 00:00:00 của D+1 thì không |

### Postman

Thư mục "Tồn kho ngày đã qua (UC-103)" trong `postman/MotoFlow-Iteration2.postman_collection.json`. Thư mục chỉ đọc, cần dữ liệu demo.

| Request | Kết quả mong đợi |
|---|---|
| BM tra tồn kho hôm qua | 200, đúng kho, có `basedOnPeriod`, `closingQty` là số lớn hơn 0, xếp theo SKU |
| Owner tra một kho | 200, đúng kho, không có trường giá |
| Owner không chọn kho bị MSG02 | 400 `MSG02` |
| Tra hôm nay bị MSG92 | 400 `MSG92` |
| BM tra kho khác bị MSG05 | 403 `MSG05` |

Request đầu tiên có script chạy trước (pre-request) tính `today` và `yesterday` theo giờ Việt Nam (UTC+7) rồi lưu vào biến collection, các request sau dùng lại.

Dữ liệu seed có movement mang ngày "bây giờ", nên tra hôm qua có thể ra danh sách rỗng. Các test viết sao cho vẫn đạt khi danh sách rỗng (chỉ kiểm tra hình dạng). Muốn có dữ liệu thật để xem, chỉ trên **DB thử nghiệm** (không bao giờ trên DB dùng chung) lùi movement 2 ngày sau khi seed:

```sql
UPDATE stock_movement SET occurred_at = occurred_at - interval '2 days';
```
