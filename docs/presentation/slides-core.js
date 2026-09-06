const path = require('node:path');
const {
  pptx, C, FONT, addPage, addTopline, addText, addPill,
  addCard, addNode, addArrow, addNotes,
} = require('./theme');

const asset = (name) => path.resolve(__dirname, '..', name);
const mockup = asset('ds_rfs/stog_flow_design_mockup.png');
const preference = asset('ds_rfs/여행성향.png');

function cover() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  slide.addShape(pptx.ShapeType.rect, {
    x: 8.15, y: 0, w: 5.18, h: 7.5, fill: { color: C.paleBlue }, line: { color: C.paleBlue },
  });
  addText(slide, 'STOBEE  /  FINAL PRESENTATION', 0.72, 0.66, 4.0, 0.18, {
    fontFace: FONT.mono, fontSize: 8, bold: true, color: C.yellow, charSpacing: 1.2,
  });
  addText(slide, 'STOG', 0.7, 1.65, 5.5, 0.85, {
    fontFace: FONT.display, fontSize: 45, bold: true, color: C.ink,
  });
  addText(slide, '나를 따라 그려지는\n맞춤형 여행 지도', 0.74, 2.63, 6.15, 1.12, {
    fontSize: 25, bold: true, color: C.ink, valign: 'top',
  });
  addText(slide, '여행은 끝나도, 추억까지 끝나는 건 아니니까요.', 0.76, 4.28, 6.5, 0.3, {
    fontSize: 14, color: C.muted,
  });
  addPill(slide, 'PLAN  →  STEP  →  LOG', 0.76, 5.03, 2.45, C.yellow, C.ink);
  slide.addImage({ path: mockup, x: 8.42, y: 1.18, w: 4.35, h: 4.72, transparency: 1 });
  slide.addShape(pptx.ShapeType.line, {
    x: 0.76, y: 6.37, w: 11.75, h: 0,
    line: { color: C.yellow, width: 2.3, endArrowType: 'triangle' },
  });
  [0.76, 3.1, 5.45, 7.8, 10.15, 12.36].forEach((x) => slide.addShape(pptx.ShapeType.ellipse, {
    x: x - 0.07, y: 6.29, w: 0.16, h: 0.16, fill: { color: C.yellow }, line: { color: C.yellow },
  }));
  addPage(slide, 1);
  addNotes(slide, '안녕하세요. 나를 따라 그려지는 맞춤형 여행 지도, STOG를 발표합니다.');
}

function agenda() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '01 / Story', '오늘은 여행의 흐름을 따라갑니다', '문제의 공감에서 기술과 시연까지, 하나의 여행처럼 이어집니다.');
  const items = [
    ['01', '문제의 공감', C.coral], ['02', '왜 전북인가', C.blue],
    ['03', 'AI 시장 변화', C.yellow], ['04', '기존 서비스', C.olive],
    ['05', 'STOG 해결 구조', C.coral], ['06', '기술 · 시연', C.blue],
    ['07', '기대효과 · 마무리', C.yellow],
  ];
  items.forEach(([number, label, accent], index) => {
    const col = index < 4 ? 0 : 1;
    const row = index < 4 ? index : index - 4;
    const x = 1.2 + col * 5.65;
    const y = 2.25 + row * 0.86;
    addText(slide, number, x, y, 0.5, 0.2, { fontFace: FONT.mono, fontSize: 9, bold: true, color: accent });
    addText(slide, label, x + 0.82, y - 0.05, 3.9, 0.28, { fontSize: 16, bold: true });
    slide.addShape(pptx.ShapeType.line, { x: x + 0.8, y: y + 0.38, w: 3.75, h: 0, line: { color: C.line, width: 0.8 } });
  });
  addText(slide, '여행의 실제 불편함에서 시작해, STOG의 연결 구조로 들어갑니다.', 1.2, 6.15, 9.8, 0.28, {
    fontSize: 14, color: C.muted,
  });
  addPage(slide, 2);
  addNotes(slide, '오늘은 여행의 실제 불편함에서 시작해, 왜 전북인지, STOG가 어떻게 해결하는지 순서대로 말씀드리겠습니다.');
}

function specialMoment() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleYellow };
  addText(slide, '02 / A moment', 0.78, 0.66, 2.2, 0.18, {
    fontFace: FONT.mono, fontSize: 8, bold: true, color: C.coral, charSpacing: 1.2,
  });
  addText(slide, '여행은 특별한 순간입니다', 0.78, 1.26, 7.6, 0.52, {
    fontFace: FONT.display, fontSize: 30, bold: true, color: C.ink,
  });
  addText(slide, '그렇다면 우리는 이 소중한 여행을\n어떻게 준비하고, 어떻게 남기고 있을까요?', 0.8, 2.2, 5.5, 0.92, {
    fontSize: 20, bold: true, color: C.ink, valign: 'top',
  });
  addCard(slide, 7.52, 1.72, 4.92, 4.12, C.paper, C.white, 0.2);
  slide.addImage({ path: mockup, x: 7.7, y: 1.9, w: 4.56, h: 3.76, transparency: 1 });
  addText(slide, '친구  ·  가족  ·  풍경  ·  한 끼', 7.82, 6.12, 4.3, 0.2, {
    fontFace: FONT.mono, fontSize: 8.2, color: C.muted, align: 'center',
  });
  addPage(slide, 3);
  addNotes(slide, '사람마다 평범한 일상 속 특별한 순간이 있습니다. 저희는 그중 하나가 여행이라고 생각했습니다.');
}

function planningReality() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '03 / Before the trip', '“우리 어디 가지?”', '여행 하나를 계획하기 위해 우리는 여러 서비스를 오갑니다.');
  const apps = [
    ['SNS', '장소 발견', C.paleCoral, C.coral],
    ['CHAT', '링크 공유', C.paleYellow, C.yellow],
    ['SEARCH', '맛집·관광지 검색', C.paleBlue, C.blue],
    ['MAP', '일정·동선 정리', C.paleOlive, C.olive],
  ];
  apps.forEach(([name, detail, fill, accent], index) => {
    const x = 0.92 + index * 3.12;
    addCard(slide, x, 2.42, 2.42, 1.5, fill, C.white, 0.16);
    addText(slide, name, x + 0.22, 2.73, 1.8, 0.22, { fontFace: FONT.mono, fontSize: 9, bold: true, color: accent });
    addText(slide, detail, x + 0.22, 3.18, 1.95, 0.26, { fontSize: 14, bold: true });
    if (index < 3) addArrow(slide, x + 2.55, 3.17, x + 2.94, 3.17, C.ink);
  });
  addText(slide, '발견 → 공유 → 검색 → 정리', 3.32, 4.62, 6.8, 0.34, {
    fontFace: FONT.mono, fontSize: 12, bold: true, color: C.ink, align: 'center',
  });
  addText(slide, '여행 하나를 계획하기 위해, 정보의 맥락이 계속 끊깁니다.', 2.62, 5.58, 8.1, 0.3, {
    fontSize: 16, color: C.muted, align: 'center',
  });
  addPage(slide, 4);
  addNotes(slide, 'SNS에서 장소를 발견하고, 단체방에 공유하고, 다시 검색하고, 지도와 메모를 오갑니다.');
}

function scatteredInfo() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleBlue };
  addTopline(slide, '04 / Problem 01', '정보는 많은데, 정리는 어렵습니다', '찾는 것은 쉬워졌지만, 모으는 것은 어려워졌습니다.');
  const scraps = [
    ['저장 게시물', C.paper, 1.02, 2.3, -5], ['카카오톡 링크', C.paleYellow, 3.0, 3.42, 4],
    ['블로그 · 릴스', C.paleCoral, 1.55, 4.55, -3], ['지도 저장 장소', C.paleOlive, 4.35, 2.28, 5],
    ['캡처 이미지', C.paper, 4.22, 4.82, -4],
  ];
  scraps.forEach(([label, fill, x, y]) => {
    addCard(slide, x, y, 2.2, 0.68, fill, C.white, 0.1);
    addText(slide, label, x + 0.18, y + 0.2, 1.82, 0.2, { fontSize: 11, bold: true });
  });
  addText(slide, '그래서\n어디 가기로 했지?', 7.55, 2.62, 4.4, 1.12, {
    fontFace: FONT.display, fontSize: 26, bold: true, color: C.ink, valign: 'top',
  });
  addText(slide, '정보 부족이 아니라 정보 분산이 문제입니다.', 7.58, 4.3, 4.3, 0.3, {
    fontSize: 15, color: C.muted,
  });
  addText(slide, 'source · context · decision', 7.58, 5.35, 3.4, 0.22, {
    fontFace: FONT.mono, fontSize: 9, color: C.coral,
  });
  addPage(slide, 5);
  addNotes(slide, '여행 정보가 부족한 것이 아니라, 너무 많은 정보가 서로 다른 공간에 흩어지는 것이 문제였습니다.');
}

function changingPlan() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '05 / Problem 02', '계획은 실제 여행에서 계속 변합니다', '정적인 일정표와 실제 여행의 속도는 다릅니다.');
  addText(slide, 'PLAN', 0.88, 2.25, 1.0, 0.22, { fontFace: FONT.mono, fontSize: 9, bold: true, color: C.blue });
  addText(slide, '10:00 한옥마을  →  12:00 식당  →  14:00 경기전  →  16:00 카페', 0.88, 2.7, 5.5, 0.45, {
    fontSize: 13, bold: true,
  });
  slide.addShape(pptx.ShapeType.line, { x: 1.0, y: 3.7, w: 5.35, h: 0, line: { color: C.blue, width: 2 } });
  [1.0, 2.7, 4.4, 6.2].forEach((x) => slide.addShape(pptx.ShapeType.ellipse, {
    x: x - 0.08, y: 3.62, w: 0.16, h: 0.16, fill: { color: C.blue }, line: { color: C.blue },
  }));
  addText(slide, 'ACTUAL', 7.25, 2.25, 1.1, 0.22, { fontFace: FONT.mono, fontSize: 9, bold: true, color: C.coral });
  const changes = [['출발 지연', C.paleYellow], ['우연한 발견', C.paleBlue], ['웨이팅', C.paleCoral], ['비', C.paleOlive]];
  changes.forEach(([label, fill], index) => {
    const y = 2.67 + index * 0.72;
    addCard(slide, 7.24, y, 3.65, 0.48, fill, C.white, 0.08);
    addText(slide, label, 7.48, y + 0.13, 2.4, 0.2, { fontSize: 11, bold: true });
  });
  addText(slide, '여행은 계획대로 움직이는 것이 아니라 계속 변합니다.', 1.0, 5.55, 10.8, 0.34, {
    fontSize: 18, bold: true, color: C.ink, align: 'center',
  });
  addPage(slide, 6);
  addNotes(slide, '날씨, 이동 지연, 웨이팅, 우연한 발견 때문에 계획은 계속 달라집니다.');
}

function photoContext() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleCoral };
  addTopline(slide, '06 / Problem 03', '여행 후에는 사진만 남습니다', '사진은 남았지만, 우리가 걸었던 여행의 맥락은 흐려집니다.');
  addCard(slide, 0.9, 2.18, 5.32, 3.86, C.paper, C.white, 0.16);
  slide.addImage({ path: mockup, x: 1.08, y: 2.36, w: 4.96, h: 3.5, transparency: 1 });
  addText(slide, '사진 · 일정 · 이동의 맥락', 1.18, 5.88, 4.75, 0.18, {
    fontFace: FONT.mono, fontSize: 8, color: C.muted, align: 'center',
  });
  addText(slide, '어디를 걸었고,\n어떤 순서였을까요?', 6.4, 2.52, 5.0, 0.95, {
    fontFace: FONT.display, fontSize: 27, bold: true, color: C.ink, valign: 'top',
  });
  addText(slide, '위치 · 동선 · 발견의 맥락\n사진과 함께 남지 않습니다.', 6.42, 4.2, 5.2, 0.62, {
    fontSize: 14, color: C.muted, valign: 'top',
  });
  addPill(slide, 'PHOTO  ≠  JOURNEY', 6.42, 5.37, 2.32, C.ink, C.white);
  addPage(slide, 7);
  addNotes(slide, '사진은 많이 남지만 어디를 걸었고 무엇을 발견했는지는 점점 기억에서 사라집니다.');
}

function problemDefinition() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '07 / Problem', '여행 전·중·후가 단절되어 있습니다', '계획·경험·기록이 각각 다른 서비스에 흩어져 서로 연결되지 않습니다.');
  const areas = [
    ['BEFORE', '정보 탐색', 'SNS · 검색 · 단톡방', C.paleCoral, C.coral],
    ['DURING', '일정 변경', '지도 · 재검색 · 이동', C.paleYellow, C.yellow],
    ['AFTER', '기억 보관', '사진 적재 · 맥락 소실', C.paleBlue, C.blue],
  ];
  areas.forEach(([eyebrow, title, detail, fill, accent], index) => {
    const x = 0.95 + index * 4.15;
    addCard(slide, x, 2.42, 3.3, 1.92, fill, C.white, 0.16);
    addText(slide, eyebrow, x + 0.24, 2.72, 1.2, 0.18, { fontFace: FONT.mono, fontSize: 8, bold: true, color: accent });
    addText(slide, title, x + 0.24, 3.18, 2.4, 0.28, { fontSize: 17, bold: true });
    addText(slide, detail, x + 0.24, 3.72, 2.65, 0.2, { fontSize: 10.5, color: C.muted });
    if (index < 2) addText(slide, '×', x + 3.5, 3.12, 0.35, 0.3, { fontSize: 21, bold: true, color: C.coral, align: 'center' });
  });
  addText(slide, '끊어진 여행의 흐름을 다시 연결해야 합니다.', 1.2, 5.35, 10.6, 0.35, {
    fontSize: 19, bold: true, color: C.ink, align: 'center',
  });
  addPage(slide, 8);
  addNotes(slide, '저희가 발견한 문제는 하나였습니다. 여행의 계획, 실제 경험, 기록이 서로 연결되지 않는다는 것입니다.');
}

function jeonbuk() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleBlue };
  addTopline(slide, '08 / Why Jeonbuk', '그렇다면, 왜 전북일까요?', '관광객의 부족이 아니라, 머무는 시간을 어디로 연결하느냐의 문제입니다.');
  const stats = [
    ['2,529만', '2026년 1분기 방문자', C.coral, C.paleCoral],
    ['전국 2위', '관광객 체류시간', C.blue, C.paper],
    ['전국 13위', '관광지출', C.olive, C.paleOlive],
  ];
  stats.forEach(([number, label, accent, fill], index) => {
    const x = 0.86 + index * 4.15;
    addCard(slide, x, 2.32, 3.45, 2.12, fill, C.white, 0.18);
    addText(slide, number, x + 0.28, 2.84, 2.85, 0.5, { fontFace: FONT.display, fontSize: 28, bold: true, color: accent });
    addText(slide, label, x + 0.3, 3.7, 2.75, 0.24, { fontSize: 12.5, bold: true });
    slide.addShape(pptx.ShapeType.line, { x: x + 0.3, y: 4.08, w: 2.75, h: 0, line: { color: accent, width: 1.8 } });
  });
  addText(slide, '오래 머무는 관광객을, 전북의 더 다양한 장소와 경험으로.', 0.9, 5.42, 11.4, 0.34, {
    fontSize: 18, bold: true, color: C.ink, align: 'center',
  });
  addText(slide, 'Source: 2026년 1분기 전북 관광 데이터', 0.9, 6.28, 11.4, 0.18, {
    fontFace: FONT.mono, fontSize: 7.5, color: C.muted, align: 'center',
  });
  addPage(slide, 9);
  addNotes(slide, '전북은 체류시간이 긴 지역입니다. 저희는 이 시간을 더 다양한 지역 경험으로 연결하는 데 주목했습니다.');
}

function jeonbukChallenge() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '09 / Regional fit', '전북특화는 관광지 나열이 아닙니다', '대표 관광지에서 다음 경험으로 이어지는 지역 연결입니다.');
  const cards = [
    ['01', '다음 장소로 연결', '대표 관광지에서\n다음 장소를 찾기 어렵습니다.', C.paleCoral, C.coral],
    ['02', '취향으로 조합', '자연·역사·미식·체험·휴식을\n취향으로 조합하기 어렵습니다.', C.paleYellow, C.yellow],
    ['03', '관계가 이어짐', '여행이 끝나면 지역과의 관계도\n함께 끊어집니다.', C.paleBlue, C.blue],
  ];
  cards.forEach(([number, title, body, fill, accent], index) => {
    const x = 0.8 + index * 4.18;
    addCard(slide, x, 2.38, 3.65, 2.45, fill, C.white, 0.18);
    addText(slide, number, x + 0.3, 2.72, 0.5, 0.22, { fontFace: FONT.mono, fontSize: 9, bold: true, color: accent });
    addText(slide, title, x + 0.3, 3.22, 2.7, 0.3, { fontSize: 17, bold: true });
    addText(slide, body, x + 0.3, 3.85, 3.0, 0.62, { fontSize: 10.2, color: C.muted, valign: 'top' });
  });
  addText(slide, '전북 관광자원 × 여행성향 × 실제 이동 × H3 기록', 1.0, 5.65, 11.3, 0.28, {
    fontFace: FONT.mono, fontSize: 11, bold: true, color: C.ink, align: 'center',
  });
  addPage(slide, 10);
  addNotes(slide, '전북 관광지를 나열하는 것이 아니라 지역 경험을 연결하는 것이 STOG의 전북특화입니다.');
}

function aiMarket() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleYellow };
  addTopline(slide, '10 / AI shift', '여행 서비스도 검색에서 개인화로 이동합니다', 'AI는 써 보고 싶은 기술에서 실제 여행을 돕는 도구가 되고 있습니다.');
  addText(slide, '2025', 1.12, 2.35, 1.1, 0.22, { fontFace: FONT.mono, fontSize: 9, bold: true, color: C.coral });
  addText(slide, '98%', 1.05, 2.82, 3.6, 0.72, { fontFace: FONT.display, fontSize: 40, bold: true, color: C.coral });
  addText(slide, '한국 여행자의\nAI 활용 의향', 1.12, 3.78, 2.4, 0.52, { fontSize: 15, bold: true, valign: 'top' });
  addArrow(slide, 4.48, 3.36, 7.18, 3.36, C.ink, 'actual use');
  addText(slide, '2026', 8.0, 2.35, 1.1, 0.22, { fontFace: FONT.mono, fontSize: 9, bold: true, color: C.blue });
  addText(slide, '69%', 7.92, 2.82, 3.6, 0.72, { fontFace: FONT.display, fontSize: 40, bold: true, color: C.blue });
  addText(slide, '한국 여행객의\n여행 중 AI 활용 경험', 8.0, 3.78, 2.9, 0.52, { fontSize: 15, bold: true, valign: 'top' });
  addText(slide, 'AI가 결정을 대신하는 것이 아니라, 사용자의 선택과 여행을 보조해야 합니다.', 1.15, 5.62, 10.8, 0.32, {
    fontSize: 17, bold: true, color: C.ink, align: 'center',
  });
  addText(slide, 'Source: Booking.com 글로벌 AI 인식 보고서 · 연합뉴스 보도', 1.15, 6.35, 10.8, 0.18, {
    fontFace: FONT.mono, fontSize: 7.3, color: C.muted, align: 'center',
  });
  addPage(slide, 11);
  addNotes(slide, 'AI는 사용해 보고 싶은 기술에서 실제 여행을 돕는 도구로 이동하고 있습니다.');
}

function existingServices() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '11 / Existing services', '각각의 기능은 잘하지만, 여행은 이어지지 않습니다', '경쟁을 말하는 대신 각 서비스의 역할과 남는 단절을 봅니다.');
  const rows = [
    ['SNS', '여행지 발견', '정보가 콘텐츠 속에 흩어짐', C.coral],
    ['지도', '장소 검색 · 경로', '여행 단위 관리 부족', C.blue],
    ['메신저', '링크 공유', '정보가 대화에 묻힘', C.yellow],
    ['플랫폼', '예약 · 상품 · 일정', '실제 경험 기록과 단절', C.olive],
    ['사진 앱', '사진 보관', '여행 동선과 맥락 부족', C.coral],
  ];
  rows.forEach(([name, role, gap, accent], index) => {
    const y = 2.16 + index * 0.7;
    addText(slide, name, 1.0, y, 1.25, 0.22, { fontSize: 13, bold: true, color: accent });
    addText(slide, role, 3.02, y, 2.7, 0.22, { fontSize: 12, bold: true });
    addText(slide, gap, 7.0, y, 4.5, 0.22, { fontSize: 11, color: C.muted });
    slide.addShape(pptx.ShapeType.line, { x: 0.98, y: y + 0.42, w: 11.3, h: 0, line: { color: C.line, width: 0.8 } });
  });
  addText(slide, '기능의 합이 아니라, 하나의 여행 경험으로 연결되어야 합니다.', 1.2, 6.22, 10.7, 0.3, {
    fontSize: 16, bold: true, color: C.ink, align: 'center',
  });
  addPage(slide, 12);
  addNotes(slide, '기존 서비스는 각각의 기능을 잘 수행하지만 여행 전·중·후 전체 경험은 연결하지 못했습니다.');
}

function bridge() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleBlue };
  addTopline(slide, '12 / STOG', '그래서 STOG', '사용자의 선택을 실제 이동과 기록의 흐름으로 연결합니다.');
  const steps = [
    ['PLAN', '발견 · 계획', '나에게 맞는 장소를 찾고 담습니다.', C.coral, C.paleCoral],
    ['STEP', '이동 · 변경', '상황에 맞게 순서를 바꿔 걷습니다.', C.yellow, C.paleYellow],
    ['LOG', '위치 · 사진', '걸어온 길과 순간을 남깁니다.', C.blue, C.paleBlue],
  ];
  steps.forEach(([key, title, body, accent, fill], index) => {
    const x = 0.95 + index * 4.14;
    addCard(slide, x, 2.45, 3.45, 2.35, fill, C.white, 0.18);
    addText(slide, key, x + 0.28, 2.78, 1.7, 0.3, { fontFace: FONT.display, fontSize: 23, bold: true, color: accent });
    addText(slide, title, x + 0.28, 3.4, 2.7, 0.26, { fontSize: 14, bold: true });
    addText(slide, body, x + 0.28, 3.92, 2.75, 0.42, { fontSize: 10.5, color: C.muted, valign: 'top' });
    if (index < 2) addArrow(slide, x + 3.54, 3.55, x + 3.95, 3.55, C.ink);
  });
  addText(slide, 'STOG는 AI가 여행을 대신 결정하는 서비스가 아닙니다.', 1.25, 5.65, 10.8, 0.3, {
    fontSize: 16, bold: true, color: C.ink, align: 'center',
  });
  addPage(slide, 13);
  addNotes(slide, 'STOG는 사용자의 성향에 맞는 장소를 추천하고 실제 동선으로 연결한 뒤 지도 위에 기록합니다.');
}

function preferenceEmbedding() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '13 / PLAN', '하나의 유형이 아니라, 여섯 성향의 조합', '사용자를 고정하지 않고 이번 여행에 맞는 조합을 찾습니다.');
  slide.addImage({ path: preference, x: 0.72, y: 2.02, w: 1.65, h: 3.8, transparency: 2 });
  const traits = [
    ['버즈', '자연 · 걷기', C.olive], ['윙', '역사 · 문화', C.coral],
    ['픽', '쇼핑 · 로컬', C.blue], ['허니', '미식 · 카페', C.yellow],
    ['액티', '체험 · 축제', C.coral], ['릴리', '휴식 · 힐링', C.olive],
  ];
  traits.forEach(([name, detail, accent], index) => {
    const x = 2.72 + (index % 2) * 2.03;
    const y = 2.12 + Math.floor(index / 2) * 1.02;
    addCard(slide, x, y, 1.84, 0.78, C.paper, C.line, 0.12);
    slide.addShape(pptx.ShapeType.ellipse, { x: x + 0.16, y: y + 0.18, w: 0.34, h: 0.34, fill: { color: accent }, line: { color: accent } });
    addText(slide, name, x + 0.64, y + 0.13, 0.92, 0.2, { fontSize: 11, bold: true });
    addText(slide, detail, x + 0.64, y + 0.41, 1.04, 0.18, { fontSize: 8, color: C.muted });
  });
  addArrow(slide, 7.28, 3.45, 8.06, 3.45, C.ink, 'embed');
  addCard(slide, 8.15, 2.2, 4.25, 2.68, C.ink, C.ink, 0.18);
  addText(slide, 'PROFILE VECTOR', 8.48, 2.56, 2.1, 0.18, { fontFace: FONT.mono, fontSize: 8, bold: true, color: C.yellow, charSpacing: 1.0 });
  addText(slide, '성향을 의미의\n방향으로 표현', 8.48, 3.08, 3.1, 0.68, { fontSize: 19, bold: true, color: C.white, valign: 'top' });
  addText(slide, '[자연 0.8 · 미식 0.7 · 휴식 0.4]', 8.48, 4.28, 3.35, 0.2, { fontFace: FONT.mono, fontSize: 8.5, color: C.line });
  addText(slide, 'profile vector  <->  place vector', 8.5, 5.72, 4.0, 0.22, { fontFace: FONT.mono, fontSize: 10, bold: true, color: C.ink });
  addText(slide, 'cosine similarity로 성향 적합도를 비교합니다.', 8.5, 6.2, 3.85, 0.22, { fontSize: 10.5, color: C.muted });
  addPage(slide, 14);
  addNotes(slide, '사용자를 하나의 유형으로 고정하지 않고 여섯 가지 성향의 점수를 함께 분석합니다.');
}

module.exports = {
  cover, agenda, specialMoment, planningReality, scatteredInfo, changingPlan,
  photoContext, problemDefinition, jeonbuk, jeonbukChallenge, aiMarket,
  existingServices, bridge, preferenceEmbedding,
};
