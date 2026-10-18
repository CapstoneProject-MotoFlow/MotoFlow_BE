# CI/CD cho MotoFlow

Tài liệu này dùng chung cho hai repo `MotoFlow_BE` và `MotoFlow_FE`. Phần AWS và server chỉ có ở đây; `MotoFlow_FE/docs/CICD.md` chỉ ghi những gì riêng của frontend.

Toolchain: **GitHub Actions → Amazon ECR → EC2 (deploy qua SSM)**, xác thực bằng **OIDC** nên GitHub không lưu access key AWS nào.

---

## 1. Tổng quan

### Pipeline (giống nhau ở hai repo)

```
PR vào main ──► build-test                       BE: ./mvnw verify | FE: npm ci, lint, test, build
Merge main  ──► build-test ──► build-push-image ──► deploy-dev ──► smoke test
                               (ECR, tag = commit SHA)   (tự động)
Tag v1.0.0  ──► release: tìm image đã build cho commit đó ──► deploy-production ──► smoke test
```

### Một server (mỗi môi trường một EC2)

```
Internet ──► proxy (Caddy, cổng 80/443, tự lấy chứng chỉ HTTPS khi có domain)
               ├── /api/*   ──► backend   (image từ MotoFlow_BE)  ──► db (PostgreSQL 17 container, hoặc RDS)
               └── còn lại  ──► frontend  (image từ MotoFlow_FE: Caddy + dist/)
```

Frontend và backend chạy **cùng một domain**, nên trình duyệt không gặp CORS, và cookie refresh (`path=/api/auth`) hoạt động bình thường.

### Nguyên tắc

| Nguyên tắc | Thực hiện ở đâu |
|---|---|
| Build một lần, dev và prod dùng **cùng một image digest** | Image được build từ đúng file JAR / `dist/` đã qua test; `release.yml` không build lại mà tìm image theo commit SHA |
| Không dùng tag `latest`, tag không ghi đè được | Tag = commit SHA, ECR bật *tag immutability*; deploy theo `@sha256:` |
| Không access key dài hạn | OIDC + 3 IAM role quyền hẹp (`deploy/aws/`) |
| Không mở SSH | Deploy qua SSM Run Command, vào máy bằng Session Manager |
| Bản lỗi không làm sập site | `deploy-service` chờ health check; nếu bản mới không khỏe thì quay về image cũ và báo đỏ |
| CI dùng được ngay, CD bật sau | Các job CD tự bỏ qua cho tới khi đặt biến `AWS_ACCOUNT_ID` |

### Khác với ebook "From Code to Cloud"

- **Production deploy bằng tag `v*`, không dùng Environment + Required reviewers.** Theo tài liệu GitHub, *required reviewers* không có cho repo private trên gói Free/Team. Người được đẩy tag `v*` (PM) chính là "cổng duyệt". Tag cũng khớp với các mốc *Software Package v1/v2/v3* trong Report 2.
- **Hai repo dùng chung một server.** FE và BE deploy độc lập; trên server có khóa `flock` để hai pipeline không deploy chồng lên nhau.
- **Dockerfile không có stage Maven/Node.** CI build và test trước, image chỉ chép kết quả vào, nên thứ đã test đúng là thứ được deploy.

---

## 2. Các file

| File | Vai trò |
|---|---|
| `.github/workflows/ci-cd.yml` | PR: build-test. Merge `main`: build-test → build-push-image → deploy-dev |
| `.github/workflows/release.yml` | Tag `v*`: deploy production. Chạy tay trên tag cũ để rollback |
| `.github/scripts/ssm-deploy.sh` | Tìm EC2 theo tag, gửi lệnh SSM, chờ và in kết quả. **Giống hệt** bản trong MotoFlow_FE |
| `.github/scripts/smoke-test.sh` | BE: `POST /api/auth/login` với user không tồn tại phải trả `401 MSG09` |
| `.github/dependabot.yml` | PR cập nhật action, Maven, base image mỗi tuần |
| `Dockerfile`, `.dockerignore` | Image runtime: `eclipse-temurin:21-jre` + JAR, chạy user không phải root |
| `deploy/server/setup.sh` | Dựng server một lần: Docker, `compose.yaml`, Caddyfile, `bin/deploy-service`, `.env` với secret ngẫu nhiên |
| `deploy/aws/*.json` | Trust policy và quyền cho 3 IAM role của GitHub Actions |

---

## 3. Thiết lập một lần

Làm theo đúng thứ tự. Region mặc định là **ap-southeast-1 (Singapore)**. Nếu đổi region, sửa ARN trong `deploy/aws/*.json` và đặt biến `AWS_REGION` ở GitHub.

Các lệnh `aws ...` bên dưới chạy trong **AWS CloudShell** (biểu tượng `>_` trên thanh trên cùng của AWS Console), nên không cần cài AWS CLI trên máy.

### 3.1 ECR: hai repository chứa image

```bash
for r in motoflow-backend motoflow-frontend; do
  aws ecr create-repository --repository-name "$r" \
    --image-tag-mutability IMMUTABLE \
    --image-scanning-configuration scanOnPush=true
done
```

### 3.2 OIDC provider cho GitHub

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

Nếu báo `EntityAlreadyExists` thì provider đã có sẵn, bỏ qua bước này.

### 3.3 Ba IAM role cho GitHub Actions

| Role | Dùng ở job | Trust policy | Quyền |
|---|---|---|---|
| `motoflow-gha-ecr-publisher` | build-push-image | `trust-main.json` (nhánh `main` của 2 repo) | `policy-ecr-publisher.json`: push image vào 2 repo ECR |
| `motoflow-gha-deploy-dev` | deploy-dev | `trust-main.json` | `policy-deploy-dev.json`: SSM chỉ tới EC2 có tag `App=motoflow`, `Env=dev` |
| `motoflow-gha-deploy-prod` | deploy-production | `trust-release.json` (chỉ tag `v*` của 2 repo) | `policy-deploy-prod.json`: như trên với `Env=production`, thêm đọc ECR |

Trong CloudShell: **Actions → Upload file**, tải lên 5 file trong `deploy/aws/`, rồi chạy:

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
sed -i "s/<ACCOUNT_ID>/$ACCOUNT_ID/g" trust-*.json policy-*.json

aws iam create-role --role-name motoflow-gha-ecr-publisher --assume-role-policy-document file://trust-main.json
aws iam put-role-policy --role-name motoflow-gha-ecr-publisher --policy-name ecr-publisher --policy-document file://policy-ecr-publisher.json

aws iam create-role --role-name motoflow-gha-deploy-dev --assume-role-policy-document file://trust-main.json
aws iam put-role-policy --role-name motoflow-gha-deploy-dev --policy-name deploy-dev --policy-document file://policy-deploy-dev.json

aws iam create-role --role-name motoflow-gha-deploy-prod --assume-role-policy-document file://trust-release.json
aws iam put-role-policy --role-name motoflow-gha-deploy-prod --policy-name deploy-prod --policy-document file://policy-deploy-prod.json
```

Trust policy so khớp trường `sub` của token GitHub **phân biệt chữ hoa, chữ thường**: `repo:CapstoneProject-MotoFlow/MotoFlow_BE:ref:refs/heads/main`. Nếu đổi tên org hoặc repo, phải sửa file JSON theo.

### 3.4 Role cho EC2

```bash
aws iam create-role --role-name motoflow-ec2 --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]
}'
aws iam attach-role-policy --role-name motoflow-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam attach-role-policy --role-name motoflow-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
aws iam create-instance-profile --instance-profile-name motoflow-ec2
aws iam add-role-to-instance-profile --instance-profile-name motoflow-ec2 --role-name motoflow-ec2
```

### 3.5 Server dev

1. **Mở `deploy/server/setup.sh`, sửa hai dòng đầu:**
   - `SITE_ADDRESS`: domain của môi trường, ví dụ `dev.motoflow.example.com`. Nếu chưa có domain, để `":80"` để thử trước bằng HTTP.
   - `LOCAL_DB`: để `"true"` để chạy PostgreSQL trong container. Chọn `"false"` nếu dùng RDS.
2. **EC2 → Launch instance:**
   - AMI: **Amazon Linux 2023, x86_64**. Không chọn ARM/Graviton (`t4g`), vì image được build cho x86_64.
   - Instance type: **t3.small** (2 GB RAM). `t3.micro` (1 GB) quá chật cho Spring Boot + PostgreSQL.
   - Key pair: *Proceed without a key pair*, vì vào máy bằng Session Manager.
   - Security group: inbound **TCP 80, TCP 443** (thêm UDP 443 nếu muốn HTTP/3) từ `0.0.0.0/0`. **Không mở cổng 22.**
   - Storage: 20 GB gp3.
   - *Advanced details*: **IAM instance profile** = `motoflow-ec2`; **User data** = dán toàn bộ `setup.sh` đã sửa ở bước 1.
   - **Tags: `App` = `motoflow`, `Env` = `dev`.** Pipeline tìm server theo đúng hai tag này.
3. **Elastic IP:** EC2 → Elastic IPs → Allocate → Associate vào instance, để IP không đổi khi stop/start.
4. **Domain (nên có):** tạo bản ghi DNS `A` cho `dev.<domain>` trỏ về Elastic IP. Caddy sẽ tự lấy chứng chỉ HTTPS ở request đầu tiên.
5. **Kiểm tra:** Systems Manager → Fleet Manager thấy instance *Online*. EC2 → instance → *Connect* → *Session Manager*:
   ```bash
   sudo /opt/motoflow/bin/motoflow ps        # proxy và db đang chạy
   sudo grep MOTOFLOW_ADMIN_PASSWORD /opt/motoflow/.env   # mật khẩu tạm của Admin đầu tiên
   ```

Không có domain thì giữ `SITE_ADDRESS=":80"`, khi đó `.env` tự đặt `REFRESH_COOKIE_SECURE=false`. Đăng nhập vẫn được, nhưng chỉ nên dùng để thử. Khi có domain, sửa `SITE_ADDRESS`, `MOTOFLOW_CORS_ORIGINS=https://<domain>` và `REFRESH_COOKIE_SECURE=true` trong `/opt/motoflow/.env`, rồi chạy `sudo /opt/motoflow/bin/motoflow up -d proxy backend`.

Chi phí ước tính cho một môi trường: t3.small + 20 GB gp3 + một IPv4 công khai, khoảng 25 USD/tháng nếu chạy liên tục. Kiểm tra lại bằng AWS Pricing Calculator. Tắt instance khi không dùng sẽ bớt tiền EC2, nhưng IPv4 vẫn bị tính phí.

### 3.6 Server production (khi cần, ví dụ trước UAT tuần 12)

Làm giống 3.5, chỉ khác tag **`Env` = `production`** và dùng domain riêng. Sau đó đặt `PROD_ENABLED=true` và `PROD_BASE_URL` ở GitHub (mục 3.7). Trước khi có server production, tag `v*` không deploy gì cả (job bị bỏ qua).

### 3.7 GitHub (làm ở cả hai repo)

**Settings → Secrets and variables → Actions → tab Variables → New repository variable.** Không cần secret nào.

| Biến | Giá trị | Tác dụng |
|---|---|---|
| `AWS_ACCOUNT_ID` | 12 chữ số | Bật build-push-image và deploy-dev |
| `DEV_BASE_URL` | `https://dev.<domain>` hoặc `http://<Elastic IP>` | URL cho smoke test sau deploy dev |
| `AWS_REGION` | *(tùy chọn)* | Mặc định `ap-southeast-1` |
| `PROD_ENABLED` | `true` | Bật `release.yml` khi đã có server production |
| `PROD_BASE_URL` | `https://<domain production>` | URL cho smoke test production |

**Bảo vệ nhánh `main`:** Settings → Rules → Rulesets → *New branch ruleset* → target `main`:
- Require a pull request before merging.
- Require status checks to pass: `build-test`.

Có thể thêm một *tag ruleset* cho `v*` để chỉ PM được tạo tag release. Lưu ý: theo tài liệu GitHub, repo **private** trên gói **Free** không áp dụng được ruleset/branch protection. Nếu org đang ở gói Free, nhóm cần thống nhất quy tắc "không merge khi CI đỏ", hoặc nâng gói (xem các ưu đãi của GitHub Education), hoặc chuyển repo sang public.

---

## 4. Dùng hằng ngày

### Làm tính năng

1. Tạo nhánh từ `main`, code, chạy `./mvnw test` ở máy.
2. Mở PR vào `main`. `build-test` phải xanh, và có người review.
3. Merge. Khoảng 5–10 phút sau, bản mới lên dev. Xem tiến trình ở tab **Actions**; phần *Summary* của run ghi image digest đã deploy.

### Phát hành production (Software Package vN)

```bash
git switch main
git pull
git tag -a v1.0.0 -m "Software Package v1"
git push origin v1.0.0
```

Làm ở **cả hai repo**: BE trước, FE sau. Nếu `main` vừa mới merge, `release.yml` sẽ chờ tối đa 20 phút để CI/CD trên `main` build xong image cho commit đó.

### Rollback

- **Tự động:** nếu bản mới không qua health check trên server, `deploy-service` đưa image cũ trở lại và job báo đỏ.
- **Dev:** Actions → *CI/CD* → mở run cũ đã xanh → job `deploy-dev` → **Re-run this job**. Job sẽ deploy lại đúng image của run đó.
- **Production:** Actions → *Release* → **Run workflow** → *Use workflow from* → tab **Tags** → chọn tag cũ (ví dụ `v1.0.0`) → Run.
- **Database:** rollback chỉ đổi code. Migration Flyway đã chạy thì vẫn giữ nguyên. Vì vậy thay đổi schema phải theo **expand → migrate → contract**: thêm cột/bảng mới trước; chỉ xóa cột cũ ở một release sau, khi không còn bản nào cần quay về. Không bao giờ sửa file `V*.sql` đã chạy.

### Đổi API giữa FE và BE

Deploy BE trước. BE mới phải vẫn phục vụ được FE đang chạy (tương thích ngược). Chỉ bỏ API cũ khi FE đã chuyển sang API mới.

---

## 5. Vận hành server

Vào máy: EC2 → chọn instance → **Connect → Session Manager**.

```bash
sudo /opt/motoflow/bin/motoflow ps                          # trạng thái các container
sudo /opt/motoflow/bin/motoflow logs -f --tail 200 backend  # log backend
sudo cat /opt/motoflow/images.env                           # image đang chạy
sudo vi /opt/motoflow/.env                                  # sửa cấu hình/secret
sudo /opt/motoflow/bin/motoflow up -d proxy backend         # áp dụng sau khi sửa .env
sudo /opt/motoflow/bin/motoflow exec -T db pg_dump -U motoflow motoflow > /tmp/motoflow.sql   # sao lưu DB
```

**Sửa cấu hình server** (`compose.yaml`, Caddyfile, `deploy-service`): sửa `deploy/server/setup.sh` trong repo → merge → Systems Manager → **Run Command** → `AWS-RunShellScript` → dán nội dung `setup.sh` → chọn target theo tag `Env`. Script chạy lại được nhiều lần và không ghi đè `.env`.

---

## 6. Khi pipeline đỏ

| Triệu chứng | Nguyên nhân thường gặp | Cách xử lý |
|---|---|---|
| `build-test` đỏ ở bước test | Test fail | Tải artifact `test-reports` của run, xem file trong `surefire-reports` |
| `build-push-image` bị *skipped* | Chưa đặt `AWS_ACCOUNT_ID` | Đặt biến (mục 3.7) |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Trust policy sai `sub`/account, hoặc chưa có OIDC provider | Kiểm tra mục 3.2–3.3, chú ý chữ hoa/thường của tên org/repo |
| `No running EC2 instance tagged App=motoflow, Env=dev` | Sai tag hoặc instance đang tắt | Sửa tag / bật instance |
| SSM kẹt `Pending` rồi `Undeliverable` | SSM agent offline: thiếu instance profile `motoflow-ec2` hoặc máy không ra Internet | Gắn role, kiểm tra Fleet Manager |
| `AccessDenied ... ssm:SendCommand` | Tag instance không khớp policy | Tag phải đúng `App=motoflow` và `Env=dev`/`production` |
| `backend did not become healthy` | `.env` sai, DB chưa sẵn sàng, migration lỗi | Đọc log in trong nhóm log của job; vào server xem `motoflow logs backend` |
| Smoke test `HTTP 000` | `DEV_BASE_URL` sai, security group chưa mở 80/443, DNS chưa trỏ | Kiểm tra lần lượt |
| Smoke test `HTTP 502` | Backend chưa từng deploy, hoặc đang khởi động lại | Xem `motoflow ps` |
| `403 Invalid CORS request` khi đăng nhập trên web | Origin không khớp | Kiểm tra `MOTOFLOW_CORS_ORIGINS` trong `.env` đúng `https://<domain>` |
| Bị đăng xuất sau 15 phút | Cookie refresh có cờ `Secure` nhưng site chạy HTTP | Dùng domain + HTTPS |
| Release: `No image for ...` | CI/CD trên `main` chưa xong hoặc bị đỏ ở commit đó | Đợi CI xanh rồi chạy lại Release |
| Release: `... which is not on main` | Tag đặt trên commit ngoài `main` | Xóa tag, tag lại trên `main` |

---

## 7. Việc nên làm tiếp

1. **`mvnw` mất quyền thực thi** (đang được commit với mode `100644`). CI đã chạy bằng `sh ./mvnw` nên không bị ảnh hưởng, nhưng nên sửa: `git update-index --chmod=+x mvnw`.
2. **Thêm Spring Boot Actuator** (`spring-boot-starter-actuator`), cho phép `/actuator/health` trong `SecurityConfig`, rồi đổi health check trong `setup.sh` sang endpoint đó. Hiện tại health check dùng `/v3/api-docs`, nên sẽ hỏng nếu sau này tắt Swagger ở production.
3. **Sao lưu DB hằng ngày** (`pg_dump` → S3) nếu production/UAT dùng PostgreSQL container.
4. **Bỏ giá trị mặc định không an toàn** trong `application.properties` (JWT secret mẫu, `admin/admin`). Server không bị ảnh hưởng vì `.env` luôn đặt các giá trị này, nhưng nên bỏ để tránh vô tình chạy production bằng giá trị mẫu.
5. **SDS (Report 4)**: API thực tế dùng `/api/...` chứ không phải `/api/v1`.
