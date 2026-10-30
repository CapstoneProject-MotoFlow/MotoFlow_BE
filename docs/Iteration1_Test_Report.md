# MotoFlow – Báo cáo test Iteration 1 và danh sách bug

- Ngày test: 30/10/2026
- Code được test: `main` mới nhất của hai repo
  - BE `MotoFlow_BE` commit `3f37da0` (PR #14, V10–V11, 44 bảng)
  - FE `MotoFlow_FE` commit `f6a7246` (PR #8)
- Tài liệu đối chiếu: Report 1 (phạm vi FE-01..FE-13), Report 3 (SRS: UC, Permission Matrix, BR, MSG), Report 4 (SDS: kiến trúc, 44 bảng), bảng hạng mục Iteration 1 trong Project Tracking.

## 1. Cách test

| Bước | Nội dung | Kết quả |
|---|---|---|
| Unit/Integration test BE | `./mvnw test` (PostgreSQL nhúng) | **103/103 test pass** |
| Build FE | `npm ci`, `npm run lint`, `npm run build`, `node --test tests/*.test.mjs` | Lint sạch, build OK, **7/7 test pass** |
| Test API thật | Chạy BE (`spring-boot:test-run`) + nạp `scripts/seed/seed_demo_data.sql`, gọi API bằng 5 vai trò (Admin, Owner, BM kho tổng, BM có quyền danh mục, Staff) theo từng UC-01..UC-33, UC-38 | 103 kiểm tra: **97 đạt, 6 không đạt**. Đã loại 3 kiểm tra do script test viết sai và kiểm tra lại bằng tay. |
| Test input lỗi | 37 request sai dữ liệu / sai kiểu / sai quyền | **Không có lỗi 500**, đều trả mã MSG đúng |
| Test giao diện | Chạy FE (Vite) + BE, dùng Playwright (Chromium 1366×768) đăng nhập từng vai trò và thao tác từng màn hình | 35 kiểm tra: **12 đạt, 23 không đạt** |

Dữ liệu đã kiểm tra lại: DB có đúng 44 bảng như Report 4 mục I.3 và 44 entity JPA. (Dòng "Database schema V1–V9 và JPA 50 bảng" trong Project Tracking đã cũ, hiện tại là V1–V11, 44 bảng.)

## 2. Độ phủ Iteration 1

Ký hiệu: ✅ Đạt · ⚠️ Đạt một phần · ❌ Chưa đạt

| # | Hạng mục (Project Tracking) | UC | BE | FE | Kết luận |
|---|---|---|---|---|---|
| 1 | Home Page | UC-01 bước 7 | ✅ `/api/home` đủ việc chờ theo vai trò + số thông báo chưa đọc | ⚠️ Owner toàn "—", không hiện số thông báo chưa đọc ở đâu | ⚠️ (FE-12) |
| 2 | Login | UC-01 | ✅ MSG08–MSG13, khóa 10 phút sau 5 lần sai | ✅ | ✅ |
| 3 | Change Password / Profile | UC-02 | ✅ MSG42–MSG44, đăng xuất thiết bị khác | ⚠️ Profile hiện "Cửa hàng #2" thay vì tên | ⚠️ (BE-03, FE-11) |
| 4 | Auto Unlock Account (JOB-01) | UC-01 | ✅ Hết `locked_until` là đăng nhập được (kiểm tra khi đăng nhập, không có job định kỳ) | – | ✅ (ghi chú DOC-03) |
| 5 | Database schema + JPA | – | ✅ 44 bảng khớp R4 | – | ✅ |
| 6 | Bảo mật đăng nhập và phiên | – | ✅ Refresh xoay vòng, phát hiện dùng lại, thu hồi khi logout/khóa/reset | ❌ Không tự refresh khi access token hết hạn | ⚠️ (FE-05) |
| 7 | Test tích hợp + Postman | – | ✅ 103 test pass | – | ✅ |
| 8 | Store List (tạo/sửa/sao chép) | UC-03..06 | ✅ | ❌ Sai trường `central`, sửa kho tổng bị lỗi, sao chép không gọi API copy | ❌ (FE-03, FE-04) |
| 9 | Account List | UC-07..11 | ✅ | ✅ | ✅ (lệch tài liệu DOC-01) |
| 10 | Permission History | UC-12 | ✅ | ✅ (chậm khi nhiều tài khoản) | ✅ (FE-16) |
| 11 | Spare Part Search | UC-13 | ✅ | ⚠️ BM/Staff OK; Owner không thấy tồn | ⚠️ (BE-05, FE-13) |
| 12 | Spare Part Detail | UC-14, UC-17 | ✅ | ❌ Chưa có màn hình | ❌ (FE-08) |
| 13 | Spare Part Form | UC-15, UC-16 | ✅ | ⚠️ Chỉ có tạo mới (Owner); chưa có sửa; BM có quyền danh mục không vào được; chưa lưu được ảnh | ⚠️ (FE-08, FE-09, FE-14) |
| 14 | Part Category List | UC-18..21 | ✅ | ❌ Chưa có màn hình (menu "chưa có dữ liệu") | ❌ (FE-06) |
| 15 | Vehicle Model List | UC-22..25 | ✅ | ❌ Chưa có màn hình | ❌ (FE-07) |
| 16 | Supplier List | UC-30, 31, 33 | ✅ | ❌ Chưa nối API | ❌ (FE-02) |
| 17 | Supplier Detail | UC-32 | ✅ | ❌ Chưa có màn hình | ❌ (FE-02) |
| 18 | Customer List | UC-26, 27, 29 | ✅ | ❌ Chưa nối API; BM không vào được | ❌ (FE-01, FE-10) |
| 19 | Customer Detail | UC-28 | ⚠️ Thiếu "nhu cầu chưa đáp ứng" | ❌ Chưa nối API | ❌ (BE-02, FE-01) |
| 20 | Branch Manager Permissions | UC-38 | ❌ Không có API | ❌ Chưa có màn hình | ❌ (BE-01, FE-15) |

**Tóm tắt:** Iteration 1 **chưa xong**.
- **BE gần đủ:** 18/20 hạng mục đạt; UC-28 thiếu phần nhu cầu chưa đáp ứng; UC-38 chưa có API.
- **FE còn thiếu nhiều:**
  - Chạy đầy đủ: Login, Account List, Permission History.
  - Chạy được một phần: đổi mật khẩu/Profile, Home, tìm phụ tùng, tạo phụ tùng.
  - Store List có bug nặng.
  - 8 màn còn lại chưa nối API hoặc chưa có: Khách hàng (2), Nhà cung cấp (2), Nhóm hàng, Dòng xe, Chi tiết phụ tùng, Quyền BM.

## 3. Danh sách bug

Mức độ:
- **Cao:** chặn UC hoặc làm sai dữ liệu.
- **Trung bình:** UC chạy được nhưng sai hoặc thiếu so với SRS.
- **Thấp:** lỗi hiển thị, hiệu năng hoặc trải nghiệm.

### 3.1 Backend (MotoFlow_BE)

#### BE-01 · Cao · UC-38 – Không có API để Owner cấp/thu hồi quyền danh mục và quyền giá bán lẻ cho BM
- **Hiện tượng:** Không endpoint nào cho Owner đọc danh sách BM theo cửa hàng hay đổi `can_edit_catalog` / `can_edit_retail_price`. Thử `GET /api/branch-managers`, `PUT /api/branch-managers/{id}/permissions`… đều trả 404. `PUT /api/accounts/{id}/permissions` chỉ cho Admin. Quyền giá bán lẻ hiện chỉ đặt được bằng SQL (seed data).
- **Yêu cầu:** R3 UC-38, BR-05, BR-08, Permission Matrix ("Grant / Revoke catalog and retail price permissions": Admin = No, Owner = Full).
- **Cách sửa:**
  1. Thêm controller, ví dụ `pricing/controller/ManagerPermissionController` hoặc `account/controller/ManagerPermissionController`, đặt `@PreAuthorize("hasRole('OWNER')")`:
     - `GET /api/branch-managers?storeId=` trả các BM đang hoạt động kèm `permissions`.
     - `PUT /api/branch-managers/{id}/permissions` nhận body `{ catalogEdit, retailPriceEdit, reason }`.
  2. Trong service:
     - Kiểm tra tài khoản đích là `BRANCH_MANAGER` và đang `ACTIVE`; nếu không, trả MSG17.
     - Gọi lại `PermissionService.setPermission(...)`, để audit_log ghi `PERMISSION_GRANTED` / `PERMISSION_REVOKED` với `actor_id` là Owner. Như vậy UC-12 tự hiện "do Owner cấp".
  3. Viết test cho 4 trường hợp: Owner cấp, Owner thu hồi, Admin gọi bị 403, cấp cho Staff bị MSG17.

#### BE-02 · Trung bình · UC-28 – Chi tiết khách hàng thiếu "nhu cầu chưa đáp ứng"
- **Hiện tượng:** `GET /api/customers/{id}` chỉ trả các trường `customer, creditHistory, debts, orders, orderCount, payments, paymentCount`.
- **Yêu cầu:** R3 UC-28 ("…outstanding debt and unmet demand"); BR-34/BR-35.
- **Cách sửa:**
  - Thêm `List<UnmetDemandRow> unmetDemand` vào `CustomerDetailResponse`.
  - Lấy dữ liệu từ `sales_order_line` của các đơn `COMPLETED` của khách: `requested_qty - delivered_qty > 0`, kèm SKU, tên, đơn gốc và cửa hàng.
  - Lọc theo cửa hàng giống phần `orders` (BM chỉ thấy cửa hàng mình), trong `CustomerService.detail` (`partner/service/CustomerService.java:94`).

#### BE-03 · Trung bình · UC-01/UC-02 – `/api/auth/me` (và login/refresh) không trả `storeName`
- **Hiện tượng:** `CurrentUserResponse` chỉ có `id, username, fullName, role, storeId, permissions, mustChangePassword`. FE đọc `user.storeName`, nên sidebar của BM/Staff luôn ghi "Chưa được phân công cửa hàng" và trang Thông tin cá nhân ghi "Cửa hàng #2".
- **Cách sửa:**
  - Thêm `storeName` (nên thêm cả `phone`, `email` cho màn Profile) vào `auth/dto/CurrentUserResponse.java:13`.
  - Lấy tên cửa hàng qua `StoreRepository` trong `AuthService.issue(...)` và trong `AuthController.me()`.
  - Cách khác: FE tự lấy `storeName` từ `/api/home` (xem FE-11).

#### BE-04 · Thấp · UC-18/UC-22 – Staff và BM chưa có quyền danh mục vẫn xem được danh sách nhóm hàng và dòng xe
- **Hiện tượng:** `GET /api/part-categories` và `GET /api/vehicle-models` trả 200 cho Staff và cho BM không có quyền danh mục. Permission Matrix ghi "View list": Staff = No, BM = Restricted¹.
- **Nguyên nhân:** `catalog/controller/CatalogAccess.java:6` dùng `READ` cho cả hai controller.
- **Cách sửa (cần nhóm chốt):**
  - Ô tìm phụ tùng (UC-13) cần danh sách này để làm bộ lọc, nên cách hợp lý là sửa R3: cho mọi vai trò bán hàng được đọc để lọc.
  - Nếu muốn giữ đúng R3: thêm endpoint rút gọn `GET /api/catalog/lookups` (chỉ id + tên, chỉ bản ghi ACTIVE) cho bộ lọc. Hai endpoint danh sách quản lý thì đổi sang `CatalogAccess.EDIT`.

#### BE-05 · Thấp · UC-13 – Owner tìm phụ tùng không thấy tồn kho
- **Hiện tượng:** Owner không có `storeId`, nên `availableQty` trả về `null` (`catalog/service/ProductService.java:106`). R3 UC-13 ghi "Owner sees every store".
- **Cách sửa:**
  - Khi người xem là Owner, hoặc BM kho tổng, và không truyền `storeId`, trả tổng tồn khả dụng toàn chuỗi.
  - Nên thêm cả `stockByStore` (tên cửa hàng + số lượng) cho màn chi tiết.
  - Hoặc giữ API như cũ, FE thêm ô chọn cửa hàng cho Owner (FE-13).

#### BE-06 · Thấp – Ký tự `%` và `_` trong ô tìm kiếm không được escape
- **Hiện tượng:** `GET /api/suppliers?q=%` trả toàn bộ nhà cung cấp; `_` khớp với mọi ký tự. Lỗi có ở `SupplierService.java:174`, `CustomerService.java:204` và `ProductService.java:205`.
- **Cách sửa:** Viết hàm `escapeLike(String)` thay `\` → `\\`, `%` → `\%`, `_` → `\_`, rồi gọi `cb.like(expr, pattern, '\\')`.

#### BE-07 · Thấp – Thiếu `fieldErrors` khi bỏ trống mã NCC / SKU lúc tạo mới
- **Hiện tượng:** `POST /api/suppliers` không gửi `supplierCode` thì trả MSG02 nhưng không có `fieldErrors`, nên FE không biết tô đỏ ô nào (NFR-U03). Cùng lỗi với SKU ở `ProductService.java:138`.
- **Cách sửa:** Ném `BusinessException` kèm `fieldErrors = {supplierCode: "Vui lòng nhập mã nhà cung cấp."}`. Có thể thêm constructor nhận map, hoặc tách DTO tạo/sửa để dùng `@NotBlank` trên DTO tạo.

#### BE-08 · Thấp – `brand` đang bắt buộc
- **Hiện tượng:** `ProductRequest` để `@NotBlank` cho thương hiệu (`catalog/dto/ProductRequest.java:26`). Nhưng R4 3.6 cho `brand` NULL, và R3 UC-15 không bắt buộc trường này.
- **Cách sửa:** Bỏ `@NotBlank`, giữ `@Size(max=100)`; `ProductService` dùng `blankToNull(request.brand())`. Hoặc sửa tài liệu nếu nhóm muốn bắt buộc.

#### BE-09 · Thấp – Chưa có API danh sách thông báo / đánh dấu đã đọc
- **Hiện tượng:** Home chỉ trả số chưa đọc và 5 thông báo mới nhất. Không có API mở hay đánh dấu đã đọc, nên số chưa đọc không bao giờ giảm.
- **Cách sửa:** Thêm `GET /api/notifications?unreadOnly=&page=`, `POST /api/notifications/{id}/read` (chỉ người nhận) và `POST /api/notifications/read-all`.

#### BE-10 · Thấp – Chưa có API tự cập nhật họ tên, điện thoại, email (SCR-04)
- **Hiện tượng:** R3 Screen #4 cho người dùng tự sửa họ tên, số điện thoại và email, nhưng chưa có endpoint. Bảng Iteration 1 chỉ ghi "xem thông tin", nên có thể dời sang iteration sau.
- **Cách sửa:** Thêm `PUT /api/auth/me` với body `{fullName, phone, email}`, dùng cùng validate như `CreateAccountRequest`.

### 3.2 Frontend (MotoFlow_FE)

#### FE-01 · Cao · UC-26..29 – Màn Khách hàng chưa nối API
- **Hiện tượng:** `CustomersPage` dùng mảng rỗng cứng (`const customers: Customer[] = []`). `CustomerDetailPage` và `CustomerForm` chỉ hiện "Chức năng khách hàng chưa được kết nối API". BE đã có đủ `/api/customers`.
- **Sai lệch thêm:**
  - Form bắt buộc số điện thoại, trong khi BE chỉ bắt buộc tên (để tạo nhanh lúc bán).
  - Form có ô "Người liên hệ" và "Ghi chú" nhưng DB không có hai trường này.
  - Còn cột "Hạng", nút "Đổi hạng", mục "Lịch sử hạng", dù V9 đã bỏ hạng khách hàng.
- **Cách sửa:**
  1. Tạo `modules/customers/services/customerService.ts` với các hàm:
     - `list({q, creditStatus, page, size})` gọi `GET /customers`, trả `PageResponse`.
     - `detail(id)` gọi `GET /customers/{id}`.
     - `create(body)` gọi `POST /customers`.
     - `update(id, body)` gọi `PUT /customers/{id}`.
  2. Màn danh sách: nối ô tìm kiếm với `q` (debounce), checkbox "Chỉ khách được mua chịu" với `creditStatus=ELIGIBLE`, phân trang theo `totalItems`. Hiện `totalDebt` (nợ toàn chuỗi) và `debtAtOwnStore`.
  3. Form chỉ gửi `customerName, phone, address, taxCode`; số điện thoại không bắt buộc. Hiện `fieldErrors` dưới từng ô, và MSG04 khi lưu thành công.
  4. Màn chi tiết hiển thị `creditHistory`, `debts`, `orders` và `payments`; phần "Nhu cầu chưa đáp ứng" chờ BE-02.
  5. Xóa toàn bộ UI về hạng khách hàng.

#### FE-02 · Cao · UC-30..33 – Màn Nhà cung cấp chưa nối API, chưa có Supplier Detail
- **Hiện tượng:**
  - `SuppliersPage` dùng mảng rỗng cứng; form chỉ hiện "chưa được kết nối API".
  - Form **thiếu ô Mã nhà cung cấp** (bắt buộc khi tạo, không đổi được sau đó) và lại bắt buộc cả MST, người liên hệ, email, địa chỉ (BE để tùy chọn).
  - Chưa có route chi tiết.
- **Cách sửa:**
  1. Tạo `supplierService.ts` gọi `GET/POST /suppliers`, `GET/PUT /suppliers/{id}`, `POST /suppliers/{id}/activate|deactivate`.
  2. Form: thêm `supplierCode` (chỉ nhập được khi tạo, khóa khi sửa); chỉ bắt buộc mã và tên. Ô trạng thái gọi activate/deactivate thay vì gửi trong body PUT.
  3. Thêm route `/owner/suppliers/:id` (Supplier Detail), hiển thị `purchaseOrders`, `deliveries`, `discrepancies` và các số đếm. Nút "Sửa" trên màn chi tiết mở lại cùng popup form.

#### FE-03 · Cao · UC-03/UC-06 – Store List đọc sai trường kho tổng, sửa kho tổng luôn lỗi
- **Hiện tượng:**
  - API trả `central: boolean` ở cấp ngoài, còn FE đọc `store.warehouse?.central` (`src/shared/types/store.ts`, `StoresPage.tsx:136-137`).
  - Kết quả: mọi dòng hiện "Chưa có kho", không dòng nào được đánh dấu "Kho tổng".
  - Mở "Sửa" cửa hàng kho tổng thì ô "Đây là kho tổng" bị bỏ chọn. Bấm Lưu (không đổi gì) nhận lỗi *"Không thể bỏ kho tổng: chuỗi luôn phải có đúng một kho tổng."* (MSG51), nên **không sửa được thông tin cửa hàng kho tổng**.
  - Ô "Tên kho *" bắt buộc nhập nhưng BE không lưu (mỗi cửa hàng là một kho, không có tên kho riêng).
- **Cách sửa:**
  1. Sửa kiểu `DashboardStore`: bỏ `warehouse`, thêm `central: boolean`, `productsInStock: number`, `copiedFromStoreId: number | null`.
  2. Bảng hiển thị `store.central ? 'Kho tổng' : 'Kho cửa hàng'`; `openEditForm` đặt `central: store.central`.
  3. Bỏ ô "Tên kho" và trường `warehouseName` trong payload.
  4. Khi sửa cửa hàng đang là kho tổng, khóa checkbox (không cho bỏ chọn); nếu chuỗi đã có kho tổng thì không cho tick ở cửa hàng khác.

#### FE-04 · Cao · UC-05 – "Tạo theo cửa hàng có sẵn" không sao chép gì
- **Hiện tượng:** Popup chọn cửa hàng mẫu nhưng gọi `POST /api/stores` (tạo cửa hàng trống) thay vì `POST /api/stores/{templateId}/copy` (`StoresPage.tsx:107`). Cấu hình vị trí lưu kho và ngưỡng tồn không được sao chép; `copiedFromStoreId` = null. Popup còn ghi "chưa được hệ thống hỗ trợ sao chép", trong khi BE đã hỗ trợ.
- **Cách sửa:**
  - Thêm hàm `copyStore(templateId, {storeName, address, phone})` gọi `POST /stores/${templateId}/copy`.
  - Thông báo thành công lấy `copiedSettings` từ response (ví dụ "Đã sao chép 29 cấu hình tồn kho").
  - Sửa lại phần mô tả: chỉ sao chép vị trí lưu kho và ngưỡng tồn; không sao chép tồn kho, chứng từ, công nợ.

#### FE-05 · Cao · NFR-S01 – Không tự làm mới access token; hết 15 phút là mọi màn hình báo lỗi
- **Hiện tượng:** `shared/api/axiosClient.ts` chỉ có interceptor cho request. Khi access token hết hạn (15 phút), mọi API trả 401 MSG06. Màn hình hiện "Không tải được…", nhưng người dùng vẫn ở trạng thái đăng nhập, không có MSG06 và không bị chuyển về Login. Phải F5 mới dùng tiếp được. Đã tái hiện bằng BE chạy `access-token-ttl=3m`: sau 190 giây, ô tìm phụ tùng báo "Chưa tải được số lượng".
- **Cách sửa:** Thêm response interceptor trong `axiosClient.ts`:
  - Nếu `status === 401`, request không phải `/auth/login|refresh|logout` và chưa retry: gọi `restoreSession()` (dùng chung một promise), gán lại token rồi gửi lại request gốc.
  - Nếu refresh cũng thất bại: `clearAccessToken()`, báo AuthProvider đặt `user = null`, hiện toast MSG06 rồi chuyển `/login`.
  - Có thể làm thêm: lỗi 403 MSG05 hiện toast chung.

#### FE-06 · Cao · UC-18..21 – Chưa có màn Part Category List
- **Hiện tượng:** Menu Owner "Nhóm phụ tùng" có `pending: true` (`ownerNavigation.ts:27`), bấm vào chỉ hiện "chưa có dữ liệu hiển thị".
- **Cách sửa:**
  - Tạo `modules/catalog/pages/PartCategoryPage.tsx`, route `/catalog/categories`.
  - Hiển thị cây từ `GET /part-categories?vehicleType=`; popup tạo/sửa chọn loại xe và nhóm cha (chỉ nhóm ACTIVE cùng loại xe).
  - Nút Ngừng dùng/Dùng lại gọi `POST /part-categories/{id}/deactivate|activate`.
  - Hiện MSG58/MSG59/MSG60 từ API.
  - Quyền vào: Owner, hoặc BM có `CATALOG_EDIT`.

#### FE-07 · Cao · UC-22..25 – Chưa có màn Vehicle Model List
- **Hiện tượng:** Menu "Dòng xe" có `pending: true` (`ownerNavigation.ts:28`).
- **Cách sửa:**
  - Tạo `VehicleModelPage.tsx`, route `/catalog/vehicle-models`.
  - Bảng lọc theo hãng / loại xe; popup tạo/sửa gồm `manufacturer, modelName, vehicleType, yearFrom, yearTo` (hiện MSG62, MSG64).
  - Nút Ngừng dùng/Dùng lại gọi `POST /vehicle-models/{id}/deactivate|activate`.
  - Quyền vào giống FE-06.

#### FE-08 · Cao · UC-14, UC-16, UC-17 – Chưa có Spare Part Detail, chưa sửa / ngừng kinh doanh được phụ tùng
- **Hiện tượng:** Các dòng trong bảng phụ tùng không có link và không có nút thao tác (0 link, 0 nút). Không có route chi tiết hay sửa.
- **Cách sửa:**
  1. Thêm route `/products/:id` (Spare Part Detail) gọi `GET /products/{id}?storeId=`. Hiển thị thông số, mã OEM, mã vạch, dòng xe tương thích, giá bán lẻ và tồn theo trạng thái + vị trí kho của cửa hàng.
  2. Thêm route `/products/:id/edit` dùng lại form tạo mới (khóa SKU), gọi `PUT /products/{id}`.
  3. Nút "Ngừng kinh doanh" mở popup xác nhận rồi gọi `POST /products/{id}/discontinue` (có thêm "Kinh doanh lại": `/reactivate`). Chỉ hiện nút cho Owner hoặc BM có `CATALOG_EDIT`.
  4. Link tên phụ tùng ở cả 3 màn tìm kiếm (Owner, BM, Staff) sang trang chi tiết.

#### FE-09 · Trung bình · UC-15/16 – BM có quyền danh mục không vào được form phụ tùng
- **Hiện tượng:** Route `/owner/products/new` nằm trong `RequireRole roles={['OWNER']}`. Đăng nhập `bm.cg` (có `CATALOG_EDIT`) rồi mở URL này thì bị đẩy về `/dashboard`. Menu BM cũng không có Nhóm hàng / Dòng xe.
- **Cách sửa:**
  - Viết guard `RequireCatalogEdit`: cho qua khi `role === 'OWNER' || permissions.includes('CATALOG_EDIT')`.
  - Đổi route sang `/products/new` và `/products/:id/edit`.
  - Hiện nút "Thêm phụ tùng" và các menu danh mục cho BM khi có quyền.

#### FE-10 · Trung bình · UC-26..29 – BM không vào được màn Khách hàng
- **Hiện tượng:** Menu BM "Khách hàng" có `pending: true` (`branchManagerNavigation.ts:6`), và route khách hàng chỉ cho OWNER. Permission Matrix: BM có toàn quyền xem/tạo/sửa khách hàng.
- **Cách sửa:** Đổi route thành `/customers` và `/customers/:id`, cho `['OWNER','BRANCH_MANAGER']`, rồi bỏ `pending` ở menu BM.

#### FE-11 · Trung bình – Sidebar và Profile không hiện tên cửa hàng
- **Hiện tượng:** Sidebar BM/Staff ghi "Phạm vi làm việc: Chưa được phân công cửa hàng" (`DashboardLayout.tsx:47`). Trang Thông tin cá nhân ghi "Cửa hàng #2".
- **Cách sửa:** Sửa BE-03 (nên làm), hoặc sau khi đăng nhập gọi `/api/home` một lần rồi lưu `storeName` vào AuthContext.

#### FE-12 · Trung bình · Home – Trang chủ Owner không dùng dữ liệu thật, không hiện số thông báo chưa đọc
- **Hiện tượng:**
  - `OwnerOverview` hiển thị 4 ô cứng "—" và không gọi `/api/home`.
  - Chuông thông báo (Owner, BM, Staff) chỉ hiện "Chưa có dữ liệu thông báo", trong khi `/api/home` đã trả `notifications.unread` và 5 thông báo mới nhất. Trang chủ BM/Staff có gọi `/api/home` nhưng không hiển thị phần thông báo.
  - Theo bảng Iteration 1, Home cần "số thông báo chưa đọc và lối tắt tới chứng từ chờ xử lý".
- **Cách sửa:**
  - Viết hook `useHome()` dùng chung, gọi `/api/home`.
  - Chuông hiện badge `unread` và dropdown 5 thông báo `latest`.
  - Owner overview map các shortcut `ADJUSTMENTS_AWAITING_APPROVAL`, `EXCESS_AWAITING_DECISION`, `STOCK_COUNTS_AWAITING_APPROVAL`, `REPLENISHMENTS_AWAITING_COORDINATION`.
  - Admin overview có thể dùng `LOCKED_ACCOUNTS`, `TEMPORARY_PASSWORD_ACCOUNTS`, `PLANNED_STORES`.

#### FE-13 · Trung bình · UC-13 – Owner tìm phụ tùng thấy cột "Khả dụng" toàn "—"
- **Hiện tượng:** `OwnerWorkspacePage` không gửi `storeId`.
- **Cách sửa:** Thêm ô chọn cửa hàng (danh sách lấy từ `/api/stores`) và gửi `storeId`, hoặc dùng tổng toàn chuỗi khi BE-05 xong.

#### FE-14 · Trung bình · UC-15 – Chưa lưu được ảnh phụ tùng
- **Hiện tượng:** Chọn ảnh rồi bấm Lưu nhận lỗi "BE chưa có API tải ảnh" (`ProductCreatePage.tsx:35`).
- **Cách sửa:** API upload Cloudinary đã có trên nhánh BE `feature/api-v1` (chưa merge). Sau khi merge: upload từng file, lấy URL https rồi gửi vào `imageUrls` (tối đa 3, ảnh đầu là ảnh chính).

#### FE-15 · Cao · UC-38 – Chưa có màn Branch Manager Permissions
- **Hiện tượng:** Menu Owner "Quyền danh mục và giá" có `pending: true` (`ownerNavigation.ts:25`).
- **Cách sửa:** Làm sau BE-01:
  - Trang `/owner/manager-permissions` lọc theo cửa hàng, liệt kê BM kèm 2 công tắc "Quyền danh mục" và "Quyền giá bán lẻ".
  - Bật/tắt mở popup nhập lý do, rồi gọi `PUT /branch-managers/{id}/permissions`.
  - Lưu thành công hiện MSG14.

#### FE-16 · Thấp · UC-12 – Lịch sử phân quyền gọi API theo từng tài khoản (N+1)
- **Hiện tượng:** 13 tài khoản sinh 52 request (trang chủ Admin cũng gọi như vậy). Chuỗi có vài trăm tài khoản sẽ rất chậm.
- **Cách sửa:** BE thêm `GET /api/accounts/permission-history?from=&to=&changeType=&q=&page=` đọc một lần từ `audit_log`; FE gọi một request có phân trang.

#### FE-17 · Thấp · UC-01 – Mở URL không có quyền thì bị chuyển trang mà không thông báo
- **Hiện tượng:** Owner gõ `/accounts`, hay Staff gõ `/stores`, bị đẩy về `/dashboard` mà không hiện MSG05 (R3 UC-01, Step 7 – Unauthorized destination).
- **Cách sửa:** `RequireRole` chuyển hướng kèm `state: { notice: 'Bạn không có quyền thực hiện thao tác này.' }`; DashboardLayout đọc `location.state` để hiện toast.

#### FE-18 · Thấp – Checkbox "Ghi nhớ phiên đăng nhập" không có tác dụng
- **Hiện tượng:** State `rememberSession` (`LoginPage.tsx:14`) không được dùng tới.
- **Cách sửa:** Bỏ checkbox. Phiên đã được giữ bằng refresh cookie 7 ngày.

#### FE-19 · Thấp · UC-02 – Quy tắc mật khẩu FE khác BE
- **Hiện tượng:** FE bắt buộc có cả chữ hoa và chữ thường (`passwordPolicy.ts:15`), còn BE/MSG43 chỉ yêu cầu có chữ cái. Ví dụ `abc@1234` được BE chấp nhận nhưng FE chặn.
- **Cách sửa:** Đổi điều kiện thành "Có ít nhất 1 chữ cái" (`/\p{L}/u`) cho khớp BE và MSG43.

#### FE-20 · Thấp – Code thừa
- **Hiện tượng:**
  - `OwnerWorkspacePage` còn nguyên popup "Thêm phụ tùng" cũ (state `creating` không bao giờ bằng `true`) và hàm `create` không được gọi.
  - `package.json` thiếu script `test`: chạy `node --test tests/` báo lỗi, phải chạy từng file.
- **Cách sửa:** Xóa popup và hàm thừa; thêm `"test": "node --test tests/*.test.mjs"` vào `package.json`.

### 3.3 Lệch giữa tài liệu và code (cần nhóm chốt)

| ID | Nội dung | Đề xuất |
|---|---|---|
| DOC-01 | **Ai cấp quyền danh mục?**<br>• R3 (UC-09 bước 2, UC-38, BR-05, Permission Matrix): Owner cấp, Admin chỉ xem.<br>• R4 3.2 (`can_edit_catalog`: "granted by Admin") và code hiện tại: Admin cấp qua `PUT /api/accounts/{id}/permissions` (field `catalogEdit`), FE Account List có công tắc "quyền sửa danh mục". | Theo R3 (bản SRS mới hơn):<br>• Bỏ `catalogEdit` khỏi `UpdatePermissionsRequest` (vẫn tự thu hồi khi tài khoản thôi là BM).<br>• FE Account List chỉ hiển thị quyền, không cho sửa.<br>• Việc cấp quyền chuyển sang BE-01 / FE-15.<br>• Sửa ghi chú trong R4 3.2. |
| DOC-02 | R4 ghi API dưới `/api/v1`; code `main` dùng `/api`. Nhánh BE `feature/api-v1` (5 commit chưa merge: chuyển `/api/v1`, danh sách thiết bị đăng nhập, upload Cloudinary) sẽ đổi toàn bộ đường dẫn. | Merge BE và FE **cùng lúc**: FE đổi `baseURL: '/api/v1'` trong `axiosClient.ts` (proxy `/api` của Vite vẫn dùng được). Nếu không, FE sẽ hỏng toàn bộ. |
| DOC-03 | R3 mục 5.3 có job "Unlock expired locked accounts – every 5 minutes"; code không có job mà kiểm tra `locked_until` lúc đăng nhập. Chức năng vẫn đúng. | Giữ cách hiện tại và ghi rõ trong R4. Hoặc thêm `@Scheduled(fixedDelay=5m)` xóa `locked_until` đã hết hạn nếu thầy yêu cầu đúng "job". |
| DOC-04 | R4 3.3 `auth_session` thiếu cột `previous_refresh_token_hash` và lý do thu hồi `REFRESH_TOKEN_REUSED` (thêm ở V8). | Cập nhật R4. |
| DOC-05 | Permission Matrix cấm Staff xem danh sách nhóm hàng / dòng xe, nhưng UC-13 lại cần chúng làm bộ lọc tìm kiếm (xem BE-04). | Cập nhật R3 hoặc thêm API lookup. |
| DOC-06 | Project Tracking ghi "V1–V9, 50 bảng". | Sửa thành "V1–V11, 44 bảng". |

## 4. Thứ tự sửa đề xuất

1. **FE-03, FE-04, FE-05**: lỗi trên màn đã có, sửa nhanh, ảnh hưởng mọi người dùng.
2. **FE-01, FE-02, FE-10**: BE đã sẵn sàng, chỉ cần nối API.
3. **FE-06, FE-07, FE-08, FE-09**: các màn danh mục còn thiếu.
4. **BE-01 → FE-15** (UC-38), **BE-03 → FE-11**, **BE-02**. Chốt DOC-01 trước khi làm BE-01.
5. **FE-12, FE-13, FE-14** và các lỗi Thấp.
6. Chốt **DOC-02** (merge `/api/v1`) trước khi demo.

## 5. Tự chạy lại test

```bash
# BE: test tự động
cd MotoFlow_BE && ./mvnw test
# BE: chạy server với dữ liệu demo (cổng 8080, PostgreSQL nhúng cổng 5433)
./mvnw spring-boot:test-run
psql -h localhost -p 5433 -U postgres -d postgres -v ON_ERROR_STOP=1 -f scripts/seed/seed_demo_data.sql
# FE
cd MotoFlow_FE && npm ci && npm run lint && npm run build && node --test tests/*.test.mjs && npm run dev
```

Tài khoản demo (mật khẩu `Demo@2026`):

| Vai trò | Tài khoản |
|---|---|
| Admin | `admin2` |
| Owner | `owner` |
| BM kho tổng | `bm.kho` |
| BM có quyền danh mục + giá | `bm.cg` |
| BM thường | `bm.hd` |
| Staff | `nv.kho1`, `nv.cg1`, `nv.hd1` |

Để test FE-05 nhanh: chạy BE với `-Dspring-boot.run.arguments="--motoflow.auth.access-token-ttl=3m"`, đăng nhập, chờ hơn 3 phút rồi thao tác bất kỳ.
