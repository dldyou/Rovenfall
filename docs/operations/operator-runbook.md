# Rovenfall 운영자 런북

기준 버전: 플랫폼 스키마 14, Minecraft 26.2 / NeoForge 26.2.0.66

## 시작과 권한

인게임 요약은 `/rovenfall admin help`, 플레이어 요약은 `/rovenfall help`로 확인한다. 관리자 역할이 하나도 없을 때만 네이티브 서버 OP가 최초 `owner`를 지정할 수 있다. 역할이 생긴 뒤에는 저장된 `owner`만 역할을 변경한다.

| 역할 | 주 용도 |
| --- | --- |
| `viewer` | 검색, 경제·감사 조회 |
| `moderator` | 토지 신뢰 권한과 보호 설정 교정, 비경제 토지 변경 되돌리기 |
| `economy_manager` | 잔액, 관리자 상점, 상점 변경 되돌리기 |
| `content_manager` | 포털, 보스, 직업·스킬 변경 되돌리기 |
| `owner` | 모든 권한, 역할 지정, 스냅샷 복원, Wilderness 초기화, 경제가 포함된 토지 변경 되돌리기 |

역할 지정:

```text
/rovenfall admin role set <player> <role> <reason>
```

## 조회와 조사

통합 검색은 읽기 전용이며 상태나 감사 기록을 변경하지 않는다.

```text
/rovenfall admin search <scope> <page> <query>
```

`scope`는 `players`, `balances`, `transactions`, `claims`, `shops`, `denied`, `alerts` 중 하나다. 전체 목록은 검색어로 `*`를 사용한다. 결과는 플레이어에게 책 화면으로, 콘솔에는 메시지 페이지로 표시된다.

전체 감사 기록은 다음 명령으로 확인한다.

```text
/rovenfall admin audit list [page]
/rovenfall admin audit gui [page]
```

경제 경고 기본값은 단일 거래 `100000` 이상 또는 같은 플레이어가 `60`초 동안 `20`건 이상 거래하는 경우다. 경고는 저장되고 서버 로그에도 게시된다. 서버 설정의 `economy.alert_amount`, `economy.alert_rate`, `economy.alert_window_seconds`로 조정한다.

## 거래 ID와 재시도

상태를 바꾸는 주요 명령은 UUID 거래 ID를 받는다. 매 새 작업에는 새 UUID를 사용하고, 네트워크 재시도에는 원래 UUID와 같은 요청 내용을 그대로 사용한다. 이미 다른 작업에 사용된 UUID는 충돌로 거부되며 부분 변경은 일어나지 않는다.

## 황야 보급상 배치

내장 `rovenfall:wilderness_outfitter` 템플릿으로 상점 인스턴스를 만든 뒤 황야의 실제 위치에 연결한다. 아래 세 관리 명령은 각각 서로 다른 새 UUID를 사용한다.

```text
/rovenfall admin shop create rovenfall:wilderness_outfitter rovenfall:wilderness_outfitter <transaction_id> <reason>
/rovenfall admin shop bind rovenfall:wilderness_outfitter rovenfall:wilderness <x> <y> <z> <transaction_id> <reason>
/rovenfall admin shop access rovenfall:wilderness_outfitter 8 <transaction_id> <reason>
```

플레이어는 상품·가격·현재 재고를 확인한 뒤 Tab으로 상점 및 거래 가능한 상품 ID를 완성할 수 있다. 거래 ID를 생략하면 서버가 새 UUID를 만든다. 재시도에 같은 ID를 보존해야 하는 외부 도구에서는 마지막 인수로 UUID를 직접 전달한다.

```text
/rovenfall shop info rovenfall:wilderness_outfitter
/rovenfall shop buy rovenfall:wilderness_outfitter rovenfall:outfitter_rations 1 [transaction_id]
/rovenfall shop sell rovenfall:wilderness_outfitter rovenfall:outfitter_cinder_core 1 [transaction_id]
```

## 표적 되돌리기

일반 도메인 변경은 다음 명령으로 되돌린다.

```text
/rovenfall admin reverse <original_transaction_id> <reversal_transaction_id> <reason>
```

지원 범위는 토지 구매·판매·양도, 토지 신뢰/보호 설정, 관리자 상점 설정, 직업 승급, 스킬 해금·초기화다. 변경 전후의 정확한 영수증이 스키마 14부터 저장된다. 현재 상태가 원본 거래 직후 상태와 다르면 아무것도 변경하지 않고 거부한다. 이 경우 가장 최근의 종속 거래부터 역순으로 되돌린다. 스키마 13 이전 거래, 만료된 거래, 변경 없음 거래에는 정확한 증거가 없으므로 표적 되돌리기를 사용할 수 없다.

플레이어 인벤토리와 상점 재고가 포함된 매수·매도는 별도 경제 역연산을 사용한다. 대상 플레이어가 온라인이어야 정확한 아이템을 검사할 수 있다.

```text
/rovenfall admin economy reverse <player> <original_transaction_id> <reversal_transaction_id> <decision> <reason>
```

`decision`은 기본적으로 `strict`를 사용한다. 구매 아이템이나 재고를 정확히 되돌릴 수 없고 잔액만 환불해야 한다는 운영 판단이 있을 때만 `refund_without_items_or_stock`을 사용한다. 이 보상 결정도 감사된다.

## 스냅샷과 고위험 작업

대규모 변경 전:

```text
/rovenfall admin snapshot create <reason>
```

전체 복원은 표적 되돌리기로 해결할 수 없는 경우에만 사용한다. 복원 작업은 먼저 안전 스냅샷을 만들고, 최근 거래 증거를 병합·검증한 후 한 번에 커밋한다.

```text
/rovenfall admin snapshot restore <snapshot_id> <reason>
```

Wilderness 초기화는 즉시 디렉터리를 지우는 명령이 아니다. 접속자 대피, 백업, 교체, 검증 단계를 저장하는 예약 작업이다.

```text
/rovenfall admin wilderness reset confirm <reason>
/rovenfall admin wilderness status [operation_id]
```

상태가 실패이면 같은 명령을 무작정 반복하지 말고 기록된 실패 코드와 백업 영수증부터 확인한다.

## 배포 확인

배포 파일은 `build/libs/rovenfall-<version>.jar` 하나다. `build/classes`, `run`, GameTest 월드, 테스트 보고서, `src/generated`를 서버의 `mods` 폴더에 넣지 않는다. 릴리스 후보는 단위 테스트, GameTest, 전체 빌드를 모두 통과한 JAR만 사용한다.
