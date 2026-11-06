# Giải thích code lô hàng (UC-97, UC-98)

Tài liệu này giải thích hai API xem lô: danh sách lô (UC-97) và chi tiết lô (UC-98). Cả hai chỉ đọc dữ liệu, không ghi gì vào DB.

| API | Dùng cho | Ai được gọi |
|---|---|---|
| `GET /api/v1/inventory/lots` | UC-97: danh sách lô theo tab | Owner, Branch Manager |
| `GET /api/v1/inventory/lots/{lotId}?storeId=` | UC-98: chi tiết một lô | Owner, Branch Manager |

Staff và Admin bị `@PreAuthorize` ở controller chặn (403).

## 1. File mới và file đã sửa

Đường dẫn tính từ `src/main/java/fu/hl/g64/motorflow/inventory/`, trừ khi ghi khác.

### File mới

| File | Làm gì |
|---|---|
| `dto/LotTab.java` | Enum 4 tab: `NEAR_EXPIRY`, `EXPIRED`, `IN_STOCK`, `DEPLETED` |
| `dto/LotSearch.java` | Gom tham số lọc của UC-97 thành một record |
| `dto/LotRowResponse.java` | Một dòng danh sách: một lô tại một kho, không có giá |
| `dto/LotListResponse.java` | Response UC-97: số ngày cảnh báo N và một trang kết quả |
| `dto/LotDetailResponse.java` | Response UC-98, gồm các record con `SourceDocument`, `StatusQty`, `IssueOrderLine` |
| `repository/LotQueryRepository.java` | Các query đọc của màn hình lô |
| `src/test/.../inventory/LotApiTest.java` | Test API của UC-97 và UC-98 trên PostgreSQL thật |
| `postman/collections/MotoFlow - Iteration 2/` | Collection Postman dạng yaml, dùng dữ liệu seed |
| `postman/MotoFlow-Iteration2.postman_collection.json` | Cùng collection đó ở dạng json |

### File đã sửa

| File | Sửa gì |
|---|---|
| `controller/InventoryController.java` | Thêm 2 endpoint `/lots` và `/lots/{lotId}` |
| `service/InventoryService.java` | Thêm `listLots`, `lotDetail` |
| `service/impl/InventoryServiceImpl.java` | Thêm `listLots`, `lotDetail`, `lotStoreScope`, `daysToExpiry`, `sourceDocument`. Tách `movementRows` khỏi `movements` để UC-49 và UC-98 dùng chung |
| `repository/LotBalanceRepository.java` | Thêm `findByLotIdAndStoreIdOrderByStockStatusAsc` |
| `repository/StockMovementRepository.java` | Thêm `existsByLotIdAndStoreId`, `findTop10ByLotIdAndStoreIdOrderByOccurredAtDescIdDesc`, `sumIncoming` |
| `src/main/resources/messages.properties` | Thêm `MSG90=Không tìm thấy lô.` |

Code receiving không bị sửa. `lotDetail` chỉ đọc qua `GoodsReceiptNoteLineRepository` có sẵn để lấy id phiếu nhập.

## 2. Từng query làm gì

### `LotQueryRepository.searchLots` (UC-97)

Đây là query chính của danh sách. Query viết bằng HQL (JPQL của Hibernate), chạy qua các bước:

1. Nối `lot_balance` với `lot`, `product`, `store`, và nối trái với `supplier`. Lô tồn đầu kỳ không có nhà cung cấp nên phải nối trái.
2. Bỏ các dòng `IN_TRANSIT` ngay trong `where`. Nhờ vậy `sum(b.quantity)` chính là "còn lại tại kho", và kho đích của một lô đang chuyển không có dòng nào.
3. Áp bộ lọc `storeId`, `supplierId`, `source`, ngày nhận, `q`. Tham số nào null thì bỏ qua.
4. Lọc tab theo hạn dùng trong `where`.
5. Gộp theo lô và kho (`group by`), rồi dùng `having` để giữ lô còn hàng, hoặc lô còn lại 0 với tab `DEPLETED`.
6. Sắp xếp: tab `DEPLETED` theo ngày nhận mới nhất trước, các tab khác theo hạn sớm nhất trước, lô không hạn xếp cuối.

Tab được định nghĩa như sau:

| Tab | Điều kiện |
|---|---|
| `NEAR_EXPIRY` | Còn hàng và hạn trong [hôm nay, hôm nay + N] |
| `EXPIRED` | Còn hàng và (hạn trước hôm nay hoặc `lot.status = EXPIRED`) |
| `IN_STOCK` | Còn hàng, gồm cả lô sắp hết và đã hết hạn |
| `DEPLETED` | Có dòng ở kho đó nhưng còn lại bằng 0 |

Phần thân query nằm trong hằng `LOTS_BY_STORE`, vì query lấy dữ liệu và `countQuery` dùng chung đoạn này. Có `group by` và `having` nên Spring không tự sinh được câu đếm. `countQuery` đếm số nhóm bằng subquery trong `from`, đây là cú pháp riêng của HQL.

Ba điểm dễ thắc mắc khi đọc query:

- `coalesce(:receivedFrom, l.receivedDate)`: viết `:receivedFrom is null` thì PostgreSQL báo `could not determine data type of parameter`. Với `coalesce`, cột quyết định kiểu của tham số.
- `cast(l.id as String) = :q`: tìm mã lô chính xác mà không cần tự parse số trong Java.
- Chưa đặt N: service truyền `warnUntil` là hôm qua, khoảng ngày thành rỗng nên tab `NEAR_EXPIRY` không có dòng nào, không cần nhánh riêng.

### `LotQueryRepository.issueOrder` (UC-98)

Lấy tối đa 20 lô cùng phụ tùng từng có ở kho đang xem, theo thứ tự xuất của BR-75: hạn sớm nhất trước, không hạn xếp cuối, rồi ngày nhận, rồi id. Cột `available` chỉ cộng số lượng trạng thái `AVAILABLE`. Service dùng kết quả này để tính `current` và `skipped`.

### Các query trong `StockMovementRepository` (UC-98)

| Query | Làm gì |
|---|---|
| `findTop10ByLotIdAndStoreIdOrderByOccurredAtDescIdDesc` | 10 biến động mới nhất của lô tại kho đang xem |
| `sumIncoming` | Tổng `quantity_change > 0` theo danh sách loại biến động, dùng cho `receivedQty` |
| `existsByLotIdAndStoreId` | Kiểm tra lô từng có biến động ở kho này chưa, dùng cho quyền của BM |

`receivedQty` chỉ tính tại `lot.storeId`, với 4 loại `RECEIPT_CONFIRMED`, `EXCESS_ACCEPTED`, `OPENING_BALANCE`, `COUNT_ADJUSTMENT`. Vì vậy hàng chuyển kho không bị cộng thành hàng nhận.

### `LotBalanceRepository.findByLotIdAndStoreIdOrderByStockStatusAsc` (UC-98)

Lấy các dòng tồn của lô tại kho đang xem. Service dùng chúng để tính `remainingQty` (không tính `IN_TRANSIT`), `byStatus` (dòng có số lượng lớn hơn 0) và kiểm tra quyền của BM.

## 3. Vì sao lọc tab trong SQL

`remainingQty` là tổng của nhiều dòng `lot_balance`, và tab phụ thuộc vào tổng đó. Nếu DB trả về 20 dòng rồi Java mới lọc, sẽ có ba lỗi:

- Trang có thể còn ít hơn 20 dòng dù vẫn còn dữ liệu.
- `totalItems` sai, FE vẽ sai số trang.
- Lô đúng tab có thể rơi sang trang khác.

Muốn đúng trong Java thì phải tải toàn bộ lô của kho lên bộ nhớ rồi tự sắp xếp và cắt trang, càng nhiều lô càng chậm. Lọc trong SQL thì DB làm cả việc lọc, sắp xếp, phân trang và đếm.

Service vẫn giữ phần nghiệp vụ: kiểm tra quyền, tính `today` và `warnUntil`, chuẩn hoá `q`, tính `daysToExpiry`, dựng response. Ngày hôm nay lấy từ `Clock` đã inject, nên test điều khiển được thời gian.

Query cũng lấy luôn lô, phụ tùng, tên kho và tên nhà cung cấp trong một lần, nên không có lỗi N+1.

## 4. Vì sao BM kho tổng chỉ xem kho mình

UC-97 ghi "their warehouse", tức kho của chính họ. Hàm `lotStoreScope` thể hiện quy tắc này:

| Người dùng | `storeId` | Kết quả |
|---|---|---|
| Owner | Không truyền | Mọi kho |
| Owner | Có, kho tồn tại | Kho đó |
| Owner | Có, kho không tồn tại | 404 `MSG52` |
| BM | Không truyền | Kho của mình |
| BM | Bằng kho của mình | Kho của mình |
| BM | Kho khác | 403 `MSG05` |

Không dùng `storeScope.viewableStore` hay `scopeStoreId`, vì hai hàm này cho BM kho tổng xem mọi kho, là đúng với các màn hình khác nhưng sai với UC-97. Nhóm chưa quyết BM kho tổng được xem gì ở Iteration 2, nên hai hàm dùng chung đó được giữ nguyên, và lô có hàm riêng.

Ở UC-98 có thêm một lớp kiểm tra: BM mà kho mình chưa từng có lô đó (không có dòng `lot_balance`, không có `stock_movement`) sẽ nhận `MSG05`. Với Owner, không truyền `storeId` thì "kho đang xem" là `lot.storeId`, kho nhận lô lần đầu.

## 5. Vì sao danh sách không trả giá

BR-72 giới hạn thông tin giá vốn, và màn hình danh sách lô không có cột giá. `LotRowResponse` không có field giá nào, nên cả Owner cũng không nhận giá ở danh sách. Test `listCarriesNoPrice` kiểm tra JSON không chứa "price" hay "cost".

Ở chi tiết lô, `purchaseUnitPrice` chỉ có giá trị với Owner, BM nhận null. `unitCost` và `value` của biến động được ẩn tương tự trong `movementRows`, cùng một chỗ với UC-49.

## 6. Các điểm cần lưu ý

- **`issueOrder` tối đa 20 dòng:** nếu lô đang xem đứng sau vị trí 20 thì không có dòng `current = true`.
- **`byStatus` có thể có `IN_TRANSIT`:** tổng `byStatus` có thể lớn hơn `remainingQty`.
- **Lô đang chuyển tới kho đích:** không hiện ở tab `DEPLETED` của kho đích vì chưa tới nơi, nhưng BM kho đích vẫn mở được chi tiết vì đã có dòng `lot_balance`.
- **`NEAR_EXPIRY` không loại `status = EXPIRED`:** lô có trạng thái này mà hạn còn trong tương lai sẽ hiện ở cả hai tab. Hiện chưa có job nào đặt trạng thái này, cần xem lại khi làm JOB-03.
- **Trạng thái hạn tính lúc đọc:** các API này không cập nhật `lot.status`.

## 7. Cách chạy Postman

1. Migrate DB rồi chạy `scripts/seed/seed_demo_data.sql`.
2. Chạy app.
3. Import `postman/MotoFlow-Iteration2.postman_collection.json` và chạy cả collection bằng Runner, đúng thứ tự từ trên xuống.

Collection dùng tài khoản seed `owner`, `bm.kho`, `nv.kho1` với mật khẩu `Demo@2026`. Collection chỉ đọc dữ liệu nên chạy lại được nhiều lần.
