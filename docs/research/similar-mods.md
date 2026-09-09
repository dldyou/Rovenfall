# 유사 모드/플러그인 패턴 조사

조사일: 2026-09-02
범위: Rovenfall의 다음 마일스톤(클레임·월드·포털, 활동·커리어·스킬)에 바로 적용할 수 있는 설계 및 운영 패턴. 외부 근거는 각 프로젝트의 공식 문서 또는 해당 프로젝트의 공식 GitHub 저장소만 사용했다.

## 현재 상태와 제외 범위

로드맵상 Milestone 0과 1은 완료이고, 다음 공식 순서는 Milestone 2(경제·관리자 상점) → Milestone 3(클레임·월드·포털) → Milestone 4(활동·커리어·스킬)다. 현재 작업 트리에도 이미 경제/상점, `claims` 도메인, 클레임 구매·관리·보호 서비스와 그 테스트가 존재한다. 따라서 아래 제안은 다음을 다시 만들자는 뜻이 아니다.

- 통화 계정·상점 인스턴스·재고·거래 감사
- Hub 청크의 기본 소유권·역할·보호 훅
- 플랫폼 영속성, 정의 리로드, 관리자 역할과 감사 장부

대신 이미 있는 권한/감사 경계 위에 포털 안전성, Wilderness 운영, 활동 증거, 커리어 설명 가능성을 추가하는 작은 다음 단위들을 제안한다.

## 비교 관찰

| 대상 | 공식 자료에서 확인한 패턴 | Rovenfall에 주는 결론 |
| --- | --- | --- |
| [FTB Chunks](https://github.com/FTBTeam/FTB-Chunks/blob/main/README.md) | 서버 청크 소유권과 클라이언트 지도를 결합하며, 공식 가이드는 지도에서 클릭/드래그로 복수 청크를 청구·해제하게 한다. [청구/로딩 가이드](https://github.com/FTBTeam/docs/blob/main/mod-docs/mods/suite/Chunks/claiming-loading.md) | 청구의 권한 판정과 지도 UX를 분리한다. Rovenfall은 이미 청구 도메인이 있으므로, 다음 UX는 새 상태를 만들기보다 서버의 dry-run 결과를 지도/명령에 표시해야 한다. |
| [FTB Chunks 관리자 명령](https://github.com/FTBTeam/docs/blob/main/mod-docs/mods/suite/Chunks/commands.md) | 보호 우회, 특정 팀 명의 청구, 추가 청구 한도, 강제 해제처럼 운영자용 경로를 별도로 둔다. | 플레이어 청구 행위와 운영 복구 행위는 같은 내부 검증을 쓰되, 권한·감사 사유·UX를 분리한다. Rovenfall의 관리자 역할/감사 기반과 잘 맞는다. |
| [FTB Teams](https://github.com/FTBTeam/docs/blob/main/mod-docs/mods/suite/Teams/index.md) | 팀 생성·초대·권한·동맹을 제공하고, **서버 팀**은 플레이어가 가입/초대/탈퇴할 수 없는 관리 전용 소유자로 정의한다. | 포털 링, 도로, 보스 경기장 같은 보호 구역은 플레이어 클레임의 예외 플래그가 아니라 관리자 소유 보호 영역으로 모델링하는 편이 운영상 명확하다. |
| [Waystones](https://github.com/TwelveIterations/Waystones) | 활성화된 웨이스톤으로의 텔레포트를 제공하며 NeoForge 배포도 지원한다. 유지보수 이력은 텔레포트를 바닐라 `/tp` 논리와 일치시키고, 탈것·탑승자 이동/재탑승을 명시적으로 처리한 수정도 보여 준다. [텔레포트 정합성 수정](https://github.com/TwelveIterations/Waystones/commit/3dacd7d), [탈것/탑승자 수정](https://github.com/TwelveIterations/Waystones/commit/eb201c3) | 포털의 좌표 이동은 단순 `teleportTo` 호출이 아니라, 안전 좌표 탐색·차원 로드·승객 정책·성공 시점의 쿨다운을 하나의 서버 트랜잭션으로 다뤄야 한다. |
| [Project MMO 2.0](https://github.com/Caltinor/Project-MMO-2.0/blob/main/wiki/docs/core/skills.mdx) | 경험치를 이름 붙은 독립 스킬에 적립하고, 스킬은 보너스/요구 조건/활동 기록 중 무엇으로 쓸지 데이터로 정한다. 스킬 그룹은 XP와 보너스를 비율로 분배하고 요구 조건에는 합산 레벨을 쓸 수 있다. | Rovenfall의 고정 7개 활동 트랙은 유지하되, “무슨 서버 검증 이벤트가 어느 트랙에 얼마나 기여했는가”를 데이터 정의로 분리하고, 커리어 조건의 합산/개별 판정은 설명 가능한 결과로 반환해야 한다. |
| [Project MMO 2.0 스크립팅 문서](https://github.com/Caltinor/Project-MMO-2.0/blob/main/wiki/docs/configuration/scripting.mdx) | XP 지급·피해·요구 조건·보너스를 이벤트별 설정으로 표현하고, 별도 anti-cheese 설정을 둔다. | 단순 이벤트 수신을 XP 지급으로 곧바로 연결하지 말고, 활동별 provenance와 anti-farming 판정을 먼저 통과시킨 뒤에만 진행도를 변경한다. 이는 Rovenfall 도메인 모델의 자연 생성 자원·첫 발견·기여도 같은 규칙과 일치한다. |
| [MineColonies Town Hall](https://minecolonies.com/wiki/buildings/townhall/) | 중심 블록이 보호 반경을 확정하고, GUI에서 플레이어·등급·개별 권한·권한 이벤트를 함께 관리한다. 보호 범위는 기본적으로 전체 높이를 덮으며, 배치/파괴/상호작용·물·TNT까지 다룬다. | 역할명만 제공하는 것보다 “선택한 역할이 현재 행동을 할 수 있는가”와 최근 거부 원인을 한 화면/명령에서 보여 주는 운영 UX가 중요하다. |
| [MineColonies Colony Protection](https://minecolonies.com/wiki/systems/protection/) | 기본값은 소유자만 배치·파괴 가능, 보호는 폭발을 기본 차단하고, 다른 플레이어는 Town Hall 권한 UI에서 추가한다. | Rovenfall의 보호 매트릭스는 계속 서버 권위로 유지하고, 폭발/유체 같은 환경 효과도 일반 상호작용과 동일한 결정 근거와 감사 표면을 가져야 한다. |

## 우선순위 제안

아래는 기존 구현을 복제하지 않고, 로드맵의 다음 완료 조건을 실제로 통과시키기 위한 추가 작업 단위다. P0는 Milestone 3을 막는 선행 seam, P1은 Milestone 4를 안전하게 시작시키는 seam이다.

1. **P0 — `SafeArrivalResolver`를 포털 도메인의 단일 안전 도착 seam으로 만든다.** 목적 차원과 후보 좌표를 받아 발 디딤면, 두 블록 충돌 여유, 유체/화염/낙하 위험, 월드 경계, 보호 영역 및 진입 제한을 검사하고, 안전 좌표 또는 기계 판독 가능한 거부 사유를 반환한다. Waystones가 바닐라 텔레포트 정합성과 탈것 처리를 별도 수정으로 다뤘다는 점은 이 경계가 실제로 취약함을 보여 준다. [정합성 수정](https://github.com/TwelveIterations/Waystones/commit/3dacd7d), [승객 수정](https://github.com/TwelveIterations/Waystones/commit/eb201c3) 근거: “블록/위험/비인가 클레임 안에 도착하지 않는다”는 Rovenfall의 Milestone 3 exit에 직접 대응한다.

2. **P0 — 포털 이동을 `PortalTransitRequest` 한 건의 원자적 커밋으로 정의한다.** 원점 범위·포털 접근권·목적지 안전성·승객 정책을 모두 재검증한 후에만 이동을 실행하고, 성공한 경우에만 쿨다운과 감사 이벤트를 기록한다. 실패·취소·목적지 무효 시에는 쿨다운을 소비하지 않고 사유를 감사한다. 활성화된 목적지로만 이동시키는 Waystones의 기본 모델은, Rovenfall에서도 “정의됨”과 “현재 사용 가능함”을 구분해야 함을 뒷받침한다. [공식 README](https://github.com/TwelveIterations/Waystones) 근거: 재시도와 악의적 패킷 모두에서 부분 상태를 없애는 가장 작은 트랜잭션 경계다.

3. **P0 — 보호 구역 우선순위를 포털/클레임에서 공용 정책으로 고정한다.** `ProtectedRegion > portal-destination-policy > player-claim entry flag > wilderness default`처럼 한 곳에서 평가하고, 결과에 적용 규칙 ID와 거부 사유를 포함한다. FTB Teams의 가입 불가능한 서버 팀은 플레이어 조직과 관리 소유권을 분리하는 선례다. [서버 팀 정의](https://github.com/FTBTeam/docs/blob/main/mod-docs/mods/suite/Teams/index.md) 근거: 포털 링과 보스 경기장을 개인 클레임의 예외로 누적시키지 않으며, safe-arrival와 일반 상호작용이 서로 다른 허용 결정을 내리는 일을 막는다.

4. **P0 — Wilderness reset을 실행 전 검증 가능한 운영 작업으로 만든다.** “공지 생성 → 접속자/대상 차원 확인 → Hub 안전 지점으로 대피 → 스냅샷 ID 고정 → 차원 교체 → 결과 검증 → 단일 감사”를 단계 상태와 재시도 가능 조건으로 저장한다. MineColonies가 영구 보호 영역의 제거를 일반 블록 파괴가 아닌 관리자 명령으로 제한하는 패턴은, 고영향 월드 변경을 명시적 운영 작업으로 분리해야 함을 보여 준다. [Town Hall 보호 영역 관리](https://minecolonies.com/wiki/buildings/townhall/) 근거: 로드맵의 “evacuation, snapshot, restore evidence, audit”을 운영자가 재현·설명할 수 있다.

5. **P0 — 클레임 행위의 서버 dry-run/설명 결과를 먼저 추가한다.** 구매·역할 변경·진입·상호작용 요청에 대해 `allowed`, `effectiveRole`, `policyRule`, `price/cap` 또는 `denialCode`를 반환하는 읽기 전용 질의를 만들고, 명령과 이후 지도 UI가 그것을 그대로 표현하게 한다. FTB Chunks는 청구 상태 조회와 관리자 복구 명령을 분리하고, MineColonies는 권한 이벤트와 등급별 토글을 같은 Town Hall UX에서 제공한다. [FTB 명령](https://github.com/FTBTeam/docs/blob/main/mod-docs/mods/suite/Chunks/commands.md), [MineColonies 권한 UX](https://minecolonies.com/wiki/buildings/townhall/) 근거: 이미 있는 보호 로직을 건드리지 않고 실패 사유와 운영 대응을 크게 개선한다.

6. **P1 — 활동 XP 전에 `ActivityEvidence`를 영속·검증한다.** 활동마다 서버가 작성한 증거(행동 종류, 차원/청크, 대상 ID, 자연 생성/성숙/첫 발견 여부, 기여량, 시간 창)를 만들고, dedupe 키·대상별 cap·시간 창을 적용한 뒤에만 XP를 부여한다. Project MMO가 이벤트 기반 XP 정의와 anti-cheese 설정을 분리하는 것은 이 두 단계를 분리할 실용적 근거다. [이벤트/anti-cheese 설정](https://github.com/Caltinor/Project-MMO-2.0/blob/main/wiki/docs/configuration/scripting.mdx) 근거: Milestone 4의 provenance·anti-farming 요구를 사후 패치가 아니라 데이터 모델에서 충족한다.

7. **P1 — 7개 고정 활동 트랙의 데이터 카탈로그와 검증기를 먼저 만든다.** 모든 XP 정의는 `combat`, `cooking`, `mining`, `exploration`, `hunting`, `building`, `farming` 중 하나만 가리키게 하고, 존재하지 않는 트랙·음수 XP·허용되지 않은 이벤트 조합은 정의 리로드에서 거부한다. Project MMO는 임의 이름의 스킬 및 데이터 기반 스킬 그룹을 허용한다. [스킬/그룹 문서](https://github.com/Caltinor/Project-MMO-2.0/blob/main/wiki/docs/core/skills.mdx) 근거: 그 유연성은 참고하되, Rovenfall의 의도된 7개 트랙과 밸런스/현지화 범위를 지켜서 관리 불가능한 트랙 증식을 피한다.

8. **P1 — 커리어 승급/전환에 ‘설명 가능한 판정’ 반환값을 도입한다.** `PromotionEvaluation`에 충족/미충족 각 조건, 부족 수치, 비용, 차단한 형제 분기, 초기화될 후손을 포함하고, 확인 이후에만 하나의 비용·상태·감사 트랜잭션을 실행한다. Project MMO도 스킬을 장비/지역·차원 접근의 요구 조건으로 사용하며 그룹 합산 요구를 지원한다. [스킬의 요구 조건 용도](https://github.com/Caltinor/Project-MMO-2.0/blob/main/wiki/docs/core/skills.mdx) 근거: Rovenfall의 임의 tier·분기·상호 배타 전환을 플레이어와 관리자가 검증 가능하게 만들며, 나중의 GUI와 명령이 판정 로직을 중복하지 않게 한다.

## 권장 구현 순서

Milestone 3에서는 **1 → 2 → 3 → 4 → 5** 순서가 안전하다. 안전 도착과 정책 결정을 먼저 고정해야 포털 쿨다운·Wilderness 대피가 같은 규칙을 재사용한다. Milestone 4 시작 시에는 **6 → 7 → 8** 순서가 적합하다. 활동 증거와 정의 검증이 먼저 있어야 커리어 조건/보상에 오염된 진행도가 들어가지 않는다.

## 주요 1차 출처

- [FTB Chunks 공식 저장소](https://github.com/FTBTeam/FTB-Chunks), [FTB Chunks 공식 문서](https://github.com/FTBTeam/docs/tree/main/mod-docs/mods/suite/Chunks)
- [FTB Teams 공식 저장소](https://github.com/FTBTeam/FTB-Teams), [FTB Teams 공식 문서](https://github.com/FTBTeam/docs/blob/main/mod-docs/mods/suite/Teams/index.md)
- [TwelveIterations Waystones 공식 저장소](https://github.com/TwelveIterations/Waystones)
- [Project MMO 2.0 공식 저장소 및 내장 위키](https://github.com/Caltinor/Project-MMO-2.0)
- [MineColonies 공식 위키](https://minecolonies.com/wiki/)
