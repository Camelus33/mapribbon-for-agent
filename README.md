# 맵톡 드라이브 리본 (Maptalk Drive Ribbons)

**한 줄 설명**: 앞서 지나간 차들이 도로 시설물마다 남긴 기록을, 내 차가 경로를 정하는 순간 한 장의 화면으로 보여 주는 Pleos Connect 앱.

현대자동차그룹 차량용 앱 개발 공모전 출품작 · 팀 Tedin (Tedin Inc.)

## 무엇을 하나

- **리본**은 공공데이터·OpenStreetMap의 도로 시설물(터널, 분기점, 톨게이트, 단속카메라)마다 하나씩 놓인다. 사람도 에이전트도 위치를 정하지 않는다.
- 차가 시설물의 영향 구간을 지나가면 차 안의 에이전트가 **매듭** 하나를 남긴다. 매듭은 그 구간에서 겪은 일을 숫자로 적은 기록이다(속도, 정지, 급제동, 분기 직전 방향지시등, 와이퍼·안개등).
- 매듭이 모이면 리본의 상태(`maptalk.ribbon/1`)가 된다. 창은 15분 → 1시간 → 3시간으로 넓히며, **서로 다른 차 3대 미만이면 공개하지 않는다.**
- **"평소와 다름"은 평소값보다도, 같은 길의 이웃 시설물보다도 높을 때만** 표시한다. 그래서 도로 전체에 비가 오면 아무것도 켜지지 않는다. 한 터널 출구에서만 와이퍼가 켜질 때 그 터널이 켜진다.
- 경로를 정하면 **경로 지도** 한 장이 뜬다. 실제 도로 모양을 출발(왼쪽 아래)에서 도착(오른쪽 위)으로 놓고, 비는 두 모서리에 답(왼쪽 위)과 표(오른쪽 아래)를 둔다. 주행 중에는 다음 한 곳까지의 거리와 무엇이 다른지만 보이고, 누를 것이 없다(CarUxRestrictions).
- 상태는 색만으로 말하지 않는다: 조금 다름은 원, 크게 다름은 삼각형과 "!", 그리고 글자. 빨강은 즉시 위험용으로 남겨 쓰지 않는다. 주야 모드는 Pleos 셸을 따른다.

## APK 위치

`apk/Tedin_MaptalkDrive_release.apk` (release 빌드, 서명됨)

## 실행 방법 (Pleos Connect 에뮬레이터)

1. Pleos Connect v2.1.0 에뮬레이터를 **Cold boot**로 실행한다.
2. 설치: `adb install -g apk/Tedin_MaptalkDrive_release.apk`
3. 앱 목록에서 **맵톡 드라이브**를 연다. 위치·속도 권한을 허용한다.
4. 오른쪽 위 **시연**을 누르고 `인제양양터널 너머 비, 서양양IC 직전 끼어들기`를 고른다.
   시뮬레이션 차 9대가 서울양양고속도로(인제IC → 양양JC, 34.5km)를 지나가며 리본을 채운다.
5. 지도와 표에 세 곳이 표시된다: **인제양양터널 출구**(와이퍼 켠 차 9대 중 6대, 평소 3%), **서양양IC**(늦은 끼어들기 9대 중 4대, 평소 8%), **단속카메라**(급제동 8대 중 3대, 가상 카메라).
6. **시뮬레이션 주행 시작**을 누르면 내 차가 20배속으로 달리며 주행 화면으로 바뀐다.
7. **시연**에서 `대조군: 전 구간 비`를 고르면 와이퍼 사용은 100%로 기록되지만 평소와 다른 곳은 0곳이다.

한 줄 명령으로도 재현된다.

```
adb shell am start -n kr.tedin.maptalkdrive/.MainActivity --es demo_scenario local-weather --ei demo_drive_speedup 20
```

### 실제 차량 신호 확인 (선택)

```
adb forward tcp:8088 tcp:8088
```

브라우저에서 http://127.0.0.1:8088 (Virtual Console)을 연다.
- 앞 와이퍼·방향지시등을 바꾸면 앱의 **시연 → 차량 연결** 칸에 바로 반영된다.
- 기어를 D로 놓으면 앱이 주행 화면으로 전환되고 버튼이 사라진다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (API 34, arm64, Apple Silicon Mac, `-gpu host`) |
| SDK | Pleos Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3 |
| 빌드 | JDK 17, AGP 8.8.2, Kotlin 2.0.0, compileSdk/targetSdk 35, minSdk 28, Gradle 8.14.6 |
| 성능(에뮬레이터, 1배속 주행) | CPU 약 2%, RSS 약 160MB |

## 빌드

```
./gradlew :ribbon-core:test        # 핵심 로직 테스트 (실제 노선 끝-끝 포함)
./gradlew :app:submissionApk       # apk/Tedin_MaptalkDrive_release.apk 생성
```

`keystore.properties`가 없으면 release 빌드는 debug 키로 서명된다. 출품 APK는 저장소 밖의 release 키로 서명했다.

## 구조

| 경로 | 내용 |
|---|---|
| `ribbon-core/` | Android·Pleos 의존 없는 Kotlin 라이브러리. 차량과 (향후) 서버가 같은 코드를 쓴다. `detect`(매듭), `agg`(리본·평소값·이웃 비교), `brief`(경로 띠), `pseudo`(익명 토큰), `demo`(시나리오) |
| `app/` | AAOS 앱. `adapter/`(Pleos Vehicle·FusedLocation·NaviHelper, 각 신호 실패해도 앱은 계속 동작), `demo/`(앱 안의 리본 채널), `ui/`(경로 지도, 주행 화면, 시연 시트, 색·글자 토큰) |
| `tools/anchors/` | OSM에서 시연 노선과 시설물 추출 → `app/src/main/assets/{route.geojson,anchors.json}` |
| `tools/scenarios/` | 시연 시나리오와 평소값 생성 → `app/src/main/assets/{scenarios/,baseline.json}` |
| `docs/` | 기획서 |

## 개인정보 설계

- 매듭에는 계정·차대번호가 없다. 차량 구분은 **기기 밖으로 나가지 않는 비밀값의 날짜별 HMAC**(하루마다 바뀜)뿐이다.
- 한 창에 3대 미만이면 공개하지 않는다. 집계가 끝나면 차 한 대의 매듭은 지운다.
- 이 빌드는 서버로 아무것도 보내지 않는다(시연 채널은 앱 안에서 돈다).

## 시뮬레이션과 실제의 구분

| 실제 | 시뮬레이션 |
|---|---|
| 노선 형상, 터널·분기점 위치(OSM) | 단속카메라 2곳("가상" 표시) — OSM과 공공데이터에서 이 노선의 카메라를 확인하지 못했다 |
| Pleos SDK 신호 수신(에뮬레이터 Virtual Console로 확인) | 지나가는 차 9대와 그 신호, 평소값(`baseline.json`의 `simulated: true`) |

앱 화면에는 **시뮬레이션 데이터** 배지가 항상 붙는다.

## 확인하지 못한 것

- **내비 연동**: NaviHelper로 경로 시작 이벤트를 받는 부분. 에뮬레이터의 기본 내비가 상태 조회에 응답하지 않아, 앱은 "등록함 · 응답 대기"로 표시한다. 1차 시연은 앱 안의 시연 경로로 한다.
- **경로 선형**: NaviHelper에는 경로 선형이 없다(목적지 좌표와 남은 거리만 온다). 서비스 단계에서는 같은 출발지·목적지로 경로를 계산한다.
- **Gleo AI 연동**: 함수 호출 스키마 심사 후 개통되므로 PT 이후 과제다.

## 데이터 출처와 라이선스

- 노선과 시설물: © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright). `app/src/main/assets/route.geojson`, `anchors.json`은 ODbL을 따른다.
- 참고한 공공데이터: 전국무인교통단속카메라표준데이터, CCTV정보 표준데이터, 한국도로교통공단 교통사고 위험지역 (공공데이터포털)
