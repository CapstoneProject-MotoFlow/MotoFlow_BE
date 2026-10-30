-- MotoFlow demo data (Nga Minh motorbike parts), for trying the system end to end on a local database.
-- Not a Flyway migration: run it by hand on a database already migrated to V11.
--
--   psql -h localhost -U root -d motoflow -v ON_ERROR_STOP=1 -f scripts/seed/seed_demo_data.sql
--
-- Runs in one transaction. Re-running is a no-op once the demo central store exists.
-- The existing Admin account(s) are never touched. Every demo account's password is Demo@2026.
--
-- Stock is built only from lots and stock_movement rows; lot_balance and stock_balance are then summed from
-- the movements, so stock_balance = sum(lot_balance) = net stock_movement per store, product and status.

BEGIN;

-- Helpers live in pg_temp and disappear with the session.
CREATE FUNCTION pg_temp.ts(f numeric) RETURNS timestamp LANGUAGE sql AS
$$ SELECT date_trunc('month', localtimestamp) + (localtimestamp - date_trunc('month', localtimestamp)) * f $$;

CREATE FUNCTION pg_temp.pid(p_sku text) RETURNS bigint LANGUAGE sql AS
$$ SELECT id FROM product WHERE sku = p_sku $$;

CREATE FUNCTION pg_temp.uid(p_username text) RETURNS bigint LANGUAGE sql AS
$$ SELECT id FROM user_account WHERE username = p_username $$;

CREATE FUNCTION pg_temp.opening_lot(p_store bigint, p_sku text) RETURNS bigint LANGUAGE sql AS
$$ SELECT id FROM lot WHERE store_id = p_store AND product_id = pg_temp.pid(p_sku) AND source_type = 'OPENING' $$;

-- One stock_movement row; cost comes from the lot, the period from the movement time.
CREATE FUNCTION pg_temp.mv(p_store bigint, p_lot bigint, p_status text, p_qty int, p_type text, p_by bigint,
                           p_at timestamp, p_so bigint, p_gr bigint, p_to bigint, p_sc bigint, p_reason text)
    RETURNS void LANGUAGE sql AS
$$
INSERT INTO stock_movement (store_id, product_id, stock_status, quantity_change, movement_type, sales_order_id,
                            goods_receipt_id, transfer_order_id, stock_count_id, reason, performed_by, occurred_at,
                            lot_id, inventory_period_id, unit_cost, value)
SELECT p_store, l.product_id, p_status, p_qty, p_type, p_so, p_gr, p_to, p_sc, p_reason, p_by, p_at,
       l.id, ip.id, l.purchase_unit_price, p_qty::bigint * l.purchase_unit_price
FROM lot l JOIN inventory_period ip ON ip.period_month = date_trunc('month', p_at)::date
WHERE l.id = p_lot
$$;

-- A delivered sales order line, issued from the store's opening lot.
CREATE FUNCTION pg_temp.sell(p_so bigint, p_store bigint, p_line int, p_sku text, p_qty int, p_price bigint,
                             p_by bigint, p_at timestamp) RETURNS bigint LANGUAGE sql AS
$$
INSERT INTO sales_order_line (sales_order_id, line_no, product_id, requested_qty, picked_qty, delivered_qty,
                              reference_price, actual_unit_price)
SELECT p_so, p_line, pg_temp.pid(p_sku), p_qty, p_qty, p_qty,
       (SELECT unit_price FROM pricing_policy WHERE policy_type = 'RETAIL_PRICE' AND status = 'ACTIVE'
            AND product_id = pg_temp.pid(p_sku)), p_price;
SELECT pg_temp.mv(p_store, pg_temp.opening_lot(p_store, p_sku), 'AVAILABLE', -p_qty, 'SALE_ISSUE', p_by, p_at,
                  p_so, NULL, NULL, NULL, NULL);
SELECT p_qty::bigint * p_price
$$;

DO $seed$
DECLARE
    pw        CONSTANT text := '{bcrypt}$2a$10$/U3swv95nrGOusDZ9z.oJuUKq9Q/xnfniQ4BILiDExRN3BzzCCBpS'; -- Demo@2026
    cur_month date := date_trunc('month', localtimestamp)::date;
    prev_month date := (date_trunc('month', localtimestamp) - interval '1 month')::date;
    opening_at timestamp := (date_trunc('month', localtimestamp) - interval '1 month') + interval '1 day 8 hours';
    s_central bigint; s_cg bigint; s_hd bigint;
    u_owner bigint; u_bm_kho bigint; u_bm_cg bigint; u_bm_hd bigint;
    u_kho1 bigint; u_kho2 bigint; u_cg1 bigint; u_cg2 bigint; u_hd1 bigint; u_hd2 bigint;
    p_prev bigint; p_cur bigint;
    sup1 bigint; sup2 bigint; sup3 bigint; sup4 bigint;
    c_tuan bigint; c_hai bigint; c_linh bigint; c_phuc bigint; c_an bigint;
    grr1 bigint; grr2 bigint; grr3 bigint; grr4 bigint;
    gr1 bigint; gr2 bigint; gr3 bigint; gr_tr bigint;
    grl bigint; v_lot bigint;
    rr1 bigint; rr2 bigint; rr3 bigint;
    tr1 bigint; tr2 bigint; tr3 bigint;
    so1 bigint; so2 bigint; so3 bigint; so4 bigint; so5 bigint;
    total bigint; pay bigint; rcv bigint; rcv2 bigint;
    sc_cycle bigint; scr1 bigint; adj1 bigint; dc1 bigint;
    st record;
BEGIN
    IF EXISTS (SELECT 1 FROM store WHERE store_name = 'Kho tổng Nga Minh') THEN
        RAISE NOTICE 'Demo data already present, nothing to do.';
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM store WHERE is_central) THEN
        RAISE EXCEPTION 'Another central store already exists; this script expects a database with no stores.';
    END IF;

    -- ===== Stores =====
    INSERT INTO store (store_name, address, phone, status, is_central)
    VALUES ('Kho tổng Nga Minh', 'Số 12 Nguyễn Văn Linh, Long Biên, Hà Nội', '0243 8721 456', 'ACTIVE', TRUE)
    RETURNING id INTO s_central;
    INSERT INTO store (store_name, address, phone, status, is_central)
    VALUES ('Nga Minh Cầu Giấy', 'Số 88 Trần Thái Tông, Cầu Giấy, Hà Nội', '0243 7934 112', 'ACTIVE', FALSE)
    RETURNING id INTO s_cg;
    INSERT INTO store (store_name, address, phone, status, is_central)
    VALUES ('Nga Minh Hà Đông', 'Số 45 Quang Trung, Hà Đông, Hà Nội', '0243 3512 879', 'ACTIVE', FALSE)
    RETURNING id INTO s_hd;

    -- ===== Accounts (password Demo@2026, no forced change) =====
    INSERT INTO user_account (username, password_hash, full_name, phone, email, role, store_id, must_change_password,
                              created_by, can_edit_catalog, can_edit_retail_price)
    SELECT v.username, pw, v.full_name, v.phone, v.email, v.role, v.store_id, FALSE,
           (SELECT min(id) FROM user_account WHERE role = 'ADMIN'), v.cat, v.price
    FROM (VALUES
        ('admin2',  'Phạm Thu Trang',  '0912000001', 'trang.pt@ngaminh.vn', 'ADMIN',          NULL::bigint, FALSE, FALSE),
        ('owner',   'Nguyễn Văn Minh', '0912000002', 'minh.nv@ngaminh.vn',  'OWNER',          NULL,      FALSE, FALSE),
        ('bm.kho',  'Trần Quốc Hùng',  '0912000003', 'hung.tq@ngaminh.vn',  'BRANCH_MANAGER', s_central, FALSE, FALSE),
        ('bm.cg',   'Lê Thị Hoa',      '0912000004', 'hoa.lt@ngaminh.vn',   'BRANCH_MANAGER', s_cg,      TRUE,  TRUE),
        ('bm.hd',   'Đỗ Mạnh Cường',   '0912000005', 'cuong.dm@ngaminh.vn', 'BRANCH_MANAGER', s_hd,      FALSE, FALSE),
        ('nv.kho1', 'Vũ Văn Nam',      '0912000006', NULL,                  'STAFF',          s_central, FALSE, FALSE),
        ('nv.kho2', 'Hoàng Đức Long',  '0912000007', NULL,                  'STAFF',          s_central, FALSE, FALSE),
        ('nv.cg1',  'Ngô Thị Lan',     '0912000008', NULL,                  'STAFF',          s_cg,      FALSE, FALSE),
        ('nv.cg2',  'Bùi Văn Tú',      '0912000009', NULL,                  'STAFF',          s_cg,      FALSE, FALSE),
        ('nv.hd1',  'Đặng Thị Mai',    '0912000010', NULL,                  'STAFF',          s_hd,      FALSE, FALSE),
        ('nv.hd2',  'Phan Văn Khoa',   '0912000011', NULL,                  'STAFF',          s_hd,      FALSE, FALSE)
    ) AS v(username, full_name, phone, email, role, store_id, cat, price);

    u_owner := pg_temp.uid('owner');
    u_bm_kho := pg_temp.uid('bm.kho'); u_bm_cg := pg_temp.uid('bm.cg'); u_bm_hd := pg_temp.uid('bm.hd');
    u_kho1 := pg_temp.uid('nv.kho1'); u_kho2 := pg_temp.uid('nv.kho2');
    u_cg1 := pg_temp.uid('nv.cg1'); u_cg2 := pg_temp.uid('nv.cg2');
    u_hd1 := pg_temp.uid('nv.hd1'); u_hd2 := pg_temp.uid('nv.hd2');

    -- Permission history, in the shape PermissionService writes.
    INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason, old_data, new_data)
    SELECT 'USER_ACCOUNT', id, 'ACCOUNT_CREATED', created_by, opening_at - interval '1 day', 'Dữ liệu demo',
           NULL, jsonb_build_object('value', role)
    FROM user_account WHERE id <> created_by AND username IN
        ('admin2','owner','bm.kho','bm.cg','bm.hd','nv.kho1','nv.kho2','nv.cg1','nv.cg2','nv.hd1','nv.hd2');
    INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason, old_data, new_data)
    VALUES ('USER_ACCOUNT', u_bm_cg, 'PERMISSION_GRANTED', (SELECT created_by FROM user_account WHERE id = u_bm_cg),
            opening_at - interval '1 day', 'Quản lý danh mục chi nhánh',
            '{"permission_code":"CATALOG_EDIT"}', '{"permission_code":"CATALOG_EDIT"}'),
           ('USER_ACCOUNT', u_bm_cg, 'PERMISSION_GRANTED', u_owner,
            opening_at - interval '1 day', 'Owner giao quyền sửa giá bán lẻ',
            '{"permission_code":"RETAIL_PRICE_EDIT"}', '{"permission_code":"RETAIL_PRICE_EDIT"}');

    -- ===== Catalog =====
    INSERT INTO part_category (category_name, vehicle_type, description) VALUES
        ('Động cơ', 'MOTORCYCLE', 'Phụ tùng động cơ'),
        ('Hệ thống phanh', 'MOTORCYCLE', 'Má phanh, dây phanh, đĩa phanh'),
        ('Truyền động', 'MOTORCYCLE', 'Nhông xích, dây curoa'),
        ('Hệ thống điện', 'MOTORCYCLE', 'Ắc quy, bóng đèn, IC'),
        ('Dầu nhớt', 'MOTORCYCLE', 'Nhớt máy, nhớt hộp số'),
        ('Lốp - săm', 'MOTORCYCLE', 'Lốp và săm xe máy'),
        ('Thân vỏ - phụ kiện', 'MOTORCYCLE', 'Gương, ốp, phụ kiện'),
        ('Phụ tùng xe điện', 'E_MOTORCYCLE', 'Phụ tùng cho xe máy điện');
    INSERT INTO part_category (category_name, vehicle_type, parent_id)
    SELECT c.name, p.vehicle_type, p.id
    FROM (VALUES ('Bugi', 'Động cơ'), ('Lọc gió', 'Động cơ'), ('Piston - xéc măng', 'Động cơ'),
                 ('Má phanh', 'Hệ thống phanh'), ('Dây phanh', 'Hệ thống phanh'),
                 ('Nhông xích', 'Truyền động'), ('Dây curoa', 'Truyền động'),
                 ('Ắc quy', 'Hệ thống điện'), ('Bóng đèn', 'Hệ thống điện'), ('IC - CDI', 'Hệ thống điện'),
                 ('Phanh xe điện', 'Phụ tùng xe điện')) AS c(name, parent)
    JOIN part_category p ON p.category_name = c.parent AND p.parent_id IS NULL;

    CREATE TEMP TABLE demo_model (code text, manufacturer text, model_name text, vehicle_type text, y_from int, y_to int)
        ON COMMIT DROP;
    INSERT INTO demo_model VALUES
        ('WAVE', 'Honda', 'Wave Alpha 110', 'MOTORCYCLE', 2017, NULL),
        ('VISION', 'Honda', 'Vision 110', 'MOTORCYCLE', 2014, NULL),
        ('AB', 'Honda', 'Air Blade 125', 'MOTORCYCLE', 2016, NULL),
        ('LEAD', 'Honda', 'Lead 125', 'MOTORCYCLE', 2018, NULL),
        ('SH', 'Honda', 'SH Mode 125', 'MOTORCYCLE', 2020, NULL),
        ('EXC', 'Yamaha', 'Exciter 150', 'MOTORCYCLE', 2015, 2021),
        ('SIR', 'Yamaha', 'Sirius 110', 'MOTORCYCLE', 2012, NULL),
        ('JAN', 'Yamaha', 'Janus 125', 'MOTORCYCLE', 2016, NULL),
        ('VF', 'VinFast', 'Feliz S', 'E_MOTORCYCLE', 2022, NULL);
    INSERT INTO vehicle_model (manufacturer, model_name, vehicle_type, year_from, year_to)
    SELECT manufacturer, model_name, vehicle_type, y_from, y_to FROM demo_model;

    -- qty_* = opening count per store; NULL means the store does not stock the product.
    CREATE TEMP TABLE demo_product (sku text, part_name text, category text, brand text, origin text, unit text,
        cost bigint, retail bigint, models text, oem text, barcode text, qty_c int, qty_cg int, qty_hd int,
        expiry_months int) ON COMMIT DROP;
    INSERT INTO demo_product VALUES
        ('BUG-NGK-CPR8', 'Bugi NGK CPR8EA-9', 'Bugi', 'NGK', 'Nhật Bản', 'Cái', 42000, 65000, 'WAVE,SIR,EXC', '31916-KRM-841', '8936012340011', 60, 25, 20, NULL),
        ('BUG-NGK-CPR9', 'Bugi NGK CPR9EA-9', 'Bugi', 'NGK', 'Nhật Bản', 'Cái', 45000, 70000, 'VISION,AB,LEAD', '31926-KZR-601', '8936012340028', 50, 20, 18, NULL),
        ('LGI-WAVE', 'Lọc gió Wave Alpha', 'Lọc gió', 'Honda', 'Việt Nam', 'Cái', 38000, 60000, 'WAVE', '17210-KWW-640', '8936012340035', 40, 15, 12, NULL),
        ('LGI-VISION', 'Lọc gió Vision', 'Lọc gió', 'Honda', 'Việt Nam', 'Cái', 55000, 85000, 'VISION', '17210-K44-V00', '8936012340042', 35, 12, 10, NULL),
        ('LGI-AB125', 'Lọc gió Air Blade 125', 'Lọc gió', 'Honda', 'Việt Nam', 'Cái', 62000, 95000, 'AB', '17210-K66-V00', '8936012340059', 30, 10, 8, NULL),
        ('PIS-WAVE-STD', 'Bộ piston xéc măng Wave 110 (cos STD)', 'Piston - xéc măng', 'Honda', 'Việt Nam', 'Bộ', 210000, 320000, 'WAVE', '13101-KWW-740', '8936012340066', 15, 5, 4, NULL),
        ('XEC-SIR', 'Bộ xéc măng Sirius', 'Piston - xéc măng', 'Yamaha', 'Việt Nam', 'Bộ', 95000, 150000, 'SIR', '5C6-E1603-00', '8936012340073', 15, 6, 5, NULL),
        ('MPH-VISION', 'Má phanh trước Vision', 'Má phanh', 'Honda', 'Việt Nam', 'Cặp', 75000, 120000, 'VISION,LEAD', '06455-K44-V01', '8936012340080', 40, 15, 12, NULL),
        ('MPH-AB', 'Má phanh đĩa trước Air Blade', 'Má phanh', 'Honda', 'Việt Nam', 'Cặp', 85000, 135000, 'AB,SH', '06455-KVG-V01', '8936012340097', 35, 12, 10, NULL),
        ('MPH-EXC', 'Má phanh trước Exciter 150', 'Má phanh', 'Yamaha', 'Việt Nam', 'Cặp', 90000, 140000, 'EXC', '2PH-F5805-00', '8936012340103', 30, 10, 8, NULL),
        ('DPH-WAVE', 'Dây phanh sau Wave', 'Dây phanh', 'Honda', 'Việt Nam', 'Cái', 32000, 50000, 'WAVE', '43450-KWW-640', '8936012340110', 30, 10, 10, NULL),
        ('NX-WAVE', 'Bộ nhông xích Wave (DID)', 'Nhông xích', 'DID', 'Thái Lan', 'Bộ', 240000, 350000, 'WAVE', '06401-KWW-900', '8936012340127', 20, 8, 6, NULL),
        ('NX-EXC', 'Bộ nhông xích Exciter 150', 'Nhông xích', 'DID', 'Thái Lan', 'Bộ', 380000, 550000, 'EXC', '2ND-F5450-00', '8936012340134', 15, 6, 5, NULL),
        ('CUROA-VISION', 'Dây curoa Vision', 'Dây curoa', 'Bando', 'Nhật Bản', 'Cái', 185000, 280000, 'VISION', '23100-K44-V01', '8936012340141', 25, 8, 6, NULL),
        ('CUROA-AB', 'Dây curoa Air Blade 125', 'Dây curoa', 'Bando', 'Nhật Bản', 'Cái', 210000, 320000, 'AB,LEAD', '23100-K35-V01', '8936012340158', 20, 8, 6, NULL),
        ('CUROA-JAN', 'Dây curoa Janus', 'Dây curoa', 'Yamaha', 'Việt Nam', 'Cái', 175000, 260000, 'JAN', '2BM-E7641-00', '8936012340165', 15, 6, 5, NULL),
        ('AQ-GS-5', 'Ắc quy GS GTZ5S 12V-3.5Ah', 'Ắc quy', 'GS', 'Việt Nam', 'Chiếc', 290000, 420000, 'WAVE,VISION,SIR,JAN', 'GTZ5S-E', '8936012340172', 20, 6, 6, 24),
        ('AQ-GS-7', 'Ắc quy GS GTZ7V 12V-6.3Ah', 'Ắc quy', 'GS', 'Việt Nam', 'Chiếc', 420000, 600000, 'AB,SH,EXC', 'GTZ7V', '8936012340189', 15, 5, 4, 24),
        ('BD-HS1-PH', 'Bóng đèn pha Philips HS1 12V 35/35W', 'Bóng đèn', 'Philips', 'Trung Quốc', 'Cái', 48000, 75000, 'WAVE,SIR', '12636', '8936012340196', 40, 15, 10, NULL),
        ('BD-LED-T10', 'Bóng đèn xi-nhan LED T10', 'Bóng đèn', 'Osram', 'Trung Quốc', 'Cái', 15000, 25000, NULL, NULL, '8936012340202', 100, 30, 30, NULL),
        ('NHOT-MOTUL-08', 'Nhớt Motul 3100 Gold 10W40 0.8L', 'Dầu nhớt', 'Motul', 'Pháp', 'Chai', 95000, 130000, 'WAVE,SIR,EXC', NULL, '8936012340219', 120, 40, 35, 36),
        ('NHOT-CASTROL-1L', 'Nhớt Castrol Power1 10W40 1L', 'Dầu nhớt', 'Castrol', 'Singapore', 'Chai', 115000, 160000, 'EXC,WAVE', NULL, '8936012340226', 100, 35, 30, 36),
        ('NHOT-HONDA-HS', 'Nhớt hộp số Honda tay ga 120ml', 'Dầu nhớt', 'Honda', 'Việt Nam', 'Chai', 22000, 35000, 'VISION,AB,LEAD,SH', '08294-M99-K8LN9', '8936012340233', 80, 30, 25, 36),
        ('NHOT-YAMALUBE', 'Nhớt Yamalube tay ga 0.8L', 'Dầu nhớt', 'Yamaha', 'Việt Nam', 'Chai', 85000, 120000, 'JAN', '90793-AT415', '8936012340240', 60, 20, 20, 36),
        ('LOP-IRC-80', 'Lốp IRC 80/90-17 NR73', 'Lốp - săm', 'IRC', 'Indonesia', 'Chiếc', 280000, 390000, 'WAVE,SIR', 'NR73-80/90-17', '8936012340257', 20, 8, 6, NULL),
        ('LOP-MICH-90', 'Lốp Michelin City Grip 90/90-14', 'Lốp - săm', 'Michelin', 'Thái Lan', 'Chiếc', 520000, 720000, 'VISION,AB,LEAD', NULL, '8936012340264', 12, 5, 4, NULL),
        ('SAM-CS-17', 'Săm Chengshin 2.50-17', 'Lốp - săm', 'Chengshin', 'Việt Nam', 'Chiếc', 55000, 85000, 'WAVE,SIR', NULL, '8936012340271', 50, 20, 15, NULL),
        ('GUONG-VISION', 'Gương chiếu hậu Vision (cặp)', 'Thân vỏ - phụ kiện', 'Honda', 'Việt Nam', 'Cặp', 95000, 150000, 'VISION', '88110-K44-V00', '8936012340288', 15, 5, 5, NULL),
        ('CDI-WAVE', 'IC (CDI) Wave Alpha', 'IC - CDI', 'Honda', 'Việt Nam', 'Cái', 260000, 380000, 'WAVE', '30410-KWW-641', '8936012340295', 8, 3, NULL, NULL),
        ('MPH-VF-FELIZ', 'Má phanh xe điện VinFast Feliz S', 'Phanh xe điện', 'VinFast', 'Việt Nam', 'Cặp', 110000, 165000, 'VF', NULL, '8936012340301', 10, NULL, NULL, NULL);

    INSERT INTO product (sku, part_name, category_id, brand, country_of_origin, base_unit, created_by, initial_unit_cost,
                         created_at, updated_at)
    SELECT d.sku, d.part_name, c.id, d.brand, d.origin, d.unit, u_owner, d.cost,
           opening_at - interval '2 days', opening_at - interval '2 days'
    FROM demo_product d JOIN part_category c ON c.category_name = d.category;

    INSERT INTO product_identifier (product_id, identifier_type, identifier_value)
    SELECT pg_temp.pid(sku), 'OEM', oem FROM demo_product WHERE oem IS NOT NULL
    UNION ALL
    SELECT pg_temp.pid(sku), 'BARCODE', barcode FROM demo_product WHERE barcode IS NOT NULL;

    INSERT INTO product_vehicle_model (product_id, vehicle_model_id)
    SELECT pg_temp.pid(d.sku), vm.id
    FROM demo_product d
    CROSS JOIN LATERAL unnest(string_to_array(d.models, ',')) AS m(code)
    JOIN demo_model dm ON dm.code = m.code
    JOIN vehicle_model vm ON vm.manufacturer = dm.manufacturer AND vm.model_name = dm.model_name;

    -- ===== Pricing policy =====
    INSERT INTO pricing_policy (policy_type, product_id, unit_price, effective_from, status, set_by, set_at)
    SELECT 'RETAIL_PRICE', pg_temp.pid(sku), retail, opening_at - interval '1 day', 'ACTIVE', u_owner,
           opening_at - interval '2 days'
    FROM demo_product;
    -- A price raise already scheduled for next month (stays SCHEDULED).
    INSERT INTO pricing_policy (policy_type, product_id, unit_price, effective_from, status, set_by, set_at)
    VALUES ('RETAIL_PRICE', pg_temp.pid('NHOT-MOTUL-08'), 135000, (cur_month + interval '1 month')::timestamp,
            'SCHEDULED', u_owner, pg_temp.ts(0.5));
    INSERT INTO pricing_policy (policy_type, category_id, markup_rate, rounding_rule, effective_from, status, set_by, set_at)
    SELECT 'MARKUP_RATE', id, CASE WHEN category_name = 'Dầu nhớt' THEN 20 ELSE 30 END, 'UP_1000',
           opening_at - interval '1 day', 'ACTIVE', u_owner, opening_at - interval '2 days'
    FROM part_category WHERE parent_id IS NULL;
    INSERT INTO pricing_policy (policy_type, rate, effective_from, status, set_by, set_at) VALUES
        ('DISCOUNT_THRESHOLD', 10, opening_at - interval '1 day', 'ACTIVE', u_owner, opening_at - interval '2 days'),
        ('MINIMUM_MARGIN', 8, opening_at - interval '1 day', 'ACTIVE', u_owner, opening_at - interval '2 days');
    INSERT INTO pricing_policy (policy_type, expiry_warning_days, effective_from, status, set_by, set_at)
    VALUES ('EXPIRY_WARNING', 60, opening_at - interval '1 day', 'ACTIVE', u_owner, opening_at - interval '2 days');

    -- ===== Partners =====
    INSERT INTO supplier (supplier_code, supplier_name, contact_name, phone, email, address, tax_code) VALUES
        ('NCC001', 'Công ty TNHH Phụ tùng Minh Phát', 'Nguyễn Hữu Phát', '0243 6688 001', 'kinhdoanh@minhphat.vn', 'KCN Quang Minh, Mê Linh, Hà Nội', '0101234567'),
        ('NCC002', 'Công ty CP Dầu nhớt Sao Việt', 'Trần Thị Sao', '0243 6688 002', 'sales@saoviet-oil.vn', 'Số 5 Phạm Văn Đồng, Bắc Từ Liêm, Hà Nội', '0102345678'),
        ('NCC003', 'Công ty TNHH Lốp xe Thành Công', 'Lê Văn Thành', '0243 6688 003', 'order@lopthanhcong.vn', 'KCN Phố Nối A, Hưng Yên', '0901234567'),
        ('NCC004', 'Công ty TNHH Điện Hưng Thịnh', 'Phạm Quang Hưng', '0243 6688 004', NULL, 'Số 20 Giải Phóng, Hai Bà Trưng, Hà Nội', '0103456789-001');
    sup1 := (SELECT id FROM supplier WHERE supplier_code = 'NCC001');
    sup2 := (SELECT id FROM supplier WHERE supplier_code = 'NCC002');
    sup3 := (SELECT id FROM supplier WHERE supplier_code = 'NCC003');
    sup4 := (SELECT id FROM supplier WHERE supplier_code = 'NCC004');

    INSERT INTO customer (customer_name, phone, address, tax_code, credit_status, created_by, created_at, updated_at)
    SELECT v.name, v.phone, v.address, v.tax, v.credit, v.creator, opening_at - interval '1 day', opening_at - interval '1 day'
    FROM (VALUES
        ('Tiệm sửa xe Tuấn Hưng', '0983111222', 'Số 3 Xuân Thủy, Cầu Giấy, Hà Nội', '0105556667', 'ELIGIBLE', u_bm_cg),
        ('Garage Hải Nam', '0983222333', 'Số 17 Văn Phú, Hà Đông, Hà Nội', '0105557778', 'ELIGIBLE', u_bm_hd),
        ('Cửa hàng xe máy Linh Chi', '0983333444', 'Số 102 Cầu Giấy, Hà Nội', NULL, 'ELIGIBLE', u_bm_cg),
        ('Tiệm xe Phúc Lộc', '0983444555', 'Số 9 Lê Trọng Tấn, Hà Đông, Hà Nội', NULL, 'REVOKED', u_bm_hd),
        ('Anh Nguyễn Văn An', '0983555666', 'Mai Dịch, Cầu Giấy, Hà Nội', NULL, 'NOT_GRANTED', u_cg1),
        ('Chị Trần Thị Bích', '0983666777', 'Mỗ Lao, Hà Đông, Hà Nội', NULL, 'NOT_GRANTED', u_hd1),
        ('Anh Lê Hoàng Dũng', '0983777888', NULL, NULL, 'NOT_GRANTED', u_cg2),
        ('Công ty TNHH Giao hàng Nhanh Việt', '0243 9988 776', 'Số 1 Duy Tân, Cầu Giấy, Hà Nội', '0108889990', 'NOT_GRANTED', u_bm_cg)
    ) AS v(name, phone, address, tax, credit, creator);
    c_tuan := (SELECT id FROM customer WHERE customer_name = 'Tiệm sửa xe Tuấn Hưng');
    c_hai  := (SELECT id FROM customer WHERE customer_name = 'Garage Hải Nam');
    c_linh := (SELECT id FROM customer WHERE customer_name = 'Cửa hàng xe máy Linh Chi');
    c_phuc := (SELECT id FROM customer WHERE customer_name = 'Tiệm xe Phúc Lộc');
    c_an   := (SELECT id FROM customer WHERE customer_name = 'Anh Nguyễn Văn An');

    -- Credit history lives in audit_log (CREDIT_GRANTED / CREDIT_REVOKED), as CustomerService reads it.
    INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason) VALUES
        ('CUSTOMER', c_tuan, 'CREDIT_GRANTED', u_owner, opening_at, 'Khách sỉ lâu năm, thanh toán đúng hạn'),
        ('CUSTOMER', c_hai,  'CREDIT_GRANTED', u_owner, opening_at, 'Garage đối tác'),
        ('CUSTOMER', c_linh, 'CREDIT_GRANTED', u_owner, opening_at, 'Đại lý cấp 2'),
        ('CUSTOMER', c_phuc, 'CREDIT_GRANTED', u_owner, opening_at, 'Khách sỉ'),
        ('CUSTOMER', c_phuc, 'CREDIT_REVOKED', u_owner, opening_at + interval '10 days', 'Nợ quá hạn 60 ngày');

    -- ===== Inventory periods: previous month CLOSED, current month OPEN =====
    INSERT INTO inventory_period (period_month, from_date, to_date, status, closed_by, closed_at)
    VALUES (prev_month, prev_month, cur_month - 1, 'CLOSED', u_owner, pg_temp.ts(0.02))
    RETURNING id INTO p_prev;
    INSERT INTO inventory_period (period_month, from_date, to_date, previous_period_id, status)
    VALUES (cur_month, cur_month, (cur_month + interval '1 month')::date - 1, p_prev, 'OPEN')
    RETURNING id INTO p_cur;

    -- ===== Opening stock: one completed OPENING count per store, one lot per product =====
    FOR st IN SELECT * FROM (VALUES (s_central, u_bm_kho, u_kho1, 'c'), (s_cg, u_bm_cg, u_cg1, 'cg'),
                                    (s_hd, u_bm_hd, u_hd1, 'hd')) AS t(store_id, bm, staff, k) LOOP
        INSERT INTO stock_count (store_id, count_type, scope_type, scope_note, status, created_by, counting_started_at,
                                 confirmed_by, confirmed_at, approved_by, approved_at, created_at)
        VALUES (st.store_id, 'OPENING', 'WHOLE', 'Kiểm kê đầu kỳ khi đưa hệ thống vào sử dụng', 'COMPLETED', st.bm,
                opening_at, st.bm, opening_at + interval '4 hours', u_owner, opening_at + interval '6 hours',
                opening_at - interval '1 hour')
        RETURNING id INTO sc_cycle;

        INSERT INTO lot (product_id, source_type, stock_count_id, store_id, received_date, purchase_unit_price,
                         manufacturer_lot_no, manufacture_date, expiry_date)
        SELECT pg_temp.pid(d.sku), 'OPENING', sc_cycle, st.store_id, opening_at::date, d.cost,
               CASE WHEN d.expiry_months IS NOT NULL THEN 'LOT-' || to_char(opening_at, 'YYMM') || '-' || left(d.sku, 4) END,
               CASE WHEN d.expiry_months IS NOT NULL THEN (opening_at - interval '3 months')::date END,
               CASE WHEN d.expiry_months IS NOT NULL THEN (opening_at + make_interval(months => d.expiry_months))::date END
        FROM demo_product d
        WHERE CASE st.k WHEN 'c' THEN d.qty_c WHEN 'cg' THEN d.qty_cg ELSE d.qty_hd END IS NOT NULL;

        INSERT INTO stock_count_line (stock_count_id, product_id, lot_id, expiry_date, book_qty, counted_qty)
        SELECT sc_cycle, l.product_id, l.id, l.expiry_date, 0,
               CASE st.k WHEN 'c' THEN d.qty_c WHEN 'cg' THEN d.qty_cg ELSE d.qty_hd END
        FROM lot l JOIN product p ON p.id = l.product_id JOIN demo_product d ON d.sku = p.sku
        WHERE l.stock_count_id = sc_cycle;

        PERFORM pg_temp.mv(st.store_id, scl.lot_id, 'AVAILABLE', scl.counted_qty, 'OPENING_BALANCE', st.bm,
                           opening_at + interval '6 hours', NULL, NULL, NULL, sc_cycle, 'Tồn đầu kỳ')
        FROM stock_count_line scl WHERE scl.stock_count_id = sc_cycle;
    END LOOP;

    -- ===== Supplier purchases into the central store (current month) =====
    -- GRR1: fully received, confirmed, no discrepancy.
    INSERT INTO goods_receipt_request (supplier_id, store_id, expected_arrival, note, status, created_by, created_at, updated_at)
    VALUES (sup2, s_central, pg_temp.ts(0.15)::date, 'Nhập nhớt định kỳ', 'FULLY_RECEIVED', u_owner, pg_temp.ts(0.1), pg_temp.ts(0.2))
    RETURNING id INTO grr1;
    INSERT INTO goods_receipt_request_line (goods_receipt_request_id, product_id, ordered_qty, purchase_price) VALUES
        (grr1, pg_temp.pid('NHOT-MOTUL-08'), 48, 97000),
        (grr1, pg_temp.pid('NHOT-CASTROL-1L'), 36, 117000),
        (grr1, pg_temp.pid('NHOT-HONDA-HS'), 60, 22000);
    INSERT INTO goods_receipt (receipt_type, goods_receipt_request_id, delivery_document_no, arrived_at, status,
                               recorded_by, submitted_at, confirmed_by, confirmed_at)
    VALUES ('SUPPLIER_DELIVERY', grr1, 'PGH-SV-0001', pg_temp.ts(0.15), 'CONFIRMED', u_kho1, pg_temp.ts(0.16),
            u_bm_kho, pg_temp.ts(0.17))
    RETURNING id INTO gr1;
    INSERT INTO goods_receipt_line (goods_receipt_id, product_id, document_qty, delivered_qty, passed_qty, unit_price,
                                    manufacturer_lot_no, manufacture_date, expiry_date)
    SELECT gr1, l.product_id, l.ordered_qty, l.ordered_qty, l.ordered_qty, l.purchase_price,
           'SV-' || to_char(pg_temp.ts(0.15), 'YYMMDD'), (pg_temp.ts(0.15) - interval '2 months')::date,
           (pg_temp.ts(0.15) + interval '3 years')::date
    FROM goods_receipt_request_line l WHERE l.goods_receipt_request_id = grr1;

    -- GRR2: partly received; 12 on the delivery note, 10 arrived, 2 of them damaged, so a discrepancy case is open.
    INSERT INTO goods_receipt_request (supplier_id, store_id, expected_arrival, status, created_by, created_at, updated_at)
    VALUES (sup1, s_central, pg_temp.ts(0.4)::date, 'RECEIVING', u_owner, pg_temp.ts(0.3), pg_temp.ts(0.45))
    RETURNING id INTO grr2;
    INSERT INTO goods_receipt_request_line (goods_receipt_request_id, product_id, ordered_qty, purchase_price) VALUES
        (grr2, pg_temp.pid('MPH-VISION'), 30, 78000),
        (grr2, pg_temp.pid('CUROA-VISION'), 12, 190000);
    INSERT INTO goods_receipt (receipt_type, goods_receipt_request_id, delivery_document_no, arrived_at, status,
                               recorded_by, submitted_at, confirmed_by, confirmed_at)
    VALUES ('SUPPLIER_DELIVERY', grr2, 'PGH-MP-0107', pg_temp.ts(0.4), 'CONFIRMED', u_kho2, pg_temp.ts(0.41),
            u_bm_kho, pg_temp.ts(0.42))
    RETURNING id INTO gr2;
    INSERT INTO goods_receipt_line (goods_receipt_id, product_id, document_qty, delivered_qty, passed_qty, damaged_qty, unit_price)
    VALUES (gr2, pg_temp.pid('MPH-VISION'), 30, 30, 30, 0, 78000),
           (gr2, pg_temp.pid('CUROA-VISION'), 12, 10, 8, 2, 190000);

    -- Lots and movements for both confirmed supplier receipts (passed goods AVAILABLE, damaged goods DAMAGED).
    FOR grl IN SELECT l.id FROM goods_receipt_line l WHERE l.goods_receipt_id IN (gr1, gr2) ORDER BY l.id LOOP
        INSERT INTO lot (product_id, source_type, goods_receipt_line_id, supplier_id, store_id, received_date,
                         purchase_unit_price, manufacturer_lot_no, manufacture_date, expiry_date)
        SELECT l.product_id, 'RECEIPT', l.id, r.supplier_id, s_central, g.arrived_at::date, l.unit_price,
               l.manufacturer_lot_no, l.manufacture_date, l.expiry_date
        FROM goods_receipt_line l JOIN goods_receipt g ON g.id = l.goods_receipt_id
        JOIN goods_receipt_request r ON r.id = g.goods_receipt_request_id
        WHERE l.id = grl
        RETURNING id INTO v_lot;
        PERFORM pg_temp.mv(s_central, v_lot, 'AVAILABLE', l.passed_qty, 'RECEIPT_CONFIRMED', g.confirmed_by,
                           g.confirmed_at, NULL, g.id, NULL, NULL, NULL)
        FROM goods_receipt_line l JOIN goods_receipt g ON g.id = l.goods_receipt_id WHERE l.id = grl AND l.passed_qty > 0;
        PERFORM pg_temp.mv(s_central, v_lot, 'DAMAGED', l.damaged_qty, 'RECEIPT_CONFIRMED', g.confirmed_by,
                           g.confirmed_at, NULL, g.id, NULL, NULL, 'Hàng móp méo khi vận chuyển')
        FROM goods_receipt_line l JOIN goods_receipt g ON g.id = l.goods_receipt_id WHERE l.id = grl AND l.damaged_qty > 0;
    END LOOP;

    INSERT INTO discrepancy_case (source_type, goods_receipt_id, product_id, short_qty, damaged_qty, return_required_qty,
                                  replacement_required_qty, status, opened_at)
    VALUES ('SUPPLIER', gr2, pg_temp.pid('CUROA-VISION'), 2, 2, 2, 4, 'OPEN', pg_temp.ts(0.42))
    RETURNING id INTO dc1;

    -- GRR3: goods just arrived and wait for inspection (no stock yet).
    INSERT INTO goods_receipt_request (supplier_id, store_id, expected_arrival, status, created_by, created_at, updated_at)
    VALUES (sup3, s_central, pg_temp.ts(0.95)::date, 'RECEIVING', u_owner, pg_temp.ts(0.6), pg_temp.ts(0.95))
    RETURNING id INTO grr3;
    INSERT INTO goods_receipt_request_line (goods_receipt_request_id, product_id, ordered_qty, purchase_price) VALUES
        (grr3, pg_temp.pid('LOP-IRC-80'), 20, 285000),
        (grr3, pg_temp.pid('SAM-CS-17'), 40, 56000);
    INSERT INTO goods_receipt (receipt_type, goods_receipt_request_id, delivery_document_no, arrived_at, status)
    VALUES ('SUPPLIER_DELIVERY', grr3, 'PGH-TC-2231', pg_temp.ts(0.95), 'AWAITING_INSPECTION')
    RETURNING id INTO gr3;

    -- GRR4: ordered, not delivered yet.
    INSERT INTO goods_receipt_request (supplier_id, store_id, expected_arrival, note, status, created_by, created_at, updated_at)
    VALUES (sup4, s_central, (localtimestamp + interval '5 days')::date, 'Đặt ắc quy cho mùa mưa', 'AWAITING_DELIVERY',
            u_owner, pg_temp.ts(0.9), pg_temp.ts(0.9))
    RETURNING id INTO grr4;
    INSERT INTO goods_receipt_request_line (goods_receipt_request_id, product_id, ordered_qty, purchase_price) VALUES
        (grr4, pg_temp.pid('AQ-GS-5'), 20, 295000),
        (grr4, pg_temp.pid('AQ-GS-7'), 10, 425000);

    -- ===== Transfers from the central store =====
    -- TR1: replenishment for Cầu Giấy, dispatched, received and closed.
    INSERT INTO replenishment_request (requesting_store_id, status, note, created_by, created_at, closed_at)
    VALUES (s_cg, 'CLOSED', 'Bổ sung nhớt và bugi', u_bm_cg, pg_temp.ts(0.2), pg_temp.ts(0.3))
    RETURNING id INTO rr1;
    INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty) VALUES
        (rr1, pg_temp.pid('NHOT-MOTUL-08'), 20), (rr1, pg_temp.pid('BUG-NGK-CPR9'), 10);
    INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, status, created_by, created_at,
                                dispatched_by, dispatched_at, closed_by, closed_at)
    VALUES (rr1, s_central, s_cg, 'CLOSED', u_bm_kho, pg_temp.ts(0.22), u_bm_kho, pg_temp.ts(0.24), u_bm_cg, pg_temp.ts(0.3))
    RETURNING id INTO tr1;
    INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty, prepared_qty, dispatched_qty) VALUES
        (tr1, pg_temp.pid('NHOT-MOTUL-08'), 20, 20, 20), (tr1, pg_temp.pid('BUG-NGK-CPR9'), 10, 10, 10);
    INSERT INTO goods_receipt (receipt_type, transfer_order_id, arrived_at, status, recorded_by, submitted_at,
                               confirmed_by, confirmed_at)
    VALUES ('TRANSFER_IN', tr1, pg_temp.ts(0.27), 'CONFIRMED', u_cg1, pg_temp.ts(0.28), u_bm_cg, pg_temp.ts(0.3))
    RETURNING id INTO gr_tr;
    INSERT INTO goods_receipt_line (goods_receipt_id, product_id, document_qty, delivered_qty, passed_qty)
    SELECT gr_tr, product_id, dispatched_qty, dispatched_qty, dispatched_qty FROM transfer_order_line WHERE transfer_order_id = tr1;
    FOR st IN SELECT tl.product_id, tl.dispatched_qty AS qty, pg_temp.opening_lot(s_central, p.sku) AS lot
              FROM transfer_order_line tl JOIN product p ON p.id = tl.product_id WHERE tl.transfer_order_id = tr1 LOOP
        PERFORM pg_temp.mv(s_central, st.lot, 'AVAILABLE', -st.qty, 'TRANSFER_DISPATCH', u_bm_kho, pg_temp.ts(0.24), NULL, NULL, tr1, NULL, NULL);
        PERFORM pg_temp.mv(s_cg, st.lot, 'IN_TRANSIT', st.qty, 'TRANSFER_DISPATCH', u_bm_kho, pg_temp.ts(0.24), NULL, NULL, tr1, NULL, NULL);
        PERFORM pg_temp.mv(s_cg, st.lot, 'IN_TRANSIT', -st.qty, 'TRANSFER_RECEIPT', u_bm_cg, pg_temp.ts(0.3), NULL, gr_tr, NULL, NULL, NULL);
        PERFORM pg_temp.mv(s_cg, st.lot, 'AVAILABLE', st.qty, 'TRANSFER_RECEIPT', u_bm_cg, pg_temp.ts(0.3), NULL, gr_tr, NULL, NULL, NULL);
    END LOOP;

    -- TR2: on the way to Hà Đông.
    INSERT INTO transfer_order (source_store_id, dest_store_id, status, created_by, created_at, dispatched_by, dispatched_at)
    VALUES (s_central, s_hd, 'IN_TRANSIT', u_bm_kho, pg_temp.ts(0.85), u_bm_kho, pg_temp.ts(0.9))
    RETURNING id INTO tr2;
    INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty, prepared_qty, dispatched_qty) VALUES
        (tr2, pg_temp.pid('MPH-VISION'), 8, 8, 8), (tr2, pg_temp.pid('LGI-VISION'), 5, 5, 5);
    FOR st IN SELECT tl.dispatched_qty AS qty, pg_temp.opening_lot(s_central, p.sku) AS lot
              FROM transfer_order_line tl JOIN product p ON p.id = tl.product_id WHERE tl.transfer_order_id = tr2 LOOP
        PERFORM pg_temp.mv(s_central, st.lot, 'AVAILABLE', -st.qty, 'TRANSFER_DISPATCH', u_bm_kho, pg_temp.ts(0.9), NULL, NULL, tr2, NULL, NULL);
        PERFORM pg_temp.mv(s_hd, st.lot, 'IN_TRANSIT', st.qty, 'TRANSFER_DISPATCH', u_bm_kho, pg_temp.ts(0.9), NULL, NULL, tr2, NULL, NULL);
    END LOOP;

    -- RR2 + TR3: Hà Đông asked for the CDI it does not stock; transfer waits for dispatch preparation.
    INSERT INTO replenishment_request (requesting_store_id, status, note, created_by, created_at)
    VALUES (s_hd, 'IN_PROGRESS', 'Khách đặt IC Wave', u_bm_hd, pg_temp.ts(0.8))
    RETURNING id INTO rr2;
    INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty)
    VALUES (rr2, pg_temp.pid('CDI-WAVE'), 3);
    INSERT INTO transfer_order (replenishment_request_id, source_store_id, dest_store_id, status, created_by, created_at)
    VALUES (rr2, s_central, s_hd, 'AWAITING_DISPATCH', u_bm_kho, pg_temp.ts(0.85))
    RETURNING id INTO tr3;
    INSERT INTO transfer_order_line (transfer_order_id, product_id, planned_qty) VALUES (tr3, pg_temp.pid('CDI-WAVE'), 3);

    -- RR3: pending, waiting for the central store to coordinate.
    INSERT INTO replenishment_request (requesting_store_id, status, note, created_by, created_at)
    VALUES (s_cg, 'AWAITING_COORDINATION', 'Sắp hết lốp và dây curoa', u_bm_cg, pg_temp.ts(0.97))
    RETURNING id INTO rr3;
    INSERT INTO replenishment_request_line (replenishment_request_id, product_id, requested_qty, to_purchase_qty) VALUES
        (rr3, pg_temp.pid('LOP-MICH-90'), 6, 0), (rr3, pg_temp.pid('CUROA-AB'), 5, 0), (rr3, pg_temp.pid('NX-EXC'), 4, 2);

    -- ===== Sales =====
    -- SO1: walk-in retail at Cầu Giấy, paid cash.
    INSERT INTO sales_order (store_id, status, completion_type, price_type, customer_name_snapshot, created_by, created_at,
                             confirmed_by, confirmed_at, completed_by, completed_at, updated_at)
    VALUES (s_cg, 'COMPLETED', 'PAID', 'RETAIL', 'Khách lẻ', u_cg1, pg_temp.ts(0.5), u_cg1, pg_temp.ts(0.51),
            u_cg1, pg_temp.ts(0.52), pg_temp.ts(0.52))
    RETURNING id INTO so1;
    total := pg_temp.sell(so1, s_cg, 1, 'NHOT-MOTUL-08', 1, 130000, u_cg1, pg_temp.ts(0.52))
           + pg_temp.sell(so1, s_cg, 2, 'BUG-NGK-CPR9', 1, 70000, u_cg1, pg_temp.ts(0.52));
    INSERT INTO payment (payment_type, sales_order_id, amount, tendered_amount, payment_method, status, recorded_by,
                         recorded_at, verified_by, verified_at)
    VALUES ('ORDER_PAYMENT', so1, total, 500000, 'CASH', 'VERIFIED', u_cg1, pg_temp.ts(0.52), u_bm_cg, pg_temp.ts(0.6));

    -- SO2: wholesale to a known customer at Hà Đông, bank transfer still to be verified.
    INSERT INTO sales_order (store_id, status, completion_type, price_type, customer_id, customer_name_snapshot,
                             customer_phone_snapshot, customer_address_snapshot, created_by, created_at,
                             confirmed_by, confirmed_at, completed_by, completed_at, updated_at)
    SELECT s_hd, 'COMPLETED', 'PAID', 'WHOLESALE', id, customer_name, phone, address, u_hd1, pg_temp.ts(0.7),
           u_bm_hd, pg_temp.ts(0.71), u_hd1, pg_temp.ts(0.72), pg_temp.ts(0.72)
    FROM customer WHERE id = c_hai
    RETURNING id INTO so2;
    total := pg_temp.sell(so2, s_hd, 1, 'MPH-AB', 4, 115000, u_hd1, pg_temp.ts(0.72))
           + pg_temp.sell(so2, s_hd, 2, 'NHOT-CASTROL-1L', 6, 140000, u_hd1, pg_temp.ts(0.72))
           + pg_temp.sell(so2, s_hd, 3, 'LGI-AB125', 2, 82000, u_hd1, pg_temp.ts(0.72));
    INSERT INTO payment (payment_type, sales_order_id, amount, payment_method, transaction_reference, status, recorded_by, recorded_at)
    VALUES ('ORDER_PAYMENT', so2, total, 'BANK_TRANSFER', 'FT' || to_char(pg_temp.ts(0.72), 'YYMMDD') || '0042', 'UNVERIFIED',
            u_hd1, pg_temp.ts(0.72));

    -- SO3: credit sale to Tuấn Hưng at Cầu Giấy; 1,000,000 paid up front, the rest becomes a receivable,
    -- later partly paid by a debt payment allocated to it.
    INSERT INTO sales_order (store_id, status, completion_type, price_type, customer_id, customer_name_snapshot,
                             customer_phone_snapshot, customer_address_snapshot, created_by, created_at,
                             confirmed_by, confirmed_at, completed_by, completed_at, updated_at)
    SELECT s_cg, 'COMPLETED', 'CREDIT', 'WHOLESALE', id, customer_name, phone, address, u_cg2, pg_temp.ts(0.55),
           u_bm_cg, pg_temp.ts(0.56), u_cg2, pg_temp.ts(0.58), pg_temp.ts(0.58)
    FROM customer WHERE id = c_tuan
    RETURNING id INTO so3;
    total := pg_temp.sell(so3, s_cg, 1, 'NX-WAVE', 3, 310000, u_cg2, pg_temp.ts(0.58))
           + pg_temp.sell(so3, s_cg, 2, 'PIS-WAVE-STD', 2, 285000, u_cg2, pg_temp.ts(0.58))
           + pg_temp.sell(so3, s_cg, 3, 'SAM-CS-17', 5, 75000, u_cg2, pg_temp.ts(0.58));
    UPDATE sales_order SET credit_decision = 'APPROVED', approved_credit_amount = total - 1000000,
                           credit_decided_by = u_owner, credit_decided_at = pg_temp.ts(0.57)
    WHERE id = so3;
    INSERT INTO payment (payment_type, sales_order_id, amount, tendered_amount, payment_method, status, recorded_by,
                         recorded_at, verified_by, verified_at)
    VALUES ('ORDER_PAYMENT', so3, 1000000, 1000000, 'CASH', 'VERIFIED', u_cg2, pg_temp.ts(0.58), u_bm_cg, pg_temp.ts(0.6));
    INSERT INTO receivable (origin_type, customer_id, store_id, sales_order_id, original_amount, note, recognized_by,
                            recognized_at, due_date)
    VALUES ('CREDIT_SALE', c_tuan, s_cg, so3, total - 1000000, 'Công nợ 30 ngày', u_cg2, pg_temp.ts(0.58),
            (pg_temp.ts(0.58) + interval '30 days')::date)
    RETURNING id INTO rcv;
    INSERT INTO payment (payment_type, amount, payment_method, transaction_reference, status, recorded_by, recorded_at,
                         verified_by, verified_at)
    VALUES ('DEBT_PAYMENT', 500000, 'BANK_TRANSFER', 'FT' || to_char(pg_temp.ts(0.8), 'YYMMDD') || '0107', 'VERIFIED',
            u_cg1, pg_temp.ts(0.8), u_bm_cg, pg_temp.ts(0.82))
    RETURNING id INTO pay;
    INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (pay, rcv, 500000);

    -- Opening debts carried over from before the system: Hải Nam (fully paid) and Linh Chi (open, overdue).
    INSERT INTO receivable (origin_type, customer_id, store_id, original_amount, note, recognized_by, recognized_at,
                            settled_at, due_date)
    VALUES ('OPENING_BALANCE', c_hai, s_hd, 2400000, 'Nợ cũ chuyển sang hệ thống', u_bm_hd, opening_at,
            pg_temp.ts(0.35), (opening_at + interval '30 days')::date)
    RETURNING id INTO rcv2;
    INSERT INTO payment (payment_type, amount, tendered_amount, payment_method, status, recorded_by, recorded_at, verified_by, verified_at)
    VALUES ('DEBT_PAYMENT', 2400000, 2400000, 'CASH', 'VERIFIED', u_hd1, pg_temp.ts(0.35), u_bm_hd, pg_temp.ts(0.36))
    RETURNING id INTO pay;
    INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount) VALUES (pay, rcv2, 2400000);
    INSERT INTO receivable (origin_type, customer_id, store_id, original_amount, note, recognized_by, recognized_at, due_date)
    VALUES ('OPENING_BALANCE', c_linh, s_cg, 1850000, 'Nợ cũ chuyển sang hệ thống', u_bm_cg, opening_at,
            (opening_at + interval '15 days')::date);

    -- SO4: just created at Hà Đông, waiting to be picked.
    INSERT INTO sales_order (store_id, status, price_type, customer_id, customer_name_snapshot, customer_phone_snapshot,
                             created_by, created_at, updated_at)
    SELECT s_hd, 'AWAITING_PICKING', 'RETAIL', id, customer_name, phone, u_hd2, pg_temp.ts(0.98), pg_temp.ts(0.98)
    FROM customer WHERE customer_name = 'Chị Trần Thị Bích'
    RETURNING id INTO so4;
    INSERT INTO sales_order_line (sales_order_id, line_no, product_id, requested_qty, reference_price, actual_unit_price)
    VALUES (so4, 1, pg_temp.pid('LGI-VISION'), 1, 85000, 85000), (so4, 2, pg_temp.pid('MPH-VISION'), 1, 120000, 120000);

    -- SO5: cancelled before picking.
    INSERT INTO sales_order (store_id, status, price_type, customer_id, customer_name_snapshot, created_by, created_at,
                             cancelled_by, cancelled_at, cancellation_reason, updated_at)
    VALUES (s_cg, 'CANCELLED', 'RETAIL', c_an, 'Anh Nguyễn Văn An', u_cg2, pg_temp.ts(0.6), u_cg2, pg_temp.ts(0.61),
            'Khách đổi ý không mua', pg_temp.ts(0.61))
    RETURNING id INTO so5;
    INSERT INTO sales_order_line (sales_order_id, line_no, product_id, requested_qty, reference_price, actual_unit_price)
    VALUES (so5, 1, pg_temp.pid('LOP-MICH-90'), 1, 720000, 720000);

    -- ===== Counting =====
    -- Cycle count in progress at Hà Đông (oil shelf).
    INSERT INTO stock_count (store_id, inventory_period_id, count_type, scope_type, scope_note, status, created_by,
                             counting_started_at, created_at)
    VALUES (s_hd, p_cur, 'CYCLE', 'AREA', 'Kệ dầu nhớt', 'COUNTING', u_bm_hd, pg_temp.ts(0.96), pg_temp.ts(0.95))
    RETURNING id INTO sc_cycle;
    -- Request from Cầu Giấy for a product count, waiting for the Owner's decision.
    INSERT INTO stock_count_request (store_id, scope_type, reason, status, requested_by, requested_at)
    VALUES (s_cg, 'PRODUCTS', 'Lệch số lượng bugi khi bán', 'PENDING', u_bm_cg, pg_temp.ts(0.97))
    RETURNING id INTO scr1;
    INSERT INTO stock_count_request_product (stock_count_request_id, product_id)
    VALUES (scr1, pg_temp.pid('BUG-NGK-CPR8')), (scr1, pg_temp.pid('BUG-NGK-CPR9'));

    -- ===== Adjustment waiting for approval: one broken bulb at Cầu Giấy =====
    INSERT INTO adjustment_request (adjustment_type, store_id, reason, status, requested_by, created_at, submitted_at)
    VALUES ('STOCK_LOSS', s_cg, 'Vỡ 1 bóng đèn pha khi sắp xếp kệ', 'PENDING', u_bm_cg, pg_temp.ts(0.9), pg_temp.ts(0.9))
    RETURNING id INTO adj1;
    INSERT INTO adjustment_stock_line (adjustment_request_id, store_id, product_id, stock_status, quantity_change, lot_id)
    VALUES (adj1, s_cg, pg_temp.pid('BD-HS1-PH'), 'AVAILABLE', -1, pg_temp.opening_lot(s_cg, 'BD-HS1-PH'));

    -- ===== Derived balances: lot_balance and stock_balance are sums of the movements =====
    INSERT INTO lot_balance (lot_id, store_id, stock_status, quantity, updated_at)
    SELECT lot_id, store_id, stock_status, sum(quantity_change), max(occurred_at)
    FROM stock_movement GROUP BY lot_id, store_id, stock_status HAVING sum(quantity_change) <> 0;
    UPDATE lot SET status = 'DEPLETED' WHERE NOT EXISTS (SELECT 1 FROM lot_balance b WHERE b.lot_id = lot.id);

    INSERT INTO stock_balance (store_id, product_id, available_qty, damaged_qty, in_transit_qty, storage_area, bin_location,
                               low_stock_threshold, updated_at)
    SELECT m.store_id, m.product_id,
           coalesce(sum(m.quantity_change) FILTER (WHERE m.stock_status = 'AVAILABLE'), 0),
           coalesce(sum(m.quantity_change) FILTER (WHERE m.stock_status = 'DAMAGED'), 0),
           coalesce(sum(m.quantity_change) FILTER (WHERE m.stock_status = 'IN_TRANSIT'), 0),
           CASE WHEN m.store_id = s_central THEN 'Khu ' || chr(64 + (coalesce(c.parent_id, c.id) % 5 + 1)::int) ELSE 'Quầy' END,
           CASE WHEN m.store_id = s_central THEN 'K' || lpad((m.product_id % 30 + 1)::text, 2, '0') END,
           CASE WHEN m.store_id = s_central THEN 10 ELSE 5 END,
           max(m.occurred_at)
    FROM stock_movement m JOIN product p ON p.id = m.product_id JOIN part_category c ON c.id = p.category_id
    GROUP BY m.store_id, m.product_id, c.parent_id, c.id;

    -- Weighted average purchase cost of everything received so far.
    UPDATE product p SET moving_avg_cost = x.avg_cost
    FROM (SELECT product_id, round(sum(value)::numeric / sum(quantity_change), 4) AS avg_cost
          FROM stock_movement WHERE movement_type IN ('OPENING_BALANCE', 'RECEIPT_CONFIRMED')
          GROUP BY product_id) x
    WHERE x.product_id = p.id;

    -- Closed previous period: snapshot of its movements (only opening balances happened in it).
    INSERT INTO period_stock_balance (inventory_period_id, store_id, product_id, opening_qty, opening_value, in_qty, in_value,
                                      out_qty, out_value, closing_qty, closing_value, period_avg_unit_cost)
    SELECT p_prev, store_id, product_id, 0, 0,
           sum(quantity_change) FILTER (WHERE quantity_change > 0), sum(value) FILTER (WHERE quantity_change > 0),
           coalesce(-sum(quantity_change) FILTER (WHERE quantity_change < 0), 0),
           coalesce(-sum(value) FILTER (WHERE quantity_change < 0), 0),
           sum(quantity_change), sum(value), round(sum(value)::numeric / nullif(sum(quantity_change), 0), 4)
    FROM stock_movement WHERE inventory_period_id = p_prev
    GROUP BY store_id, product_id;

    INSERT INTO stock_count_line (stock_count_id, product_id, lot_id, expiry_date, book_qty)
    SELECT sc_cycle, l.product_id, b.lot_id, l.expiry_date, b.quantity
    FROM lot_balance b JOIN lot l ON l.id = b.lot_id JOIN product p ON p.id = l.product_id
    JOIN part_category c ON c.id = p.category_id
    WHERE b.store_id = s_hd AND b.stock_status = 'AVAILABLE' AND c.category_name = 'Dầu nhớt';

    -- ===== Warehouse tasks =====
    INSERT INTO warehouse_task (task_type, goods_receipt_id, assigned_to, assigned_by, assigned_at, status, result_submitted_at) VALUES
        ('INSPECTION', gr1, u_kho1, u_bm_kho, pg_temp.ts(0.15), 'DONE', pg_temp.ts(0.16)),
        ('INSPECTION', gr2, u_kho2, u_bm_kho, pg_temp.ts(0.4), 'DONE', pg_temp.ts(0.41)),
        ('INSPECTION', gr3, u_kho1, u_bm_kho, pg_temp.ts(0.96), 'ASSIGNED', NULL);
    INSERT INTO warehouse_task (task_type, transfer_order_id, assigned_to, assigned_by, assigned_at, status, result_submitted_at) VALUES
        ('DISPATCH_PREPARATION', tr1, u_kho2, u_bm_kho, pg_temp.ts(0.22), 'DONE', pg_temp.ts(0.23)),
        ('DISPATCH_PREPARATION', tr3, u_kho2, u_bm_kho, pg_temp.ts(0.86), 'ASSIGNED', NULL);
    INSERT INTO warehouse_task (task_type, sales_order_id, assigned_to, assigned_by, assigned_at, status) VALUES
        ('PICKING', so4, u_hd1, u_bm_hd, pg_temp.ts(0.98), 'ASSIGNED');
    INSERT INTO warehouse_task (task_type, stock_count_id, assigned_to, assigned_by, assigned_at, status) VALUES
        ('COUNTING', sc_cycle, u_hd2, u_bm_hd, pg_temp.ts(0.96), 'ASSIGNED');

    -- ===== Notifications =====
    INSERT INTO notification (recipient_id, notification_type, message, ref_type, ref_id, is_read, created_at, read_at) VALUES
        (u_owner, 'REPLENISHMENT_REQUESTED', 'Nga Minh Cầu Giấy gửi yêu cầu bổ sung hàng.', 'REPLENISHMENT_REQUEST', rr3, FALSE, pg_temp.ts(0.97), NULL),
        (u_bm_kho, 'REPLENISHMENT_REQUESTED', 'Nga Minh Cầu Giấy gửi yêu cầu bổ sung hàng.', 'REPLENISHMENT_REQUEST', rr3, FALSE, pg_temp.ts(0.97), NULL),
        (u_owner, 'STOCK_COUNT_REQUESTED', 'Nga Minh Cầu Giấy đề nghị kiểm kê bugi.', 'STOCK_COUNT_REQUEST', scr1, FALSE, pg_temp.ts(0.97), NULL),
        (u_owner, 'ADJUSTMENT_SUBMITTED', 'Có đề nghị điều chỉnh tồn kho chờ duyệt.', 'ADJUSTMENT_REQUEST', adj1, FALSE, pg_temp.ts(0.9), NULL),
        (u_owner, 'DISCREPANCY_OPENED', 'Phiếu nhập PGH-MP-0107 thiếu 2 và hỏng 2 dây curoa Vision.', 'DISCREPANCY_CASE', dc1, TRUE, pg_temp.ts(0.42), pg_temp.ts(0.45)),
        (u_bm_hd, 'PAYMENT_TO_VERIFY', 'Chuyển khoản của Garage Hải Nam chờ xác nhận.', 'SALES_ORDER', so2, FALSE, pg_temp.ts(0.72), NULL),
        (u_kho1, 'TASK_ASSIGNED', 'Bạn được giao kiểm hàng phiếu PGH-TC-2231.', 'GOODS_RECEIPT', gr3, FALSE, pg_temp.ts(0.96), NULL),
        (u_kho2, 'TASK_ASSIGNED', 'Bạn được giao chuẩn bị hàng điều chuyển đi Hà Đông.', 'TRANSFER_ORDER', tr3, FALSE, pg_temp.ts(0.86), NULL),
        (u_hd1, 'TASK_ASSIGNED', 'Bạn được giao soạn hàng cho đơn của chị Bích.', 'SALES_ORDER', so4, FALSE, pg_temp.ts(0.98), NULL),
        (u_hd2, 'TASK_ASSIGNED', 'Bạn được giao kiểm kê kệ dầu nhớt.', 'STOCK_COUNT', sc_cycle, FALSE, pg_temp.ts(0.96), NULL),
        (u_bm_hd, 'TRANSFER_DISPATCHED', 'Hàng điều chuyển từ Kho tổng đang trên đường tới.', 'TRANSFER_ORDER', tr2, FALSE, pg_temp.ts(0.9), NULL);

    RAISE NOTICE 'Demo data loaded: 3 stores, % products, % stock movements.',
        (SELECT count(*) FROM product), (SELECT count(*) FROM stock_movement);
END
$seed$;

COMMIT;
