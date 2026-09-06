# STOG map/search design research

상태: 2026-08-18 기준 구현 참고 자료. 제품 사실과 canonical YAML을 대체하지 않는다.

## 이번 작업에 반영한 항목

- [x] Google 지도를 배경으로 유지하는 지도 오버레이 바텀 모달 시트
- [x] 부분 펼침, 드래그 확장, 명시적 `펼치기` 동작
- [x] 검색·저장 후보 카드의 장소명/주소/완료 상태 계층
- [x] `StogCanvas`, `StogSurface`, `StogYellow`, `StogInk`와 공통 여백·모서리 토큰
- [x] 검색 후보를 여행에 담은 뒤 `담김`으로 상태를 고정

## 출처와 근거

### 국내 공식 서비스

- [네이버 고객센터 - 장소/경로 저장](https://help.naver.com/service/5637/contents/692):
  로그인 후 저장하고 저장 탭에서 확인·편집·삭제하는 흐름. STOG의 후보 확인
  상태와 계획 영역 분리를 참고했다.
- [네이버 고객센터 - 리스트 공유](https://help.naver.com/service/5637/contents/21479):
  저장 리스트의 상세·공유 권한을 별도 행동으로 둔다. 현재 작업에서는 공유를
  추가하지 않고, 저장 완료 상태만 적용했다.
- [트리플 공식 소개](https://triple.guide/intro) 및
  [Google Play 제품 페이지](https://play.google.com/store/apps/details?id=com.titicacacorp.triple&hl=ko):
  장소 선택 이후 일정에 편입하고 지도와 카드형 계획을 함께 확인하는 계층을
  참고했다.
- [대한민국 구석구석 여행지도](https://korean.visitkorea.or.kr/mylocation/mylocation.do):
  지도에서 주변 장소를 탐색하고 유형·테마와 개인 저장을 분리하는 방향을
  참고했다. 페이지 본문은 동적으로 완전 검증하지 못했으므로 시각 규칙의
  근거가 아니라 보조 근거로 기록한다.
- [네이버 지도 서비스](https://www.navercorp.com/service/map)와
  [네이버 지도 Google Play](https://play.google.com/store/apps/details?id=com.nhn.android.nmap&hl=ko&gl=KR):
  지도 맥락을 유지한 장소 탐색·저장 계층을 확인했다. 시트의 정확한 치수는
  문서화하지 않았으므로 추정하지 않았다.
- [카카오맵 Google Play](https://play.google.com/store/apps/details?id=net.daum.android.map&hl=ko&gl=KR):
  통합 검색과 지도 위 저장 장소라는 정보 구조만 참고했다. STOG의 지도
  provider를 Kakao로 되돌리는 근거로 사용하지 않았다.
- [TMAP 어디갈까](https://www.tmapmobility.com/service/place/where):
  주변 탐색과 발견 모드를 분리하는 구조를 참고했다.

### API·플랫폼 근거

- [Kakao Developers Local keyword search](https://developers.kakao.com/docs/ko/local/dev-guide#search-by-keyword)
  및 [category search](https://developers.kakao.com/docs/ko/local/dev-guide#search-by-category):
  장소명·분류·주소·좌표를 결과 카드에 남기고 검색/분류를 별도 확장할 수
  있다는 정보 구조를 참고했다. STOG의 지도 provider는 Google로 유지한다.
- [Material 3 bottom sheets](https://m3.material.io/components/bottom-sheets/overview):
  Android 시트 동작의 기준으로 `BottomSheetScaffold`를 선택했다.
- [Android Compose bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets):
  Compose의 시트 구현 표면을 확인하는 공식 경로다. 이 환경에서는 페이지
  본문을 직접 확인하지 못했으므로 구체 치수의 근거로 사용하지 않았다.
- [Samsung One UI layout grid](https://developer.samsung.com/one-ui/layout/grid.html),
  [button](https://developer.samsung.com/one-ui/comp/button.html),
  [accessibility](https://developer.samsung.com/one-ui/accessibility/layout-and-typo.html):
  24dp keyline, 한 화면의 버튼 강조 일관성, 확대·터치·대비 검토의 근거로
  사용했다. 18dp 예시 radius를 STOG 전역 토큰으로 복사하지 않았다.
- [Kurly design-system 운영 사례](https://helloworld.kurly.com/blog/klds-web-structure-refactor/)
  및 [Kakao Entertainment 공통 컴포넌트](https://tech.kakaoent.com/front-end/2024/240116-common-component/):
  실사용 흐름을 기준으로 공통 컴포넌트 책임과 제한된 variant를 유지하는
  원칙만 참고했다.

### 보조·접근성 근거

- [WCAG 2.2](https://www.w3.org/TR/WCAG22/):
  상태를 색상만으로 전달하지 않고, 드래그만을 유일한 조작으로 만들지 않으며,
  충분한 터치 대상과 접근 가능한 이름을 제공해야 한다는 기준으로 검토했다.
- Pinterest, Dribbble, 재게시된 화면 캡처와 접근 불가능한 블로그 자료는
  구현 근거에서 제외했다.

## 확인하지 못한 것과 후속 항목

- [ ] 국내 서비스의 공식 문서에서 부분/전체 펼침 높이, 외부 탭 해제,
  뒤로가기, 키보드 inset의 구체 치수는 확인하지 못했다.
- [ ] Kakao Design 공식 사이트와 Naver/Toss의 상세 시트 규칙은 이 조사에서
  접근 가능한 1차 자료를 확보하지 못했다.
- [ ] 기존 여행을 선택하는 Android 흐름은 `product.yaml` A-11로 남겼다.
- [ ] Pixel_8의 Google Maps 타일은 Android Maps API key authorization failure로
  직접 확인하지 못했다. 패키지/서명 제한을 외부 설정에서 확인해야 하며,
  key 값은 소스·문서·로그에 복사하지 않는다.
- [ ] 이 프로젝트는 네이티브 Compose 앱이므로 Playwright 브라우저 자동화는
  직접 표면에 연결하지 못한다. Pixel_8 adb/UI 트리/스크린샷 검증으로
  대체하고, 웹 버전은 `product.yaml` 범위 밖으로 유지한다.

