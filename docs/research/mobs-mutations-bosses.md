# Milestone 5 — 몹·변이·보스 공식 1차 자료 조사

조사일: 2026-09-02
대상 버전: 저장소의 `gradle.properties`에 고정된 Minecraft 26.2 / NeoForge 26.2.0.66. NeoForge API 이름과 동작은 [NeoForge `26.2.x` 공식 소스](https://github.com/neoforged/NeoForge/tree/26.2.x)로 교차 확인했다.

## 범위와 자료 기준

이 문서는 Milestone 5의 구현 결정을 위한 조사만 다룬다. 외부 근거는 NeoForge 공식 문서·공식 GitHub 저장소와 비교 대상 모드가 직접 관리하는 공식 GitHub 저장소/프로젝트 문서만 사용했다. 블로그, 위키 복제본, 포럼, 2차 해설은 사용하지 않았다. Rovenfall 고유 요구는 [도메인 모델](../../.agents/skills/rovenfall-development/references/domain-model.md#Mobs-mutations-and-bosses), [불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Experience-and-rewards), [로드맵](../../.agents/skills/rovenfall-development/references/roadmap.md#milestone-5-mobs-mutations-and-boss-encounters)을 기준으로 삼는다.

## 결론 요약

1. **일반 몹은 `EntityType`·기본 속성·생물군계 스폰 항목·배치 판정을 별도 데이터/등록 seam으로 둔다.** `MobCategory`가 자연 스폰 cap과 despawn 성질을 함께 결정하므로, Wilderness의 적대 몹은 별도 카테고리를 새로 만들지 말고 `MONSTER`에 넣고 `add_spawns`의 weight/pack과 `add_spawn_costs`를 조절하는 것이 맞다. [NeoForge 엔티티/카테고리 공식 문서](https://docs.neoforged.net/docs/entities/), [26.2.x `AddSpawnsBiomeModifier` 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/common/world/BiomeModifiers.java)

2. **기존 몹 변이는 새 엔티티 타입으로 복제하지 않고, 자연 Wilderness 스폰의 최종화 시점에 선택된 데이터 조합을 서버가 한 번 적용하고 그 `mutation_id`를 엔티티 저장 데이터에 남긴다.** Apotheosis의 공식 변경 이력은 Invader/Elite의 조건을 `SpawnCondition`으로 분리하고, 다중 속성 affix와 데이터 맵 기반 spawn rule을 채택한 선례를 제공한다. [Apotheosis 공식 변경 이력](https://github.com/Shadows-of-Fire/Apotheosis/blob/1.21/changelog.md)

3. **보스는 “엔티티 AI”와 별개인 영속 `Encounter` 상태 기계가 소유해야 한다.** 보호 경기장, 패턴 전환, 참여자/기여도, 보상 영수증, 쿨다운, 복구는 보스 엔티티 하나의 휘발 필드에 두면 재시작·중복 지급을 막을 수 없다. 패턴 학습형 전투/전용 경기장은 Mowzie's Mobs의 공식 소스가, 반경 leash·재시작 시 기존 보스 채택·영속 쿨다운은 POIs and Raid Bosses의 공식 프로젝트 문서가 뒷받침한다. [Mowzie's Mobs 공식 저장소](https://github.com/bobmowzie/MowziesMobs-Public), [POIs and Raid Bosses 공식 문서](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki)

4. **개인 보상은 마지막 타격이나 클라이언트 보고가 아니라 서버가 누적한 유효 기여도로 판정하고, 보상 지급·쿨다운·감사를 단일 커밋으로 처리한다.** 이는 Rovenfall의 “마지막 타격만으로 소유권을 주지 않고 contribution을 사용”한다는 불변식과 직접 일치한다. [Rovenfall 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Experience-and-rewards)

## 1. NeoForge/Minecraft 26.2: 커스텀 엔티티, 속성, 자연 스폰과 제한

### 확인된 공식 API 경계

| 관심사 | 공식 사실 | Milestone 5 결정 |
| --- | --- | --- |
| 타입 등록 | `EntityType`은 타입 공통 속성을 가진 singleton이고, 실제 동작은 entity instance subclass에 둔다. 공식 예제는 `DeferredRegister.Entities`와 `EntityType.Builder`를 사용한다. [NeoForge 엔티티 문서](https://docs.neoforged.net/docs/entities/) | `RovenfallEntityTypes`에서 두 일반 몹과 보스를 명시적으로 등록한다. 타입 ID, hitbox, tracking/update 설정은 등록 시 고정하고, 전투 수치·스폰 수치는 데이터로 분리한다. |
| 생물 카테고리 | `MobCategory`는 자연 스폰 cap·friendly/persistent·despawn 성질을 결정하며, `MISC`는 자연 스폰되지 않는다. 새 카테고리는 별도 스폰 메커니즘도 필요하다. [NeoForge 엔티티/카테고리 문서](https://docs.neoforged.net/docs/entities/#mobcategory) | 일반 적대 몹은 `MONSTER`를 사용한다. 보스는 자연 스폰 목록에 넣지 않고 `MISC`로 우회하지도 않으며, encounter service가 명시적으로 생성한다. |
| 기본 속성 | `LivingEntity`는 spawn 시 사용할 기본 attribute set을 등록해야 하며 `EntityAttributeCreationEvent`가 등록 지점이다. [NeoForge 속성 문서](https://docs.neoforged.net/docs/entities/attributes/#default-attributes) | 각 커스텀 living mob은 `MAX_HEALTH`, `MOVEMENT_SPEED`, `ATTACK_DAMAGE`, `FOLLOW_RANGE`, 방어 관련 값만 기본값으로 등록한다. 보스 phase/변이 보정은 이 기본값을 덮어쓰지 않고 식별 가능한 attribute modifier로 더한다. |
| 생물군계 스폰 | `neoforge:add_spawns`는 biome/tag와 entity type, weight, min/max pack으로 자연 스폰 후보를 추가한다. 26.2.x 구현은 이 entry를 entity category의 biome spawn list에 추가한다. [26.2.x biome modifier 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/common/world/BiomeModifiers.java) | `data/rovenfall/neoforge/biome_modifier/`에 **Wilderness 전용 biome tag**만 참조하는 JSON을 둔다. Hub biome 또는 범용 Overworld tag를 사용하지 않는다. |
| 배치 안전성 | 신규 엔티티의 자연 스폰은 `RegisterSpawnPlacementsEvent`에 placement type·heightmap·predicate를 등록해야 하며, predicate는 자연 스폰 때 검사된다. [26.2.x 이벤트 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent.java) | 일반 몹마다 지상/물 등 맞는 `SpawnPlacementType`, heightmap, 빛·블록·충돌 predicate를 등록한다. biome 목록만으로 공중 낙사나 부적절한 표면을 막는다고 가정하지 않는다. |
| 개체 수 압력 | `add_spawn_costs`는 entity type별 charge/energy budget을 biome spawn settings에 추가한다. [26.2.x biome modifier 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/common/world/BiomeModifiers.java) | 희귀·강한 일반 몹은 weight만 낮추지 말고 spawn cost도 데이터로 부여한다. 이는 category cap을 대체하는 것이 아니라 해당 몹의 군집 압력을 추가로 제한한다. |
| 최종 스폰 관문 | `FinalizeSpawnEvent`는 `Mob#finalizeSpawn` 직전에 논리 서버에서 발생하며, spawn reason을 조회하고 실제 spawn 취소도 할 수 있다. [26.2.x `FinalizeSpawnEvent` 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent.java) | 자연/청크 생성 reason과 Wilderness dimension을 다시 확인하는 최종 방어선을 둔다. Hub에서 변이를 적용하지 않고, 정의 오류 등으로 Hub 자연 스폰이 시도되면 변이 없이 두는 것이 아니라 해당 Rovenfall 일반 몹 spawn을 취소한다. |

### 권장 스폰 파이프라인

`BiomeModifier 후보 선정 → vanilla/NeoForge placement predicate → FinalizeSpawnEvent의 dimension·spawn reason 재검증 → 일반 몹 초기화 또는 변이 조합 → world 추가` 순서를 사용한다. 생물군계 데이터는 **어디에서 얼마나 자주 후보가 되는가**, placement predicate는 **그 좌표가 물리적으로 가능한가**, finalization은 **서버 정책상 실제 허용되는가**를 각각 맡는다. 이 분리는 `RegisterSpawnPlacementsEvent`가 자연 스폰 predicate용이라는 점과 `FinalizeSpawnEvent`가 서버에서 spawn reason을 제공한다는 점에서 나온다. [배치 이벤트](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent.java), [최종화 이벤트](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent.java)

`/summon`, 관리자 보스 시작, 구조물/스포너, 자연 스폰을 같은 정책으로 뭉개지 않는다. `FinalizeSpawnEvent`가 reason과 spawner 원인을 제공하므로, 자연 Wilderness만 mutation 후보로 두고 보스/관리자 생성은 encounter service의 명시적 경로만 허용한다. [26.2.x `FinalizeSpawnEvent` 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent.java)

## 2. 기존 몹의 데이터 조합형 Wilderness 변이

### 비교 대상에서 확인한 패턴

Apotheosis는 공식 변경 이력에서 Invader/Elite의 exclusion을 논리 연산자를 지원하는 `SpawnCondition`으로 교체했고, multi-attribute affix, 데이터 맵 기반 Invader spawn rule, 데이터로 조절하는 affix 수치를 명시한다. 즉 “기존 몹의 타입을 다시 구현”하는 방식보다 **선택 조건 + 조합 가능한 효과 + 데이터 검증**을 분리하는 방향의 실제 선례다. [Apotheosis 공식 변경 이력: `SpawnCondition`·multi-attribute](https://github.com/Shadows-of-Fire/Apotheosis/blob/1.21/changelog.md), [Apotheosis 공식 변경 이력: data-driven affix](https://github.com/Shadows-of-Fire/Apotheosis/blob/1.21/changelog.md)

이 선례는 Rovenfall의 정의와도 정확히 맞는다. Rovenfall은 mutation을 기존 몹에 합성하는 attribute/AI/visible marker/spawn condition/reward change의 묶음으로 정의하고, Wilderness에서만 낮은 비율로 허용한다. [Rovenfall 도메인 모델](../../.agents/skills/rovenfall-development/references/domain-model.md#Mobs-mutations-and-bosses)

### 권장 정의 모델

`MutationDefinition`은 다음처럼 **선택**과 **효과**를 분리한다. 임의 Java class 이름이나 스크립트 문자열은 허용하지 않고, 효과 종류는 등록된 식별자만 받는다. 이는 재로드 시 전체 snapshot 검증을 요구하는 Rovenfall 불변식과, 재사용 가능한 typed effect를 권장하는 기존 skill-tree 원칙을 그대로 따른다. [Rovenfall 정의/리로드 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Definitions-and-reload), [Rovenfall 도메인 모델의 typed effect 원칙](../../.agents/skills/rovenfall-development/references/domain-model.md#Activities-careers-and-skills)

```json
{
  "id": "rovenfall:ashen",
  "eligible_entity_types": "#rovenfall:wilderness_mutation_eligible",
  "spawn_conditions": {
    "dimension": "rovenfall:wilderness",
    "spawn_reasons": ["NATURAL", "CHUNK_GENERATION"],
    "biomes": "#rovenfall:ashen_mutation_biomes",
    "min_local_difficulty": 1.5
  },
  "weight": 12,
  "effects": [
    {"type": "attribute_modifier", "attribute": "minecraft:generic.max_health", "operation": "add_multiplied_total", "value": 0.25},
    {"type": "attribute_modifier", "attribute": "minecraft:generic.movement_speed", "operation": "add_multiplied_total", "value": 0.10},
    {"type": "equipment_or_effect", "id": "rovenfall:ashen_marker"},
    {"type": "reward_profile", "id": "rovenfall:ashen_rewards"}
  ]
}
```

위 JSON은 제안 스키마이며 외부 모드 포맷의 복제가 아니다. 핵심 권고는 다음과 같다.

- **eligible tag가 먼저다.** mutation은 `Mob` 전체나 class 이름이 아니라 entity-type tag에만 적용한다. 다른 모드 몹·보스·길들인 몹을 우발적으로 바꾸지 않는 가장 작은 호환성 경계다. Apotheosis도 8.x에서 registry/tag를 이용해 기존 범주 전반에 확장 가능한 방향을 채택했다. [Apotheosis 공식 변경 이력: holder set/tag 기반 범주](https://github.com/Shadows-of-Fire/Apotheosis/blob/1.21/changelog.md)

- **조건 → 가중 선택 → 효과 적용을 한 번만 실행한다.** `FinalizeSpawnEvent`는 server-only이고 spawn reason을 제공하므로, 이 시점에 `eligible + Wilderness + natural reason + condition`을 필터링하고 한 개만 가중 추첨한다. 추첨 뒤에는 `mutation_id`, definition revision, 적용한 modifier 식별자를 저장해 chunk unload/load나 다른 spawn hook에서 다시 중첩 적용하지 않는다. [26.2.x `FinalizeSpawnEvent` 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent.java), [NeoForge 엔티티 저장 훅 공식 문서](https://docs.neoforged.net/docs/entities/)

- **가시 표식은 선택 사항이 아니라 정의의 필수 검증 항목이다.** 이름 색·파티클·장비/효과 중 적어도 하나를 요구해 플레이어가 변이를 식별할 수 있게 한다. 이는 Rovenfall 정의에 명시된 visible marker 요구를 충족하며, 전투 난이도 변화가 보이지 않는 상태를 피한다. [Rovenfall 도메인 모델](../../.agents/skills/rovenfall-development/references/domain-model.md#Mobs-mutations-and-bosses)

- **보상은 엔티티 드롭 이벤트에서 즉석 난수로 덧붙이지 않는다.** `reward_profile`을 mutation에 참조시키고, 사망 시 서버가 mutation ID와 기여/kill context를 읽어 결정한다. 서버가 loot/reward를 소유하고 완료된 서버 관측 결과만 보상해야 한다는 불변식을 지킨다. [Rovenfall 권한 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Authority-and-validation), [Rovenfall 보상 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Experience-and-rewards)

### 변이 정의 검증과 운영 한계

재로드 전 snapshot에서 중복 ID, 빈 eligible set, Hub dimension 조건, 0 이하 weight, 없는 attribute/effect/reward profile, 범위를 벗어난 수치, marker 부재를 거부한다. 전체 후보를 먼저 파싱·검증한 뒤에만 swap해야 한다는 요구는 Rovenfall의 정의 reload 불변식에 따른 것이다. [Rovenfall 정의/리로드 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Definitions-and-reload)

또한 entity type의 기본 `MobCategory` cap 위에 mutation 자체의 per-chunk/nearby-player 상한을 별도 둔다. cap은 같은 category 전체에 걸리고, 가중치만으로는 특정 희귀 변이가 군집화되는 것을 보장하지 못한다. category cap의 역할과 `add_spawn_costs`의 별도 비용 모델은 NeoForge 공식 문서/소스에서 확인된다. [NeoForge `MobCategory` 문서](https://docs.neoforged.net/docs/entities/#mobcategory), [26.2.x spawn cost 공식 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/common/world/BiomeModifiers.java)

## 3. 보호 경기장 다중 패턴 보스

### 비교 대상에서 확인한 패턴

Mowzie's Mobs는 공식 저장소에서 보스를 “전용 경기장/은신처”, “관찰 가능한 고유 공격 패턴”, “스탯 검사보다 패턴 학습”으로 명시한다. 따라서 Milestone 5의 다중 패턴은 단순히 health가 낮아질수록 attack damage를 올리는 것이 아니라, 플레이어가 알아차릴 수 있는 telegraph와 서로 다른 행동 집합을 가진 상태 전환이어야 한다. [Mowzie's Mobs 공식 README](https://github.com/bobmowzie/MowziesMobs-Public)

POIs and Raid Bosses의 공식 문서는 보스 시작 전 countdown, 등장 중 무적/고정, 보스 체력 bar, 반경 이탈 시 home teleport, 재시작/청크 reload에서 기존 guardian 채택, 재시작 이후에도 남는 쿨다운을 설명한다. 이는 엔티티 단독 AI보다 encounter lifecycle을 영속으로 모델링해야 할 직접적인 운영 근거다. [POIs and Raid Bosses 공식 문서: Boss/Guard lifecycle](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki)

### 권장 encounter 상태 기계

`BossEncounterState`를 `PlatformSavedData` 또는 boss 전용 versioned `SavedData`가 소유하고, boss entity에는 encounter ID와 동기화가 필요한 현재 phase/telegraph만 둔다. Rovenfall은 전역 경제/진행 상태를 resettable Wilderness 밖에 저장해야 하며, 정의·런타임·표현을 분리한다. [Rovenfall persistence/ownership 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Persistence-and-migration), [Rovenfall ownership 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Ownership-and-boundaries)

```text
ARMED
  └─ start 승인 → INTRO (경기장 잠금·telegraph·무적)
                       └─ intro 종료 → ACTIVE_PHASE_1
                                             └─ HP/시간/기믹 조건 → TRANSITION
                                                                           └─ ACTIVE_PHASE_2 …
ACTIVE_* ── 사망 → REWARD_COMMITTING ── 원자 커밋 → COOLDOWN
ACTIVE_* ── 모든 참여자 이탈·관리자 취소·엔티티 유실 → FAILED 또는 RECOVERING
RECOVERING ── 기존 UUID 재연결/재생성 판정 → 이전 활성 상태 또는 FAILED
COOLDOWN ── deadline 도달 → ARMED
```

각 상태 전환은 정의 ID/revision, encounter ID, 경기장 dimension·중심·반경, boss UUID, phase, 시작/전환 deadline, 참여자와 기여도, 보상 영수증, 쿨다운 deadline을 포함한 하나의 서버 상태 변경이다. 이 구분은 protected region이 player claim보다 우선한다는 Rovenfall 모델과, lifecycle/reward를 감사해야 한다는 불변식에 따른다. [Rovenfall protected region 모델](../../.agents/skills/rovenfall-development/references/domain-model.md#Platform-and-identity), [Rovenfall lifecycle audit 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Audit-monitoring-and-recovery)

### 패턴 설계 최소 기준

| 패턴 | 서버가 판정할 조건 | 플레이어에게 관찰 가능한 신호 | 보호 경기장 안전 규칙 |
| --- | --- | --- | --- |
| `SWEEP` | 목표 거리/시야/내부 cooldown | 준비 자세·음향·짧은 wind-up | 반경 밖 대상은 타격하지 않고, block damage는 protected-region 정책을 통과한 경우만 허용 |
| `CHARGE` | 직선 경로·충돌 여유·대상 위치 | 방향 고정·파티클·돌진 전 delay | arena 경계를 넘으면 피해 없이 정지/귀환 |
| `SUMMON` 또는 `HAZARD` | phase·최대 minion/위험물 수·내부 cooldown | phase transition·명확한 소환 지점 표시 | minion/위험물도 encounter ID를 가져 종료·실패·복구 시 함께 정리 |

세 패턴은 권장 최소 세트이지 외부 모드의 행동을 복제하라는 뜻이 아니다. **패턴마다 준비 신호, 서버 cooldown, 취소/복구 규칙을 정의 데이터에 포함**해야 한다는 결론은 Mowzie's Mobs가 패턴 학습형 보스를 표방하고, POIs 프로젝트가 등장 중 고정/무적·반경 leash를 운영하는 점에서 나온다. [Mowzie's Mobs 공식 README](https://github.com/bobmowzie/MowziesMobs-Public), [POIs and Raid Bosses 공식 문서](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki)

경기장은 기존 `ProtectedRegion`의 administrator-owned 영역으로 등록한다. boss가 블록을 부수거나 화염/폭발/유체를 만들 수 있다면, 일반 claim 보호를 우회하는 개별 event handler를 추가하지 말고 이미 정한 보호 우선순위에서 encounter 권한을 명시적으로 판정한다. 보호 영역은 player claim permission을 override한다는 프로젝트 모델 및 실제 영향을 받는 모든 위치를 검사해야 한다는 불변식이 근거다. [Rovenfall protected region 모델](../../.agents/skills/rovenfall-development/references/domain-model.md#Platform-and-identity), [Rovenfall protection 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Authorization-and-protection)

## 4. 서버 계산 기여도, 개인 보상, 쿨다운과 감사

### 기여도 모델

최초 버전은 설명 가능한 서버 누적치로 제한한다.

```text
contribution(player) =
  유효하게 보스에 가한 실제 피해량
  + 정의로 허용한 목표/기믹 기여량
  + 정의로 허용한 아군 보호·회복 기여량
```

“유효”는 encounter가 `ACTIVE_*` 상태이고, 플레이어가 경기장 안에 있으며, 서버가 관측한 피해/행동이고, 같은 피해 이벤트가 재처리되지 않았다는 뜻이다. 클라이언트가 숫자를 보내지 않으며 마지막 타격은 별도 보너스가 될 수 있어도 eligibility의 단독 조건이 될 수 없다. 이는 Rovenfall의 server authority와 “combat credit per target을 cap하고 boss reward는 contribution을 사용, last hit alone은 불충분”이라는 명시 불변식이다. [Rovenfall authority 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Authority-and-validation), [Rovenfall 보상 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Experience-and-rewards)

초기 eligibility는 `totalContribution > 0` 및 `playerContribution / totalContribution >= configuredMinimumShare`로 계산하고, 최소 실제 피해 또는 최소 전투 참여 시간 중 하나를 추가 안전 조건으로 둔다. 이 수식은 제안이며, 값은 data definition에 둔다. 완료된 서버 관측 결과만 보상하고 구성 가능한 상한/이상 징후를 적용해야 한다는 Rovenfall 불변식 때문에, 회복/기믹 기여에는 per-encounter cap과 이유 코드를 함께 둔다. [Rovenfall 보상·모니터링 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Experience-and-rewards), [Rovenfall performance 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Performance)

### 개인 보상 트랜잭션

보스 사망 시 `BossRewardService`가 eligibility snapshot을 한 번 고정한 뒤, 각 플레이어마다 아래를 **원자적으로** 수행한다.

1. encounter ID, boss definition revision, player UUID, 기여도와 threshold를 다시 확인한다.
2. 해당 보상 profile의 player cooldown deadline을 확인한다.
3. reward receipt가 이미 있으면 같은 결과를 반환하고, 없으면 서버가 loot/currency/activity XP를 산출한다.
4. 보상 receipt, cooldown deadline, 필요 경제 변경, audit entry를 함께 커밋한다.

재시도 가능한 다중 도메인 작업에는 transaction ID를 부여해 중복 보상/중복 쿨다운을 막아야 한다는 원칙과, 보스 lifecycle/reward를 감사해야 한다는 요구가 이 경계의 근거다. [Rovenfall atomic-operation 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Atomic-operations), [Rovenfall audit 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Audit-monitoring-and-recovery)

POIs and Raid Bosses는 cooldown을 POI에 영속 저장해 restart/downtime 뒤에도 유지하고, guard 재무장에도 사용한다. Rovenfall도 플레이어별 `nextEligibleAt`을 versioned saved state에 저장한다. 단, POI 전역 쿨다운과 개인 보상 쿨다운을 같은 필드로 합치지 않는다. 전자는 encounter 재시작 제한, 후자는 보상을 이미 받은 플레이어의 반복 farming 제한이라는 서로 다른 권한/감사 대상이다. [POIs and Raid Bosses 공식 cooldown 문서](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki)

### 감사와 복구 표면

아래 action type은 append-only audit에 남긴다. 각 항목에는 timestamp, actor/대상 UUID, encounter/definition ID, dimension·position, before/after, reason, transaction ID를 포함한다. 이는 Rovenfall audit entry의 최소 필드와 30일 retention 정책을 따른다. [Rovenfall audit 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Audit-monitoring-and-recovery)

| action type | 언제 기록하는가 | 핵심 before/after |
| --- | --- | --- |
| `BOSS_ARMED`, `BOSS_START_DENIED`, `BOSS_STARTED` | 시작 승인/거부/성공 | encounter 상태, triggerer, arena, 정의 revision, 거부 사유 |
| `BOSS_PHASE_CHANGED`, `BOSS_BOUNDARY_RETURNED` | phase 전환·arena leash | phase, boss UUID, 원인/목표 위치 |
| `BOSS_FAILED`, `BOSS_RECOVERED`, `BOSS_DEFEATED` | 이탈·유실·재시작 복구·처치 | 이전/다음 lifecycle 상태, 참여자 수, elapsed time |
| `BOSS_REWARD_GRANTED`, `BOSS_REWARD_DENIED` | 개인 reward commit/비적격 | contribution, threshold, cooldown before/after, receipt/transaction ID, 거부 사유 |
| `BOSS_COOLDOWN_STARTED`, `BOSS_COOLDOWN_EXPIRED` | encounter/player cooldown 변경 | cooldown scope, deadline before/after |

재시작에서는 저장된 encounter가 가리키는 chunk/arena를 확인하고, 저장 UUID의 boss entity를 찾는다. 존재하면 encounter ID·definition revision·phase가 일치할 때만 재연결하고, 없거나 불일치하면 새 보스를 무조건 생성하지 말고 `RECOVERING → FAILED` 또는 명시적 관리자 복구로 끝낸다. 기존 guardian을 재시작/청크 reload에서 채택해 중복을 피하는 POIs 사례와, 실패 시 prior state를 보존해야 하는 Rovenfall atomic/persistence 원칙이 이 결정을 뒷받침한다. [POIs and Raid Bosses 공식 Guard lifecycle 문서](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki), [Rovenfall persistence 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Persistence-and-migration)

## 5. 권장 구현 순서와 자동 검증

1. **일반 몹 vertical slice:** entity registry, default attributes, Wilderness-only biome modifier, placement predicate, Hub final-spawn 차단을 먼저 GameTest로 고정한다. [NeoForge 엔티티 문서](https://docs.neoforged.net/docs/entities/), [26.2.x placement 이벤트](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent.java)
2. **mutation catalog + applicator:** immutable definition snapshot 검증, eligible tag, condition/weight 선택, 한 번 적용되는 저장 marker, visible marker, reward profile 연결을 구현한다. [Apotheosis 공식 변경 이력](https://github.com/Shadows-of-Fire/Apotheosis/blob/1.21/changelog.md), [Rovenfall 정의 reload 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Definitions-and-reload)
3. **encounter lifecycle:** protected arena 등록, `ARMED → INTRO → ACTIVE → ...` 상태/재시작 복구/audit을 boss AI보다 먼저 만든다. [POIs and Raid Bosses 공식 lifecycle 문서](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki), [Rovenfall audit 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Audit-monitoring-and-recovery)
4. **패턴과 contribution:** 최소 세 관찰 가능 패턴의 server cooldown/leash/cleanup, damage ledger, threshold evaluation, receipt/cooldown atomic commit을 붙인다. [Mowzie's Mobs 공식 README](https://github.com/bobmowzie/MowziesMobs-Public), [Rovenfall 보상 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Experience-and-rewards)

Milestone 5 exit에 직접 대응하는 최소 자동 검증 목록은 다음과 같다.

- 일반 몹은 Wilderness의 허용 biome/표면에서만 자연 스폰 후보가 되고 Hub에서는 biome data와 finalization 양쪽에서 spawn되지 않는다.
- 대상 tag 밖, Hub, `/summon`/관리자 생성, 비자연 spawn reason에서는 mutation이 적용되지 않는다. eligible Wilderness natural spawn은 정확히 하나의 mutation 또는 none만 저장한다.
- arena 밖 target, 경계 밖 boss, phase transition, minion/hazard cleanup, intro 중 공격 불가를 서버 GameTest로 검증한다.
- 마지막 타격만 한 플레이어는 threshold에 미달하면 보상을 못 받고, qualifying player는 재전송/재시작 뒤에도 한 encounter당 한 receipt만 받는다.
- player/encounter cooldown과 encounter lifecycle은 save/load 뒤 유지되며, boss entity 유실은 중복 재생성·중복 보상 없이 감사된다.
- 각 성공/거부/복구/보상 audit의 action, actor/target, 위치, before/after, reason, transaction ID를 단위 테스트로 검증한다. 이 항목들은 Rovenfall의 Milestone 5 exit 및 state-changing feature 검증 규약을 구체화한 것이다. [Milestone 5 exit](../../.agents/skills/rovenfall-development/references/roadmap.md#milestone-5-mobs-mutations-and-boss-encounters), [Rovenfall verification 불변식](../../.agents/skills/rovenfall-development/references/invariants.md#Verification)

## 1차 출처 목록

- [NeoForge 공식 엔티티 문서](https://docs.neoforged.net/docs/entities/)
- [NeoForge 공식 속성 문서](https://docs.neoforged.net/docs/entities/attributes/)
- [NeoForge 26.2.x `RegisterSpawnPlacementsEvent` 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent.java)
- [NeoForge 26.2.x `FinalizeSpawnEvent` 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent.java)
- [NeoForge 26.2.x biome modifier 소스](https://github.com/neoforged/NeoForge/blob/26.2.x/src/main/java/net/neoforged/neoforge/common/world/BiomeModifiers.java)
- [Apotheosis 공식 저장소 및 변경 이력](https://github.com/Shadows-of-Fire/Apotheosis/blob/1.21/changelog.md)
- [Mowzie's Mobs 공식 소스 저장소](https://github.com/bobmowzie/MowziesMobs-Public)
- [POIs and Raid Bosses 공식 프로젝트 문서](https://github.com/teamdemivfxfish-dev/pois-and-raid-bosses-wiki)
- [Rovenfall 도메인 모델](../../.agents/skills/rovenfall-development/references/domain-model.md)
- [Rovenfall 불변식](../../.agents/skills/rovenfall-development/references/invariants.md)
- [Rovenfall 로드맵](../../.agents/skills/rovenfall-development/references/roadmap.md)
