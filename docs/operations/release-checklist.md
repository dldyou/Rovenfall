# 릴리스 후보 검증

최종 검증일: 2026-09-03

## 단일 검증 명령

관리 콘솔 소스를 변경했다면 먼저 웹 번들을 갱신한다.

```powershell
cd admin-web
pnpm install
pnpm typecheck
pnpm lint
pnpm build
cd ..
```

그다음 모드 전체를 검증한다.

```powershell
.\gradlew.bat releaseCheck --offline
```

`releaseCheck`는 다음을 모두 실행한다.

- Java 25 컴파일과 전체 빌드
- JUnit 단위 테스트
- NeoForge 전용 GameTest 서버
- 배포 JAR 생성
- 모드 메타데이터, 3개 언어, 내장 관리 콘솔, Wilderness 차원, 황야 발전과제, 내장 상점 템플릿, 커스텀 몹·전리품·사냥 제작 아이템, 개인 보스 유물·최종 무기, 직업·변이 데이터 포함 여부
- JUnit 및 Minecraft 본체 클래스가 JAR에 잘못 번들되지 않았는지 검사
- 배포 JAR SHA-256 출력

## 현재 결과

| 검사 | 결과 |
| --- | --- |
| 웹 린트·타입 검사·프로덕션 빌드 | 통과 |
| 단위 테스트 | 663/663 통과, 135 suites |
| GameTest | 47/47 통과 |
| 전체 빌드 | 통과 |
| `git diff --check` | 오류 없음(Windows 줄바꿈 안내만 존재) |
| JAR 크기 | 5,015,824 bytes |
| SHA-256 | `2AF5EF710CB5ECFEFA0543922572E8A294CEE2CE5F2D14155ED25501574CA773` |

배포 파일:

```text
build/libs/rovenfall-1.0-SNAPSHOT.jar
```

소스나 리소스를 다시 변경하면 위 해시와 크기는 무효다. 반드시 `releaseCheck`를 다시 실행하고 이 표를 갱신한다.
