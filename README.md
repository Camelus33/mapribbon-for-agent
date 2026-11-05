# MapRibbon for Agent

**한 줄 설명**: 차마다 있는 에이전트가 도로 시설물에 기록(리본)을 남기고 서로 읽는 채널. 사람은 출발 전에 그 경로를 지도 한 장으로 미리 본다.

현대자동차그룹 차량용 앱 개발 공모전 출품작 · 팀 Tedin (Tedin Inc.)

## 무엇을 하나

- **에이전트를 위한 채널이다.** 차가 공공데이터·OpenStreetMap의 도로 시설물(터널, 분기점, 톨게이트, 단속카메라)이 영향을 주는 구간을 지나가면, 차 안의 에이전트가 **매듭**(`maptalk.knot/1`) 하나를 남긴다. 매듭은 그 구간에서 겪은 일을 숫자로 적은 기록이다(속도, 정지, 급제동, 분기 직전 방향지시등, 와이퍼·안개등).
- 매듭이 모이면 시설물마다 리본 상태(`maptalk.ribbon/1`)가 된다. 집계 창은 15분 → 1시간 → 3시간 순으로 넓히고, **서로 다른 차 3대 미만이면 공개하지 않는다.** 다른 에이전트(예: Gleo AI)는 [읽기 창구](#다른-에이전트가-읽는-법)로 리본을 가져간다.
- **"평소와 다름"은 평소값보다도, 같은 길의 이웃 시설물보다도 높을 때만** 표시한다. 그래서 도로 전체에 비가 오면 아무것도 켜지지 않고, 한 터널 출구에서만 와이퍼가 켜지면 그 터널만 켜진다.
- **내비를 대체하지 않는다. 사람이 보는 화면은 출발 전용이다.**
  - 경로 지도는 실제 도로 모양을 출발지(왼쪽 아래)에서 목적지(오른쪽 위)로 놓는다.
  - **도로가 얼마나 휘었는지를 선의 굵기와 진하기로 그린다.** 직선(반경 2 km 이상)은 가늘고 옅게, 굽은 길(500 m~2 km)은 굵고 진하게, 급커브(500 m 미만)는 가장 굵고 진하게 그린다. 반경은 경로 선형에서 계산한다(`ribbon-core` `geo/Curvature`).
  - 터널은 도로 밑에 옅은 띠로 깔린다.
  - **미리 달리기**는 점 하나가 경로를 약 30초 동안 달리며, 평소와 다른 곳마다 잠시 멈춰 카드를 보여 준다. 매듭은 남기지 않는다.
- **주행 중**(CarUxRestrictions)에는 화면에 지도, 내 위치, 버튼을 띄우지 않는다. "리본 기록 중"과 남긴 매듭 수만 보이고, 에이전트는 포그라운드 서비스로 기록을 이어 간다. 그동안 길 안내는 내비가 맡는다.
- **문장 대신 아이콘으로 보여 준다.**
  - 무엇이 달랐는지를 아이콘으로 표시한다. 와이퍼·안개등은 계기판 표준 기호(ISO 2575) 모양을 썼고, 급제동·늦은 끼어들기는 새로 그렸다.
  - 몇 대 중 몇 대인지는 `6/9`로 적고, 이번 값과 평소값은 막대 하나(이번 값은 막대, 평소값은 눈금)로 비교한다.
  - 상태는 모양으로도 구분한다. 조금 다름은 원, 크게 다름은 삼각형과 "!"이다. 빨강은 즉시 위험용으로 남겨 두고 쓰지 않는다.
  - 화면 문구는 시스템 언어를 따른다(한국어, 영어). 영어 시설물 이름은 OSM `name:en`을 쓰고, 없으면 로마자로 표기했다.

## APK 위치

`apk/Tedin_MapRibbonForAgent_release.apk` (release 빌드, 서명됨)

## 실행 방법 (Pleos Connect 에뮬레이터)

1. Pleos Connect v2.1.0 에뮬레이터를 **Cold boot**로 실행한다.
2. 설치: `adb install -g apk/Tedin_MapRibbonForAgent_release.apk`
3. 앱 목록에서 **MapRibbon for Agent**를 열고 위치·속도·알림 권한을 허용한다.
4. 오른쪽 위 **시연**을 누르고 `인제양양터널 너머 비, 서양양IC 직전 끼어들기`를 고른다. 시뮬레이션 차 9대가 서울양양고속도로(인제IC → 양양JC, 34.5km)를 지나가며 매듭을 남긴다.
5. 지도에는 커브가 몰린 21.7–28.9 km 구간이 굵게 보인다. 아이콘과 대수가 붙는 곳은 세 곳이다.
   - **인제양양터널 출구**: 와이퍼 6/9, 평소 3%
   - **서양양IC**: 늦은 끼어들기 4/9, 평소 8%
   - **단속카메라**: 급제동 3/8, 가상 카메라
6. **미리 달리기**를 누르면 점이 경로를 달리며 다음 곳까지 남은 거리와 카드를 보여 준다.
7. **시연 → 자세히**에는 마지막으로 쓴 매듭과 읽은 리본이 JSON 그대로 나온다.
8. **시연**에서 `대조군: 전 구간 비`를 고르면 와이퍼 사용은 100%로 기록되지만 평소와 다른 곳은 0곳이다.

한 줄 명령으로도 재현된다.

```
adb shell am start -n kr.tedin.maptalkdrive/.MainActivity --es demo_scenario local-weather --ez demo_preview true
```

### 주행 중 전환과 실제 차량 신호 (선택)

```
adb forward tcp:8088 tcp:8088
```

브라우저에서 http://127.0.0.1:8088 (Virtual Console)을 연다.
- 기어를 **D**로 놓으면 앱은 "리본 기록 중" 화면으로 바뀌고 누를 것이 없어진다. 기록 서비스(포그라운드, location 유형)가 시작된다. **P**로 돌리면 서비스가 멈추고 경로 지도로 돌아온다.
- 앞 와이퍼·방향지시등을 바꾸면 **시연 → 차량 연결** 칸에 바로 반영된다.

## 다른 에이전트가 읽는 법

리본은 읽기 전용 ContentProvider(`kr.tedin.maptalkdrive.ribbons`)로 열려 있다. 행마다 납작한 열과 함께 규격 JSON(`json` 열)이 들어 있다. 리본은 차 3대 이상을 모은 값이고 신원 정보가 없으므로 권한을 걸지 않았다.

| URI | 내용 |
|---|---|
| `content://kr.tedin.maptalkdrive.ribbons/ribbons` | 경로 위 시설물 전부, 주행 순서대로 (`maptalk.ribbon/1`) |
| `content://kr.tedin.maptalkdrive.ribbons/ribbons/<anchorId>` | 시설물 하나 |
| `content://kr.tedin.maptalkdrive.ribbons/next?along_m=12000` | 그 위치 앞에서 처음 나오는 평소와 다른 곳과 거리 |
| `content://kr.tedin.maptalkdrive.ribbons/route` | 경로의 굽은 정도와 시설물 (`maptalk.route/1`) |

AAOS에서는 운전자 사용자(에뮬레이터는 10번)로 물어야 같은 채널을 본다.

```
adb shell "content query --user $(adb shell am get-current-user | tr -d '\r') --uri 'content://kr.tedin.maptalkdrive.ribbons/next?along_m=12000'"
```

Gleo AI용 함수 세 개(`get_route_ribbons`, `get_next_unusual`, `get_route_shape`)는 `app/src/main/assets/gleo/functions.json`에 JSON Schema 함수 호출 형식으로 적었다. 각 함수는 위 URI 중 하나에 대응한다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (API 34, arm64, Apple Silicon Mac, `-gpu host`) |
| SDK | Pleos Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3 |
| 빌드 | JDK 17, AGP 8.8.2, Kotlin 2.0.0, compileSdk/targetSdk 35, minSdk 28, Gradle 8.14.6 |
| 성능 (에뮬레이터, release) | 경로 지도 정지 시 CPU 0%, 미리 달리기 중(약 30초) 12–23%, RSS 약 155MB |

## 빌드

```
./gradlew :ribbon-core:test        # 핵심 로직 테스트 (실제 노선 끝-끝, 곡률 포함)
./gradlew :app:submissionApk       # apk/Tedin_MapRibbonForAgent_release.apk 생성
```

`keystore.properties`가 없으면 release 빌드는 debug 키로 서명된다. 출품 APK는 저장소 밖의 release 키로 서명했다.

## 구조

| 경로 | 내용 |
|---|---|
| `ribbon-core/` | Android·Pleos에 의존하지 않는 Kotlin 라이브러리. 차량과 (향후) 서버가 같은 코드를 쓴다. `detect`(매듭), `agg`(리본·평소값·이웃 비교), `brief`(경로 브리핑), `geo`(경로 색인, 곡률), `pseudo`(익명 토큰), `spec`(규격), `demo`(시나리오) |
| `app/` | AAOS 앱. `channel/`(기기 안의 리본 채널, 기록 서비스, 다른 에이전트용 ContentProvider), `adapter/`(Pleos Vehicle·FusedLocation·NaviHelper. 신호 하나가 실패해도 앱은 계속 동작), `ui/`(경로 지도, 아이콘, 기록 중 화면, 시연 시트, 색·글자 토큰), `res/values*/`(영어·한국어 문구) |
| `app/src/main/assets/gleo/` | Gleo AI 함수 스키마 |
| `tools/anchors/` | OSM에서 시연 노선과 시설물 추출(영어 이름 포함) → `app/src/main/assets/{route.geojson,anchors.json}` |
| `tools/scenarios/` | 시연 시나리오와 평소값 생성 → `app/src/main/assets/{scenarios/,baseline.json}` |
| `docs/` | 기획서 |

## 개인정보 설계

- 매듭에는 계정·차대번호가 없다. 차량은 **기기 밖으로 나가지 않는 비밀값으로 만든 날짜별 HMAC**(하루마다 바뀜)으로만 구분한다.
- 한 창에 3대 미만이면 공개하지 않는다. 집계가 끝나면 차 한 대의 매듭은 지운다.
- 이 빌드는 서버로 아무것도 보내지 않는다(채널은 앱 안에서 돈다). 다른 에이전트에게는 집계된 리본만 읽기 전용으로 열려 있다.

## 시뮬레이션과 실제의 구분

| 실제 | 시뮬레이션 |
|---|---|
| 노선 형상과 곡률, 터널·분기점 위치(OSM) | 단속카메라 2곳("가상" 표시). OSM과 공공데이터에서 이 노선의 카메라를 확인하지 못했다 |
| Pleos SDK 신호 수신(에뮬레이터 Virtual Console로 확인), 주행 중 전환과 기록 서비스 | 지나가는 차 9대와 그 신호, 평소값(`baseline.json`의 `simulated: true`) |

앱 화면에는 **시뮬레이션 데이터** 배지가 항상 붙는다.

## 확인하지 못한 것

- **내비 연동**: NaviHelper로 경로 시작 이벤트를 받는 부분. 에뮬레이터의 기본 내비가 상태 조회에 응답하지 않아, 앱은 "등록함 · 응답 대기"로 표시한다. 1차 시연은 앱 안의 시연 경로로 한다.
- **경로 선형**: NaviHelper는 경로 선형을 주지 않는다(목적지 좌표와 남은 거리만 온다). 서비스 단계에서는 같은 출발지·목적지로 경로를 다시 계산한다.
- **Gleo AI 연동**: 함수 스키마는 준비했다. 실제 호출은 Gleo 측 스키마 심사 후 열리므로 PT 이후 과제다.
- **백그라운드에서 시작하는 기록**: 이 빌드는 화면이 떠 있을 때 주행 상태 변화를 받아 기록을 시작한다. 내비가 앞에 있는 상태에서 주행이 시작되는 경우는 Android의 백그라운드 포그라운드 서비스 제한 때문에 서비스 없이 프로세스 안에서만 기록된다.
- **영어 시설물 이름**: OSM에 영어 이름이 없는 터널은 기계 로마자 표기다(예: Girin 5 Tunnel).

## 데이터 출처와 라이선스

- 노선과 시설물: © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright). `app/src/main/assets/route.geojson`, `anchors.json`은 ODbL을 따른다.
- 참고한 공공데이터: 전국무인교통단속카메라표준데이터, CCTV정보 표준데이터, 한국도로교통공단 교통사고 위험지역 (공공데이터포털)
