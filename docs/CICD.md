# CI/CD cho MotoFlow

Backend lên Lightsail. Frontend do Cloudflare Pages xuất bản, GitHub chỉ chạy test. Xem `MotoFlow_FE/docs/CICD.md`.

Toolchain backend: **GitHub Actions → Amazon ECR → Lightsail (SSH)**, xác thực bằng **OIDC**. Không lưu access key AWS trên GitHub.

---

## 1. Tổng quan

```
PR vào main     ──► build-test
Merge main      ──► build-test ──► build-push-image ──► deploy-production ──► smoke test
Tag v1.0.0      ──► release: deploy đúng image của commit đó
```

Máy local chạy profile `dev` (xem README). Pipeline không deploy một máy dev.

Trang và API ở hai host cùng domain gốc, ví dụ `app.example.com` (Pages) và `api.example.com` (Lightsail). Cookie refresh là `SameSite=Lax`.

Một máy Lightsail chạy Caddy, backend và PostgreSQL 17. Caddy chỉ chuyển vào backend.

| Nguyên tắc | Thực hiện ở đâu |
|---|---|
| Build một lần, tag phát hành dùng lại image đó | `release.yml` tìm image theo commit SHA, không build lại |
| Tag image là commit SHA, không dùng `latest` | ECR bật tag immutability |
| Không access key dài hạn | OIDC, hai IAM role trong `deploy/aws/` |
| Bản lỗi không làm sập API | `deploy-service` đợi `/actuator/health` trả `UP`, không thì trả image cũ |

---

## 2. Các file

| File | Vai trò |
|---|---|
| `.github/workflows/ci-cd.yml` | PR: build-test. Merge `main`: build image, deploy Lightsail, smoke test |
| `.github/workflows/release.yml` | Tag `v*`: deploy production bằng image đã có |
| `.github/scripts/lightsail-deploy.sh` | Tìm máy Lightsail theo tag, SSH, gọi `deploy-service` |
| `.github/scripts/smoke-test.sh` | `POST /api/v1/auth/login` với user không tồn tại phải trả `401 MSG09` |
| `deploy/server/setup.sh` | Dựng Docker, Caddy, PostgreSQL, `.env` trên máy |
| `deploy/aws/*.json` | Trust policy và quyền của hai role |

---

## 3. Thiết lập một lần

Region mặc định là **ap-southeast-1**. Lệnh `aws` chạy trong CloudShell.

### 3.1 ECR

```bash
aws ecr create-repository --repository-name motoflow-backend \
  --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true
```

### 3.2 OIDC provider

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd
```

`EntityAlreadyExists` thì bỏ qua. Provider cần đủ hai thumbprint ở trên.

### 3.3 Hai IAM role

| Role | Job | Trust | Quyền |
|---|---|---|---|
| `motoflow-gha-ecr-publisher` | build-push-image | `trust-main.json` | `policy-ecr-publisher.json` |
| `motoflow-gha-deploy-prod` | deploy-production và release | `trust-release.json` | `policy-deploy-prod.json` |

`sub` trong trust có mã số repo vì GitHub bật subject cố định. Đổi tên repo thì lấy prefix mới từ API customization của repo và sửa file.

Trong CloudShell, upload file trong `deploy/aws/`, rồi:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
sed -i "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" trust-*.json policy-*.json

aws iam create-role --role-name motoflow-gha-ecr-publisher --assume-role-policy-document file://trust-main.json
aws iam put-role-policy --role-name motoflow-gha-ecr-publisher --policy-name ecr-publisher --policy-document file://policy-ecr-publisher.json

aws iam create-role --role-name motoflow-gha-deploy-prod --assume-role-policy-document file://trust-release.json
aws iam put-role-policy --role-name motoflow-gha-deploy-prod --policy-name deploy-prod --policy-document file://policy-deploy-prod.json
```

### 3.4 Máy Lightsail

Ubuntu 24.04, gói 2 GB, Singapore. Gắn Static IP. Firewall mở TCP 22, 80 và 443.

Tag của instance: `App` = `motoflow`, `Env` = `production`. Pipeline tìm theo hai tag này.

Sửa đầu `deploy/server/setup.sh`: `SITE_ADDRESS` là `api.<domain>`, `CORS_ORIGIN` là `https://app.<domain>`, `LOCAL_DB="true"`. Script dùng `dnf`. Trên Ubuntu cài Docker trước, bỏ khối cài Docker, rồi chạy phần còn lại bằng root.

Máy dùng khóa mặc định của Lightsail thì pipeline tự lấy khóa SSH tạm. Khóa riêng thì đặt secret `LIGHTSAIL_SSH_KEY` trên repo, nội dung là file `.pem`.

### 3.5 Biến GitHub của repo backend

| Biến | Tác dụng |
|---|---|
| `AWS_ACCOUNT_ID` | Bật đẩy image và deploy |
| `PROD_BASE_URL` | URL smoke test, ví dụ `https://api.<domain>` |
| `PROD_ENABLED` | `true` thì tag `v*` mới deploy |
| `AWS_REGION` | Tùy chọn, mặc định `ap-southeast-1` |

---

## 4. Dùng hằng ngày

Merge vào `main` thì khoảng vài phút sau API trên Lightsail đổi image. Tag `v*` deploy lại đúng image của commit đó, không build lại.

Rollback trên server: health check thất bại thì `deploy-service` trả image cũ. Muốn về bản cũ hơn: Actions → CI/CD → run cũ đã xanh → **Re-run failed jobs** trên `deploy-production`, hoặc Release với tag cũ.

Migration Flyway đã chạy thì còn sau rollback. Đổi schema theo expand, rồi mới xóa cột ở bản sau. Không sửa file `V*.sql` đã chạy.

---

## 5. Khi pipeline đỏ

| Triệu chứng | Cách xử lý |
|---|---|
| `build-push-image` bị skipped | Chưa có `AWS_ACCOUNT_ID` |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Trust policy chưa khớp `sub` có mã số repo, hoặc chưa có OIDC provider |
| `No running Lightsail instance tagged App=motoflow` | Thiếu tag hoặc máy đang tắt |
| `setup.sh has not been run` / không có `deploy-service` | Chạy `setup.sh` trên máy |
| Smoke test `HTTP 000` | `PROD_BASE_URL` sai, hoặc cổng 80/443 chưa mở |
| `403 Invalid CORS request` | `MOTOFLOW_CORS_ORIGINS` phải là origin của Pages |
| Release: `No image for ...` | CI/CD trên `main` chưa xanh ở commit đó |
