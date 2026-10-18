# OCI 운영과 복구

운영 화면은 `https://gamestock-20994.web.app`, API는 `https://gamestock-api.161-33-33-45.sslip.io`를 사용한다. Firebase 인증은 유지한다. 모바일 WebView도 같은 화면을 사용하며 네이티브 인증 API 기본 주소는 `mobile/src/config.js`에 있다. 이미 설치한 앱에 이전 네이티브 주소가 포함돼 있다면 다시 빌드해야 한다.

## 서버

- Oracle Linux 9 ARM64, Java 17, Nginx, systemd.
- `gamestock.service`: 운영 백엔드, `127.0.0.1:8081`만 수신.
- `/opt/gamestock/releases/gamestock-backend-production.jar`: 실행 파일.
- `/etc/gamestock/production.env`: 사설 MySQL 연결 및 관리자 설정. 내용을 출력하거나 Git에 넣지 않는다.
- `/etc/gamestock/firebase-admin.json`: Firebase 관리자 자격증명.
- MySQL `gamestock` 스키마에만 접근하는 `gamestock_app` 계정으로 TLS 연결한다.
- `/etc/nginx/conf.d/gamestock-https.conf`: HTTPS API 및 `/ws/market`, `/ws/stream` 프록시.
- 공인 IP가 바뀌면 IP 기반 도메인, 인증서, 워크플로, 웹·모바일 기본 API 주소를 함께 바꾼다.

```sh
sudo systemctl status gamestock nginx --no-pager
sudo journalctl -u gamestock --since '10 minutes ago' --no-pager
curl -fsS https://gamestock-api.161-33-33-45.sslip.io/api/health
sudo systemctl restart gamestock
```

`matchingEnabled`, `batchBotsEnabled`와 스케줄러 시작 상태가 켜져 있어야 한다. HTTP 200만 보지 말고 종목·호가·최근 체결, 로그인, 계좌, 주문·취소, WebSocket 재연결도 확인한다. 관리자 계정은 주문이 차단된다.

## 백업

`gamestock-backup.timer`가 매일 한국 시간 03:30에 논리 백업을 생성한다. `/var/backups/gamestock/`에는 최근 7일의 압축 SQL을 root 전용으로 보관한다. 원본 DB 외부에 복사한 백업도 유지해야 VM 손실에 대비할 수 있다. 이전 시점의 Railway 최종 백업은 Windows의 접근 제한된 OCI 설정 폴더에 별도로 보존한다. 백업에는 개인정보가 포함되므로 공유·커밋하지 않는다.

```sh
sudo systemctl start gamestock-backup.service
sudo systemctl list-timers gamestock-backup.timer gamestock-certbot-renew.timer
sudo journalctl -u gamestock-backup.service --no-pager
sudo /opt/certbot/bin/certbot renew --dry-run
```

백업은 먼저 비어 있는 별도 스키마에 복원한다. 운영 DB를 덮어쓰기 전에 54개 테이블의 행 수·체크섬과 회원 자산·주문·체결의 일관성을 비교하고 기동을 검증한다. 자격증명은 명령행 인자에 직접 쓰지 말고 접근 제한된 MySQL 클라이언트 설정을 사용한다.

## 전환과 롤백

한 시점에 하나의 운영 매칭 엔진만 실행한다. 전환은 기존 서버 중단 → 최종 백업 → 새 빈 스키마 복원 → 행 수·체크섬 비교 → OCI 기동 → Firebase 웹 배포 순서다. Railway 자동 배포를 비활성화해 GitHub push가 이전 엔진을 다시 실행하지 않게 한다. 이전 Railway DB는 복구용으로 보존하며 새 주문을 받지 않는다.

OCI가 운영 주문을 받은 뒤에는 오래된 Railway DB로 URL만 되돌리면 안 된다. 먼저 신규 주문을 막고 OCI를 중단·백업한 뒤 최신 상태를 복원해 검증하고, 그다음 웹 주소를 전환한다. 매칭 엔진을 재시작할 때도 이전 프로세스 종료를 확인한다.

웹은 `.github/workflows/firebase-hosting.yml`로 배포한다. 백엔드는 테스트한 JAR를 서버에 전송하고 설치한 뒤 `gamestock.service`를 재시작한다. GitHub의 프런트엔드 배포가 백엔드 JAR까지 갱신하지는 않는다.
