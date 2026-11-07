# Giải thích code yêu cầu bổ sung hàng (UC-68, UC-69, UC-70)

Tài liệu này giải thích các API của yêu cầu bổ sung hàng: một cửa hàng xin thêm hàng, Owner và BM kho tổng theo dõi yêu cầu đó. Căn cứ là R3 bản 05/11 (UC-68 đến UC-70, BR-54 đến BR-57, màn 7.1.2, Permission Matrix).

| API | Dùng cho |
|---|---|
| `POST /api/v1/replenishment-requests` | UC-68: tạo yêu cầu |
| `GET /api/v1/replenishment-requests/low-stock-suggestions?storeId=` | Màn 7.1.2: nút "Thêm tất cả mã dưới ngưỡng" |
| `GET /api/v1/replenishment-requests` | UC-69: danh sách |
| `GET /api/v1/replenishment-requests/{id}` | UC-70: chi tiết |

Cả 4 API chỉ cho Owner và Branch Manager. Staff và Admin bị `@PreAuthorize` ở controller chặn (403).

## 1. File mới và file đã sửa

Đường dẫn tính từ `src/main/java/fu/hl/g64/motorflow/transfer/`, trừ khi ghi khác.

### File mới

| File | Làm gì |
|---|---|
| `controller/ReplenishmentRequestController.java` | 4 endpoint ở trên |
| `service/ReplenishmentRequestService.java` | Interface của service |
| `service/impl/ReplenishmentRequestServiceImpl.java` | Toàn bộ quy tắc nghiệp vụ |
| `dto/ReplenishmentCreateRequest.java` | Body tạo yêu cầu |
| `dto/ReplenishmentDetailResponse.java` | Response chi tiết, dùng chung cho tạo và UC-70 |
| `dto/ReplenishmentRowResponse.java` | Một dòng danh sách |
| `dto/ReplenishmentSearch.java` | Gom bộ lọc của UC-69 |
| `dto/LowStockSuggestionResponse.java` | Một dòng gợi ý |
| `src/test/.../transfer/ReplenishmentRequestApiTest.java` | Test API trên PostgreSQL thật |

### File đã sửa

| File | Sửa gì |
|---|---|
| `entity/ReplenishmentRequest.java` | Thêm `create` |
| `entity/ReplenishmentRequestLine.java` | Thêm `create` |
| `repository/ReplenishmentRequestRepository.java` | Thêm `search`, `findPurchaseOrders` |
| `repository/ReplenishmentRequestLineRepository.java` | Thêm `findLowStockParts`, `findByReplenishmentRequestIdOrderById` |
| `repository/TransferOrderRepository.java` | Thêm `findLinkedOrders` |
| `repository/TransferOrderLineRepository.java` | Thêm `plannedByProduct`, `fulfilledByProduct` |
| `src/main/resources/messages.properties` | Thêm 3 mã MSG, xem mục 7 |
| `postman/...` | Thêm thư mục "Yêu cầu bổ sung (UC-68 đến UC-70)" |

Không sửa migration, schema, hay code của `receiving`. Các query đọc `purchase_order` và `goods_receipt_note` nằm trong repository của `transfer`, và chỉ đọc.

## 2. Luồng tạo yêu cầu và gửi thông báo

Toàn bộ `create` chạy trong một transaction, lỗi ở bước nào thì không lưu gì.

1. **Xác định cửa hàng yêu cầu** (`requestingStore`), xem bảng ở mục 3.
2. **Kiểm tra các dòng** (`checkRequestedParts`), theo thứ tự:
   - trùng `productId`: 400 `MSG93`;
   - phụ tùng không tồn tại: 404 `MSG57`;
   - phụ tùng không đang kinh doanh: 400 `MSG94`.

   Thiếu dòng, `requestedQty` nhỏ hơn 1, hoặc `note` dài quá 500 ký tự bị `@Valid` chặn từ đầu: 400 `MSG02`.
3. **Lưu yêu cầu và các dòng.** Trạng thái luôn là `AWAITING_COORDINATION`, `createdBy` là người gọi, `createdAt` lấy từ `Clock`, `toPurchaseQty = 0`. `note` được cắt khoảng trắng, chuỗi rỗng lưu thành null.
4. **Gửi thông báo** (`notifyCoordinators`):
   - người nhận: mọi Owner đang hoạt động và mọi BM đang hoạt động của kho tổng;
   - bỏ người tạo, vì họ đã biết;
   - type `REPLENISHMENT_REQUESTED`, refType `REPLENISHMENT_REQUEST`, refId là id yêu cầu;
   - nội dung: "Cửa hàng X yêu cầu bổ sung n phụ tùng (yêu cầu #id).".
5. **Trả response** cùng định dạng UC-70, dùng chung hàm `detail`.

Không dùng `notifyStoreManagers` và `notifyOwners` có sẵn, vì hai hàm này không loại được người tạo. Service tự gom người nhận rồi gọi `notify` cho từng người.

**Không ghi `audit_log`.** Repo hiện chỉ ghi audit cho tài khoản, cửa hàng và danh mục, chưa ghi cho chứng từ nào, nên việc tạo yêu cầu cũng không ghi.

## 3. Quyền của từng vai trò

### Tạo yêu cầu và xem gợi ý

Cả hai dùng chung hàm `requestingStore`.

| Người dùng | `storeId` | Kết quả |
|---|---|---|
| BM (thường hoặc kho tổng) | Không truyền, hoặc cửa hàng của mình | Cửa hàng của mình |
| BM (thường hoặc kho tổng) | Cửa hàng khác | 403 `MSG05` |
| Owner | Không truyền | 400 `MSG02` |
| Owner | Có | Cửa hàng đã chọn |

Sau đó kiểm tra trạng thái cửa hàng:

| Trạng thái | BM | Owner |
|---|---|---|
| `ACTIVE` | Được | Được |
| `PLANNED` | 400 `MSG46` | Được, để nhập hàng ban đầu (UC-68) |
| `INACTIVE` hoặc không tồn tại | 400 `MSG46` | 400 `MSG46` |

**Kho tổng được làm nơi nhận.** Đề bài ban đầu cấm, nhưng Permission Matrix ghi Create = Full cho BM kho tổng và R3 không có dòng nào cấm, nên đã chốt cho phép. Khi BM kho tổng tự tạo thì Owner nhận thông báo; khi Owner tạo cho kho tổng thì BM kho tổng nhận.

### Xem danh sách và chi tiết

| Người dùng | Danh sách | Chi tiết |
|---|---|---|
| Owner | Mọi yêu cầu, lọc được theo `storeId` | Mọi yêu cầu |
| BM kho tổng | Mọi yêu cầu, lọc được theo `storeId` | Mọi yêu cầu |
| BM thường | Cửa hàng của mình; truyền cửa hàng khác: 403 `MSG05` | Cửa hàng của mình; cửa hàng khác: 403 `MSG05` |
| Staff, Admin | 403 | 403 |

Không tìm thấy yêu cầu: 404 `MSG95`. Hai trường hợp đầu dùng `storeScope.seesEveryStore`, nên quy tắc "ai thấy mọi cửa hàng" chỉ nằm ở một chỗ.

**`purchaseOrders`:** ai mở được yêu cầu thì thấy PO của yêu cầu đó, gồm `supplierId`, `supplierName`. Đề bài ban đầu chỉ cho Owner, nhưng R3 UC-70 ghi "Branch Manager or Owner opens a replenishment request to see ... the PO created for any quantity that must be purchased", nên đã chốt theo R3. Response không có giá nên không phạm BR-72. Đổi lại BM thấy tên nhà cung cấp, trong khi Permission Matrix ghi Supplier là Owner-only.

## 4. Gợi ý mã dưới ngưỡng

`findLowStockParts` lấy các dòng `stock_balance` của cửa hàng thoả cả 4 điều kiện:

1. `low_stock_threshold` khác null;
2. `available_qty < low_stock_threshold`;
3. phụ tùng đang kinh doanh;
4. không nằm trong yêu cầu chưa đóng (`status <> CLOSED`) của **cùng cửa hàng**.

`suggestedQty = threshold − available`, tối thiểu 1. Vì đã lọc `available < threshold` nên số này luôn từ 1 trở lên; `Math.max` chỉ là chốt an toàn.

Kết quả không phân trang, vì nút "thêm tất cả" cần đủ danh sách. Cửa hàng PLANNED tạo từ cửa hàng mẫu có sẵn ngưỡng và tồn 0 nên cũng có gợi ý.

## 5. Cách tính planned, fulfilled, remaining

Ví dụ yêu cầu 10 má phanh, đã chuyển 3 cho Owner mua (`toPurchaseQty = 3`), có 2 lệnh điều chuyển kế hoạch 4 và 2, đã nhận đạt 3:

| Field | Cách tính | Ví dụ |
|---|---|---|
| `plannedQty` | Tổng `transfer_order_line.planned_qty` của các lệnh có `replenishment_request_id` là yêu cầu này, cùng phụ tùng | 4 + 2 = 6 |
| `fulfilledQty` | Tổng `goods_receipt_note_line.passed_qty` của GRN `TRANSFER_IN` đã `CONFIRMED`, thuộc các lệnh đó, cùng phụ tùng | 3 |
| `remainingQty` | `requestedQty − fulfilledQty − toPurchaseQty`, không nhỏ hơn 0 | 10 − 3 − 3 = 4 |

Lưu ý khi đọc:

- **Không lọc lệnh hủy:** schema `transfer_order` không có trạng thái hủy, nên tính mọi lệnh liên kết.
- **Phải lọc `TRANSFER_IN`:** GRN `RETURN_TO_SOURCE` cũng có `transfer_order_id`, nhưng đó là hàng quay về kho nguồn, không phải hàng tới cửa hàng yêu cầu.
- **`remainingQty` tính theo số đã nhận,** không theo số kế hoạch. Hàng đã lên lệnh nhưng chưa tới vẫn tính là còn thiếu.
- **Số thô:** `fulfilledQty` cộng thẳng `passed_qty`, nên có thể lớn hơn `requestedQty` nếu kho nhận thừa. Khi đó `remainingQty` về 0.
- **PO chưa được tính vào `fulfilledQty`:** phần mua ngoài đã nằm trong `toPurchaseQty`.

Service lấy `plannedQty` và `fulfilledQty` bằng 2 query `group by` theo phụ tùng, rồi ghép vào từng dòng, nên không có lỗi N+1.

### Danh sách (UC-69)

- **Sắp xếp:** `AWAITING_COORDINATION` trước, rồi mới nhất trước. `IN_PROGRESS` và `CLOSED` xếp chung trong nhóm sau.
- **`lineCount`, `totalRequestedQty`:** tính bằng subquery, không dùng `group by`, nên `countQuery` chỉ cần đếm yêu cầu.
- **`from`, `to`:** so theo ngày tạo, lấy cả hai đầu. Dùng `cast(r.createdAt as LocalDate)` nên giờ trong ngày không ảnh hưởng. Viết `coalesce` vì `:from is null` lỗi kiểu dữ liệu trên PostgreSQL.

## 6. Phần để dành cho nhiệm vụ Transfer

Chưa làm trong nhiệm vụ này:

- **UC-71:** điều phối và lập lệnh điều chuyển (màn 7.1.3). Vì vậy chưa có API nào tạo `transfer_order` gắn với yêu cầu; test và dữ liệu seed chèn trực tiếp vào DB.
- **Chuyển phần thiếu sang Owner mua:** chưa có API ghi `to_purchase_qty`, nên yêu cầu tạo mới luôn có số này bằng 0.
- **Đổi trạng thái:** chưa có API chuyển sang `IN_PROGRESS` hoặc `CLOSED`, và chưa ghi `closed_at`.
- **Đồng ý phương án cung cấp một phần:** màn 14 và 7.1.3 có nhắc, thuộc luồng điều phối.
- **"supply route (derived)":** entity R3 có, nhưng đề bài UC-70 không yêu cầu field này.

`plannedQty`, `fulfilledQty`, `toPurchaseQty` đã đọc sẵn từ DB, nên khi Transfer ghi dữ liệu thì UC-70 tự hiện số đúng, không phải sửa.

## 7. Mã MSG mới

| Mã | Nội dung | Khi nào |
|---|---|---|
| `MSG93` | Mỗi phụ tùng chỉ được xuất hiện một dòng trong yêu cầu bổ sung. | 400, dòng trùng `productId` |
| `MSG94` | Phụ tùng {0} đã ngừng kinh doanh. | 400, phụ tùng không đang kinh doanh |
| `MSG95` | Không tìm thấy yêu cầu bổ sung. | 404, UC-70 |

`MSG91`, `MSG92` không có trên nhánh này vì thuộc nhánh khác, nên số được giữ nguyên để không trùng khi merge.

Dùng lại mã có sẵn: `MSG02` (thiếu hoặc sai dữ liệu), `MSG05` (ngoài phạm vi), `MSG46` (cửa hàng sai trạng thái hoặc không tồn tại), `MSG57` (phụ tùng không tồn tại).

## 8. Cách chạy Postman

1. Migrate DB rồi chạy `scripts/seed/seed_demo_data.sql`.
2. Chạy app.
3. Import `postman/MotoFlow-Iteration2.postman_collection.json` và chạy cả collection bằng Runner, đúng thứ tự từ trên xuống.

Thư mục này dùng thêm tài khoản `bm.cg` (BM cửa hàng thường). `bm.kho` đóng vai BM kho tổng.

Thư mục này **ghi dữ liệu**: mỗi lần chạy tạo 1 cửa hàng PLANNED và 2 yêu cầu. Seed không có cửa hàng PLANNED nên phải tạo. Chạy lại nhiều lần được, nhưng nên chạy trên DB thử.
