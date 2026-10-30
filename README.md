# MotoFlow Backend

Backend REST API của MotoFlow (SEP490-G64): quản lý bán hàng và kho cho chuỗi cửa hàng phụ tùng xe máy Nga Minh.

- Spring Boot 4, Java 21, Spring Security (JWT), Spring Data JPA (Hibernate 7), Flyway, PostgreSQL.
- Kiến trúc: **một ứng dụng (monolith)**, code chia **package theo feature**.
- Frontend React chạy riêng và gọi API này (mặc định từ `http://localhost:5173`).

---

## 1. Chạy project

### Yêu cầu
- JDK 21.
- Không cần cài Maven, vì đã có sẵn `mvnw`.
- Docker hoặc PostgreSQL là tùy chọn (xem cách B).

### Cách A: chạy với PostgreSQL nhúng (không cần Docker)

```bash
./mvnw spring-boot:test-run
```

- Ứng dụng chạy ở `http://localhost:8080`.
- Database là PostgreSQL nhúng, chạy ở cổng 5433.
- Dữ liệu được giữ trong thư mục `.dev-postgres/`. Xóa thư mục này để làm lại database từ đầu.
- Trên Windows (cmd/PowerShell) dùng `mvnw.cmd` thay cho `./mvnw`.

### Cách B: chạy với Docker

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Không có profile mặc định: thiếu profile thì ứng dụng không chạy (không có khóa JWT). Profile này dùng database `motoflow` ở `localhost:5432` (user và mật khẩu đều là `motoflow`).

### Tài khoản Admin đầu tiên
- Khi database chưa có Admin, ứng dụng tự tạo Admin theo `motoflow.bootstrap.admin.*` trong `application-dev.properties`.
- Đây là mật khẩu tạm: lần đăng nhập đầu tiên sẽ bị buộc đổi mật khẩu.

### Dữ liệu demo
- `psql -h localhost -U root -d motoflow -v ON_ERROR_STOP=1 -f scripts/seed/seed_demo_data.sql` nạp dữ liệu Nga Minh
  (3 cửa hàng, tài khoản mọi vai trò, 30 sản phẩm, tồn kho, nhập hàng, điều chuyển, bán hàng, công nợ) vào database đã ở V11.
  Chạy lại không làm gì; tài khoản Admin có sẵn không bị đụng tới. Mọi tài khoản demo dùng mật khẩu `Demo@2026`.
- `scripts/seed/check_stock_consistency.sql` kiểm tra `stock_balance` = `lot_balance` = `stock_movement`; không có dòng nào là khớp.

### Tài liệu API
Swagger UI: `http://localhost:8080/swagger-ui.html` (tắt ở profile `prod`).
1. Đăng nhập bằng `POST /api/v1/auth/login`.
2. Copy `accessToken` vào nút **Authorize**.

### Chạy test

```bash
./mvnw test
```

- Test chạy trên PostgreSQL nhúng, không cần Docker.
- Lần chạy đầu Maven tải PostgreSQL về nên hơi lâu.

### Biến môi trường khi deploy
Trên server không dùng profile `dev`, nên phải đặt:

| Biến | Ý nghĩa |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (tắt Swagger, đọc IP client từ `X-Forwarded-For`) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | kết nối PostgreSQL |
| `MOTOFLOW_JWT_SECRET` | bắt buộc; khóa ký JWT, base64, tối thiểu 256 bit, **không dùng khóa dev** |
| `MOTOFLOW_CORS_ORIGINS` | các domain frontend, cách nhau bằng dấu phẩy |
| `MOTOFLOW_ADMIN_USERNAME`, `MOTOFLOW_ADMIN_PASSWORD` | Admin đầu tiên, chỉ dùng khi DB chưa có Admin; mật khẩu phải đúng chính sách (chữ, số, ký tự đặc biệt, 8–50 ký tự) |

---

## 2. Cấu trúc code

```
src/main/java/fu/hl/g64/motorflow/
├── MotorFlowApplication.java
├── security/            JWT filter, SecurityConfig, AuthenticatedUser (người đang đăng nhập)
├── common/
│   ├── error/           BusinessException, GlobalExceptionHandler, ApiError
│   ├── audit/           AuditLog (NFR-S06)
│   └── config/          Clock (giờ Việt Nam), OpenAPI
├── auth/                UC-01 đăng nhập, UC-02 đổi mật khẩu, refresh token
├── account/             UC-08 tạo tài khoản, UC-11 cấp lại mật khẩu, quyền
├── home/                Trang chủ sau đăng nhập (SCR-03): thông báo chưa đọc, việc đang chờ theo vai trò
├── store/               Store (mỗi cửa hàng là một kho, có cờ kho tổng), UC-05 sao chép cấu hình cửa hàng
├── catalog/             PartCategory, VehicleModel, Product, ProductIdentifier, ProductImage, ProductVehicleModel
├── pricing/             PricingPolicy
├── partner/             Customer, Supplier; UC-26..29 khách hàng, UC-30..33 nhà cung cấp
├── sales/               SalesOrder, SalesOrderLine (phiên bản cũ của đơn nằm trong audit_log)
├── receivable/          Receivable, Payment, PaymentAllocation
├── receiving/           GoodsReceiptRequest(+Line), GoodsReceipt(+Line)
├── transfer/            ReplenishmentRequest(+Line), TransferOrder(+Line)
├── discrepancy/         DiscrepancyCase, DiscrepancyResolutionEvent
├── inventory/           StockBalance, StockMovement, Lot, LotBalance, InventoryPeriod, StockCount(+Line), StockCountRequest(+Product),
│                        PeriodStockBalance
├── adjustment/          AdjustmentRequest, AdjustmentStockLine, AdjustmentAmountLine
└── operation/           WarehouseTask, Notification, Attachment
```

Mỗi feature dùng các package con sau, chỉ tạo package nào thực sự cần:

| Package | Chứa |
|---|---|
| `controller/` | REST controller: nhận request, `@Valid`, phân quyền `@PreAuthorize`, gọi service |
| `service/` | nghiệp vụ của use case, `@Transactional` |
| `repository/` | `JpaRepository<Entity, Long>` |
| `entity/` | entity JPA; enum đặt trong `entity/enums/` |
| `dto/` | request/response (Java `record`) |
| `config/` | cấu hình riêng của feature |

Test đặt ở cùng tên package trong `src/test/java` (ví dụ `account/AccountAdminApiTest.java`).

Feature nào cũng được dùng repository/service của feature khác, vì đây là monolith thường, không có ranh giới module.
Ngoại lệ duy nhất: **ghi tồn kho** (`stock_balance`, `stock_movement`) chỉ đi qua service của `inventory/`, không gọi thẳng repository của nó. Nhờ vậy, mọi chỗ đều ghi tồn kho cùng một cách (BR-29, không âm kho).

---

## 3. Database

### Nguồn gốc
- Schema theo Report 4, mục 3 (Database Design, 50 bảng). V1..V5 dựng thiết kế logic 43 bảng ban đầu
  (`MotoFlow_Logical_3NF/model.py`, cùng cấp với thư mục `SourceCode`); V7 đưa schema lên 50 bảng của Report 4.
- `schema_postgres.sql` được sinh từ `model.py` bằng `python ddl.py`.

### Flyway migration
Các migration nằm trong `src/main/resources/db/migration/`:

| File | Nội dung |
|---|---|
| `V1__store_and_user_account.sql` | store, warehouse, user_account, user_permission, permission_history |
| `V2__auth_session.sql` | auth_session (phiên đăng nhập, refresh token) |
| `V3__audit_log.sql` | audit_log |
| `V4__updated_at_default_and_fk_indexes.sql` | `DEFAULT NOW()` và index cho FK |
| `V5__remaining_tables.sql` | 36 bảng còn lại |
| `V6__single_central_warehouse.sql` | Chuỗi chỉ có một kho tổng |
| `V7__r4_database_design_50_tables.sql` | Report 4 mục 3: 43 → 50 bảng (bỏ mã do hệ thống cấp, `pricing_policy`, hạng khách hàng, lô, kỳ kho, kiểm kê, số dư kỳ/ngày) |
| `V10__receivable_due_date_payment_allocation.sql` | `receivable.due_date`; `payment_allocation` thay `payment.receivable_id` (một lần trả nợ trả được nhiều khoản nợ) |
| `V11__reduce_to_44_tables.sql` | 51 → 44 bảng: `warehouse` gộp vào `store` (`is_central`, cột `*_warehouse_id` thành `*_store_id`); `user_permission` thành 2 cờ trên `user_account`; `permission_history`, `credit_eligibility_event`, `sales_order_version` chuyển vào `audit_log`; bỏ `unmet_demand`, `daily_stock_balance` |

**Quy tắc:**
- **Không sửa migration đã có** (Flyway sẽ báo checksum sai trên mọi máy đã chạy nó).
- Muốn đổi DB thì làm theo thứ tự:
  1. Sửa `model.py`.
  2. Chạy `python ddl.py` và `python build.py`.
  3. Viết `V<n+1>__mo_ta.sql` chỉ chứa phần thay đổi.
  4. Sửa entity cho khớp.
- `spring.jpa.hibernate.ddl-auto=validate`: Hibernate không tự tạo bảng. Nếu entity lệch với DB thì ứng dụng không khởi động được, và test cũng fail. Đây là cách phát hiện lệch sớm.

---

## 4. Quy ước code

### Entity
Viết theo mẫu `UserAccount`, `Store`:

```java
@Entity
@Table(name = "transfer_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_store_id", nullable = false)
    private Long sourceStoreId;              // FK giữ dạng id, không dùng @ManyToOne
    ...
    public static TransferOrder create(..., LocalDateTime now) { ... }   // tạo mới
    public void dispatch(Long by, LocalDateTime now) { ... }             // đổi trạng thái
}
```

- **Không có setter.** Dữ liệu chỉ đổi qua factory method `create(...)` hoặc method nghiệp vụ (`dispatch`, `cancel`, …). Quy tắc nghiệp vụ (BR-xx) được kiểm ngay trong method đó.
- **FK lưu dạng `Long xxxId`.** Chỉ dùng `@ManyToOne` khi thật sự cần load đối tượng kia (ví dụ `AuthSession.user`).
- **Cột có danh sách giá trị** (CHECK IN) dùng `enum` + `@Enumerated(EnumType.STRING)`. Mỗi bảng có enum trạng thái riêng (`TransferOrderStatus`, …). Enum đặt trong `entity/enums/` (không đặt tên `enum` được vì đó là từ khóa Java).
- **Thời gian:**
  - Entity mới sinh hiện chỉ có field. Khi code UC nào thì thêm method cho bảng đó.
  - Luôn lấy giờ từ bean `Clock` (`LocalDateTime.now(clock)`), không gọi `LocalDateTime.now()` trực tiếp. Nhờ vậy test chỉnh được giờ (`MutableClock`).
  - Phải gán `createdAt` và `updatedAt` trong code. Hibernate ghi cả giá trị `null`, nên `DEFAULT NOW()` của DB không có tác dụng.
- **Bảng chỉ ghi thêm** (`stock_movement`, `permission_history`, `audit_log`, `credit_eligibility_event`): không viết method sửa hay xóa.
- **Tồn kho:**
  - `stock_balance` chỉ thay đổi cùng transaction với các dòng `stock_movement` gây ra thay đổi đó (BR-29).
  - Khi cập nhật số lượng, dùng câu `UPDATE ... SET available_qty = available_qty - :q` (repository `@Modifying`) hoặc khóa dòng, để hai người thao tác cùng lúc không ghi đè nhau.

### Service
- Mỗi use case là một method public có `@Transactional`.
- Đầu method ghi chú UC tương ứng (ví dụ `// UC-08`).
- Nhận `AuthenticatedUser` (người đang đăng nhập) để kiểm tra role, cửa hàng và quyền.
- Thao tác quan trọng thì ghi `AuditLog.record(...)` (xem `AccountService`).

### Lỗi và thông báo
- Lỗi nghiệp vụ: `throw new BusinessException(HttpStatus.CONFLICT, "MSG45", username);`.
- Mã `MSGxx` lấy từ bảng System Messages của SRS. Nội dung tiếng Việt nằm trong `messages.properties`.
- Thông báo chưa có trong SRS thì thêm mã mới (sau MSG48) vào `messages.properties`, và báo người viết SRS bổ sung.
- Mọi lỗi trả về cùng một dạng: `{status, code, message, path, fieldErrors?}` (`GlobalExceptionHandler`). Không để lộ lỗi SQL hay stack trace ra API.

### Controller
- URL dạng `/api/v1/<tài-nguyên>`, ví dụ `/api/v1/accounts`, `/api/v1/transfer-orders/{id}/dispatch`.
- Phân quyền theo role bằng `@PreAuthorize("hasRole('OWNER')")`. Phạm vi cửa hàng và quyền được cấp thì kiểm trong service.
- Ghi `@Tag` / `@Operation` kèm mã UC để Swagger dễ đọc.
- Request/response dùng `record` trong `dto/`, không trả entity ra ngoài.

### Test
- Test tích hợp kế thừa `support/IntegrationTest` (PostgreSQL nhúng + MockMvc).
- Đầu mỗi test gọi `resetDatabaseAndClock()`.
- Mỗi UC tối thiểu test: luồng chính, từng luồng lỗi có mã MSG, và trường hợp sai role.

---

## 5. Thêm một use case mới

Ví dụ thêm UC-xx "Dispatch Transfer Order":

1. Đọc UC spec và các BR liên quan trong Report 3.
2. **Entity:** thêm method nghiệp vụ vào `TransferOrder` (`dispatch(...)`), kiểm trạng thái và BR ngay trong đó.
3. **Repository:** thêm query nếu cần (`findBy...`, hoặc `@Query`).
4. **Service:** tạo `transfer/service/TransferOrderService` với method `dispatch(user, id, request)` có `@Transactional`. Method này kiểm quyền và cửa hàng, gọi entity, ghi `stock_movement` và audit.
5. **DTO + controller:** `POST /api/v1/transfer-orders/{id}/dispatch`, có `@PreAuthorize`.
6. **Message:** thêm mã MSG mới vào `messages.properties` nếu cần.
7. **Test:** `transfer/TransferOrderApiTest extends IntegrationTest`.
8. Chạy `./mvnw test` cho xanh rồi mới đẩy code.
