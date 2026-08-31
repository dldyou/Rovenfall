# RPG·경제 기능 공백 조사 (2026-08)

## 결론

다음 작업은 **포털 탐색기와 현장 길찾기**로 잡는 것을 권장한다. 현재 포털 이동은
raw `portal_id`를 넣는 명령어만 남아 있어, 원클릭·ID 비노출·전용 UI라는 제품
원칙에 직접 어긋난다. 탐색기는 포털을 원격으로 작동시키지 않고, 현재 있는
현장 8블록 거리·쿨다운·전투 잠금·보호 상태·안전 도착 검증을 재사용한다. 그 다음
후보는 **일일·주간 의뢰 게시판**, 마지막은 **탐험 기록과 목적지 추적**이다.

이 순서는 대형 RPG 서버에서 보이는 `목적지 선택·현장 상호작용 → 짧은 반복 목표
→ 장기 탐험 기록` 흐름을 작은 서버 권한·저장소 경계에 맞게 축소한 것이다.
Wynncraft는 Content Book에서 콘텐츠를 선택해 길잡이 표식을 만들고,
[공식 King's Recruit 문서](https://wynncraft.wiki.gg/wiki/King%27s_Recruit)가 이
온보딩 흐름을 보여 준다. Hypixel의 공식 Dwarven Mines 패치 노트는 반복 의뢰의
진행 확인, 보상, 하루 첫 4회 보너스를 한 흐름으로 설명한다
([공식 SkyBlock 0.11 패치 노트](https://hypixel.net/threads/skyblock-patch-0-11-dwarven-mines.3749492/)).

## 조사 범위와 근거

- 기준일은 2026-08-31이다. 비교 대상은 공개적으로 확인 가능한 공식 저장소,
  공식 위키, 공식 설정/문서만 사용했다. 이 문서는 특정 서버의 비공개 운영 수치나
  추정 매출을 근거로 삼지 않는다.
- Hypixel 공식 위키는 2026년 7월 종료되어 기존 문서 주소가 종료 공지로 이동한다
  ([공식 종료 공지](https://hypixel.net/threads/end-of-the-official-hypixel-wiki-july-2026.6112020/)).
  따라서 Hypixel 비교는 현재 열리는 운영진 패치 노트만 근거로 삼았다.
- "스트리머형 서버"는 시청자가 따라 하기 쉬운 짧은 목표, 명확한 진행도, 화면에
  드러나는 공동 목표라는 **공개 운영 흐름**으로 한정했다. 공개 문서가 있는 대형
  커뮤니티 RPG 서버의 UI/목표 흐름을 비교했고, 개인 방송인의 비공개 모드팩은
  검증할 수 없어 제외했다.
- Rovenfall의 현재 범위는 [플레이어 GUI 계약](../player-gui.md),
  [퀘스트 정의 계약](../quest-definitions.md),
  [RPG 확장 계약](../rpg-extension-contracts.md)을 기준으로 판정했다. 즉 이미
  Journey 퀘스트, 활동·직업·스킬, 보스, 서버 상점, 토지, 포털, 관리자 UI와
  서버 권한/거래 장부가 있다.

## 비교 결과

| 대표 사례 | 공식적으로 확인되는 흐름 | Rovenfall 상태 | 시사점 |
| --- | --- | --- | --- |
| [Jobs Reborn 공식 저장소](https://github.com/Zrips/Jobs) | 플레이어가 직업을 고르고, 플레이 중 직업 XP·포인트·통화를 얻는다. | 활동 XP·커리어·스킬과 결제 서비스가 이미 있어 직업 시스템 재구축은 중복이다. | 수입을 늘리는 직업보다, 이미 하는 활동의 **다음 할 일**을 제시하는 편이 공백을 메운다. |
| [AuraSkills 공식 저장소의 2.1.0 변경 기록](https://github.com/Archy-X/AuraSkills/blob/master/Changelog.md#210) | 선택 가능한 직업, XP 원천별 보상, 직업 수입과 동시 선택 한도를 제공한다. | Rovenfall은 데이터 정의, 서버 관측 이벤트, 활동/커리어 보상을 이미 분리한다. | 새 범용 마나·직업·스탯 계층은 중복/범위 팽창이다. 의뢰는 기존의 검증된 결과만 소비해야 한다. |
| [Wynncraft Content Book](https://wynncraft.wiki.gg/wiki/Quest) 및 [추적 예시](https://wynncraft.wiki.gg/wiki/King%27s_Recruit) | 퀘스트·동굴·발견을 한 책에 묶고, 선택한 콘텐츠를 표식으로 안내한다. | Rovenfall은 토지 지도 표식과 실제 포털 서비스는 있지만, 포털은 raw ID 명령으로만 선택한다. | 1순위 포털 탐색기는 현장 이동 검증을 유지한 채 목적지 탐색·길찾기만 UI로 옮긴다. |
| [Hypixel SkyBlock 0.11 공식 패치](https://hypixel.net/threads/skyblock-patch-0-11-dwarven-mines.3749492/) | 왕에게 반복 의뢰를 받고 진행도를 확인하며, 보상과 하루 첫 4회 추가 보상을 얻는다. | Journey는 데이터팩 기반의 고정 전개와 완료 보상을 제공하지만, 회차가 정해진 반복 선택 화면은 없다. | 2순위 의뢰 게시판의 반복 목표·진행·일일 보상 기준이다. |
| [Wynncraft의 발견 보상](https://wynncraft.wiki.gg/wiki/World_Discoveries) | 특정 지역 발견에 XP를 주고, 발견은 장기 콘텐츠 기록에 편입된다. | Rovenfall은 Journey와 토지 지도 표식은 있지만 발견/탐험 기록은 없다. | 3순위 탐험 기록은 기존 표식과 Journey UI를 자연스럽게 확장한다. |
| [EssentialsX의 경제 설정](https://github.com/EssentialsX/Essentials/blob/2.x/Essentials/src/main/resources/config.yml) | 잔액 한도, 거래/판매 로그, 명령어 비용처럼 기본 경제 기반을 제공한다. | Rovenfall은 이미 정확한 장부·복구·감사 및 서버 상점을 가진다. | 또 하나의 잔액/거래 API가 아니라, 안전한 소비·보상 경로만 추가한다. |

## 우선순위 후보

### 1. 포털 탐색기와 현장 길찾기 — 다음 Issue 권장

**플레이어 체감 가치.** 인벤토리를 열어 포털의 자연어 목적지·현재 위치에서의
거리를 보고 한 번 눌러 길잡이 표식을 켠다. 포털 블록에 도착해서도 같은 카드의
"사용"을 누를 수 있으므로 UUID/네임스페이스 ID/좌표 명령을 알 필요가 없다.
Wynncraft는 Content Book으로 퀘스트·동굴·발견을 고르고 길잡이 표식을 만들며,
튜토리얼에서도 사용자가 선택한 콘텐츠를 따라가도록 안내한다
([공식 Content Book/퀘스트 문서](https://wynncraft.wiki.gg/wiki/Quest),
[공식 추적 안내](https://wynncraft.wiki.gg/wiki/King%27s_Recruit)). 이는 목적지를
먼저 찾고 현장에서 상호작용하는 공개 RPG 서버 UX의 적합한 기준이다.

**재사용할 기존 서비스.** [플레이어 GUI 계약](../player-gui.md)은 이미 토지
카드의 현재 차원 locator-bar waypoint와 서버 재검증을 정하고, 같은 문서에서
포털이 아직 `/rovenfall portal use <portal_id>` 명령 전용임을 명시한다.
`PortalTravelService`는 origin으로부터 `PortalDefinition.MAX_USE_DISTANCE` 이내인지,
포털 보호 상태, 포털별 쿨다운, 15초 전투 잠금, 대상 차원, 안전한 도착 지점과
거래 영수증을 서버에서 검증한다
([구현](../../src/main/java/org/dldyou/rovenfall/administration/PortalTravelService.java)).
따라서 새 순간이동 정책이나 새 네트워크 권한 경로를 만들 필요가 없다.

**최소 구현 경계.**

- Land Atlas와 같은 전용 카드 UI에 `포털` 목록을 추가한다. 서버가 포털의
  번역된 목적지 세계·현재 차원에서의 거리·이용 가능 여부를 페이지 단위로
  보여 준다. raw ID와 좌표는 기존 고급 정보 토글을 켰을 때만 기술 줄로 보이고
  일반 카드에서는 숨긴다.
- "길찾기"는 현재 차원 origin에만 native locator-bar waypoint를 설정한다.
  다른 차원의 포털은 방향/차원 설명만 보이고, 원격 청크 로딩이나 원격 waypoint를
  만들지 않는다.
- "사용"은 포털 origin 8블록 이내에서만 버튼을 활성화한다. 클릭 시에도 기존
  `PortalTravelService.travel` 하나만 호출해 거리·쿨다운·전투·보호·안전 도착을
  다시 확인한다. 명령어는 복구/자동화 fallback으로 남긴다.
- 현재 도메인의 포털 정의는 모두 공개이므로 첫 릴리스에는 서버가 정의한 포털을
  모두 보인다. 검색은 입구·목적지 세계 이름의 64자 이하 서버 필터, 목록은
  최대 36개/페이지와 전체 64개 정의 스캔으로 한정한다.

**위험과 완화.** 현재 공개 포털의 기술 위치가 일반 카드에 드러나면 플레이 경험을
해칠 수 있다. 목적지 좌표·보호 구역·raw ID는 기본 카드에서 숨기고 기존 고급 정보
토글에서만 명시적으로 확인하게 한다. 향후 제한 포털 정책이 생기면 목록 projection
단계에서 먼저 적용해야 한다. 카드가 열려 있는 동안 포털 정의나 보호가 바뀌면
selection snapshot을 무효화한다. 위조 클릭은 항상 서버의 실제 플레이어 위치에서
실패해야 하며, 이미 존재하는 duplicate transaction·감사 경로를 그대로 사용한다.
이 후보는 **원격 텔레포트가 아니라 현장 사용을 찾고 안내하는 UI**라는 경계를
유지한다.

### 2. 일일·주간 의뢰 게시판

**플레이어 체감 가치.** 접속 후 한 번의 인벤토리 열기로 오늘 할 일을 고르고,
채광·사냥·상점 거래·보스·토지 활동을 짧은 목표로 엮는다. Hypixel의 공식 패치가
반복 의뢰의 진행 확인과 하루 첫 4회 추가 보상을 묶은 것처럼, 작업별 진행도·보상·
갱신 시각을 한 화면에 보이면 신규/복귀 플레이어 모두 다음 행동을 바로 알 수 있다
([공식 SkyBlock 0.11 패치 노트](https://hypixel.net/threads/skyblock-patch-0-11-dwarven-mines.3749492/)). 고정 서사 퀘스트를
대체하지 않고 반복 가능한 보조 루프가 된다.

**재사용할 기존 서비스.** `QuestProgressService`의 서버 관측 결과와 중복 보상
방지, `QuestDefinitionSnapshot`의 검증/리로드, `EconomyService`,
`ActivityXpAwardService`, `BossRewardService`, `PlayerQuestMenu` 및 전용 인벤토리
입력/페이지 UI를 재사용한다. Jobs/AuraSkills가 활동·직업 보상에 집중하는 것과
달리, 이 기능은 새 XP 이벤트나 새 화폐를 만들지 않는다
([Jobs Reborn](https://github.com/Zrips/Jobs),
[AuraSkills 변경 기록](https://github.com/Archy-X/AuraSkills/blob/master/Changelog.md#210)).

**최소 구현 경계.**

- 서버 데이터팩에 `contracts/` 정의를 추가한다. 종류는 기존 퀘스트가 이미
  검증하는 `activity`, `shop_trade`, `boss_defeat`, `claim_purchase`만 허용한다.
- 기간은 서버 시간이 결정한 일일 또는 주간 창 하나뿐으로 제한한다. 창마다
  플레이어에게 최대 3개를 결정적으로 배정하고, 목록은 28개 이하/페이지네이션
  이하로 보낸다.
- `Journey` 안에 "의뢰" 하위 화면을 추가한다. 수락, 진행, 보상 수령은 모두
  서버가 계약 창·정의 revision·진행 스냅샷을 재검증한다. 보상은 통화와 기존
  활동 XP만 지원한다.
- 월간 연속 출석, 무작위 뽑기, 플레이어 간 교환, 전용 NPC, 외부 DB는 이번
  범위에서 제외한다.

**위험과 완화.** 시간대/시계 변경은 같은 창을 다른 창으로 판정하거나 보상을
중복 지급할 수 있다. UTC 기반의 명시적 window ID, 완료 영수증의 결정적
transaction ID, 재시작 복구 테스트가 필요하다. 단순 블록 파괴 의뢰는 자동화
농장/반복 행위에 악용될 수 있으므로 이미 갖춘 보호 구역·쿨다운·서버 관측 정책을
우회하지 않고, 첫 릴리스의 목표 종류도 위 네 가지로 좁힌다.

### 3. 탐험 기록과 목적지 추적

**플레이어 체감 가치.** 발견하지 못한 지역/명소를 "??"로 남기고, 찾은 장소의
짧은 설명·진행도·다음 목적지를 보여 주면 서바이벌 월드에도 RPG 탐험의 이유가
생긴다. Wynncraft는 발견에 XP를 주며, Content Book의 콘텐츠를 선택해 표식으로
추적할 수 있다
([발견 보상](https://wynncraft.wiki.gg/wiki/World_Discoveries),
[추적 UI](https://wynncraft.wiki.gg/wiki/King%27s_Recruit)).

**재사용할 기존 서비스.** `RpgActivityEvents`의 서버 위치/advancement 관측,
`QuestPlayerSavedData`의 버전 있는 플레이어 증거 저장 방식, `PlayerQuestMenu`의
상태 카드, 그리고 #111 토지 지도에서 만든 현재 차원 locator-bar waypoint 송신과
선택 갱신 규칙을 재사용한다. 이는 새 원격 청크 로딩이나 좌표 명령을 요구하지
않는다.

**최소 구현 경계.**

- 데이터팩 정의는 `id`, 현재 차원, 하나의 블록 위치 또는 작은 반경, 제목/설명
  번역 키, 선택적 활동 XP 보상만 가진다. 위치/차원/반경/정의 수에 상한을 둔다.
- 서버가 플레이어의 실제 위치 진입만 기록한다. 한 플레이어당 발견 상태와
  결정적 보상 영수증을 보존하고, UI는 발견 수/지역별 목록/현재 차원에 한한
  "추적"만 보인다.
- 첫 배포는 12~24개 Hub·Wilderness 명소, 보상 없는 기록 또는 활동 XP만으로
  시작한다. 텔레포트, 무한 수집물, 비밀 좌표의 전체 공개, 외부 지도는 제외한다.

**위험과 완화.** 위치 정의를 클라이언트에 전송하면 스포일러가 된다. 미발견
항목에는 이름/정확 좌표를 보내지 않고, 추적은 이미 발견했거나 공개 허용된 항목만
가능하게 한다. 빠른 이동/차원 전환은 위치 이벤트를 여러 번 만들 수 있으므로,
정확한 한 번짜리 영수증과 서버 위치 재확인이 필요하다. 이 경계는
Wynncraft의 발견이 레벨/지역 조건을 둘 수 있다는 공개 사례와도 맞는다
([공식 XP 문서](https://wynncraft.wiki.gg/wiki/XP)).

## 지금 만들지 않을 항목

- **플레이어 상점·경매장·바자·외부 DB·웹 대시보드:** 현재 deferred 범위다.
  Hypixel은 플레이어 간 대량 상품 거래와 수요·공급 가격을 다루는 별도 Bazaar를
  운영하지만 ([공식 SkyBlock 0.7.7 Bazaar 패치](https://hypixel.net/threads/skyblock-patch-0-7-7-bazaar.2655146/)), 이는 Rovenfall의
  서버 소유 상점/감사 장부와 별개의 시장 설계다.
- **새 직업/마나/장비 스탯 계층:** 활동·커리어·패시브/액티브 스킬이 있으므로
  중복이다. AuraSkills의 직업/스킬 수입 모델은 비교 참고이지 이식 대상이 아니다
  ([공식 변경 기록](https://github.com/Archy-X/AuraSkills/blob/master/Changelog.md#210)).
- **원격 텔레포트:** 포털 탐색기는 origin까지의 길찾기와 현장 사용만 제공한다.
  다른 차원에서 포털을 작동시키거나, 원격 청크를 읽거나, UI 선택만으로 이동시키는
  기능은 제외한다.

## 다음 Issue에 넣을 완료 기준 (후보 1)

1. 인벤토리에서 서버 포털을 자연어 목적지와 현재 차원 거리로 보고, raw ID나
   좌표를 일반 화면에서 다루지 않고 길찾기를 켜거나 현장에서 사용할 수 있다.
2. 길찾기는 현재 차원 origin에만 표식을 만들고, 목록·검색·페이지·선택은 모두
   서버가 한정하고 정의 변경을 재검증한다.
3. 포털 사용은 실제 origin 8블록 이내의 서버 위치에서만 기존
   `PortalTravelService`를 호출한다. 위조 클릭, 정의/보호 변경, 쿨다운, 전투 잠금,
   안전하지 않은 도착은 이동하지 않는다.
4. 원격 텔레포트, 원격 청크 로딩, 목적지 좌표/보호 구역/raw ID의 기본 화면 노출, 새
   화폐·플레이어 거래는 추가하지 않는다.
5. 한국어/영어/일본어가 같은 키 집합을 가지며, 한국어 UI는 자연스러운 게임
   용어를 사용한다. 집중 단위 테스트, 전체 테스트, GameTest, 로컬라이즈 검사,
   배포 JAR 검사를 통과한다.
