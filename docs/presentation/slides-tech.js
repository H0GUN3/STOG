const {
  pptx, C, FONT, addPage, addTopline, addText, addPill,
  addCard, addNode, addArrow, addNotes,
} = require('./theme');

function solutionFlow() {
  const slide = pptx.addSlide();
  slide.background = { color: C.paleBlue };
  addTopline(slide, '14 / Solution flow', '추천에서 끝나지 않고, 다음 여행으로 이어집니다', '성향·장소·이동·기록이 하나의 순환 구조를 만듭니다.');
  const steps = [
    ['01', '성향 설문', '6축 점수', C.coral, C.paleCoral],
    ['02', '전북 관광 데이터', 'source · category · 좌표', C.blue, C.paper],
    ['03', '개인화 추천', '30개 → 10개', C.yellow, C.paleYellow],
    ['04', '실제 여행', '일정 · 변경 · 이동', C.olive, C.paleOlive],
    ['05', 'H3 기록', '위치 · 사진 · 맥락', C.coral, C.paleCoral],
  ];
  steps.forEach(([number, title, detail, accent, fill], index) => {
    const x = 0.62 + index * 2.53;
    addCard(slide, x, 2.3, 2.12, 1.68, fill, C.white, 0.14);
    addText(slide, number, x + 0.22, 2.59, 0.42, 0.18, { fontFace: FONT.mono, fontSize: 8, bold: true, color: accent });
    addText(slide, title, x + 0.22, 3.02, 1.65, 0.25, { fontSize: 14, bold: true });
    addText(slide, detail, x + 0.22, 3.47, 1.64, 0.22, { fontFace: FONT.mono, fontSize: 7.7, color: C.muted });
    if (index < 4) addArrow(slide, x + 2.22, 3.12, x + 2.45, 3.12, C.ink);
  });
  addCard(slide, 1.05, 5.15, 11.25, 0.85, C.ink, C.ink, 0.13);
  addText(slide, '기록이 쌓일수록  →  다음 여행의 추천이 가까워집니다', 1.35, 5.42, 10.5, 0.25, {
    fontSize: 16, bold: true, color: C.white, align: 'center',
  });
  addPage(slide, 15);
  addNotes(slide, 'STOG는 추천과 이동, 기록을 하나의 여행으로 연결하고 기록을 다음 여행 개인화로 다시 사용합니다.');
}

function architecture() {
  const slide = pptx.addSlide();
  slide.background = { color: C.black };
  addTopline(slide, '15 / Technical architecture', '앱에서 Cloud까지, 하나의 여행 데이터 흐름', '발견·추천·일정·기록을 각각의 책임으로 나누고 하나의 시스템으로 연결합니다.', true);
  addCard(slide, 0.58, 2.04, 2.12, 2.72, C.nightSurface, '3D4650', 0.14);
  addText(slide, 'APP', 0.86, 2.34, 1.3, 0.35, { fontFace: FONT.display, fontSize: 23, bold: true, color: C.white });
  addText(slide, 'Android · Kotlin · Compose', 0.86, 2.84, 1.55, 0.32, { fontSize: 9, color: 'B8C2C8', valign: 'top' });
  addText(slide, 'Map · Search\nTrip · Photo · H3', 0.86, 3.62, 1.45, 0.5, { fontSize: 10, color: C.white, valign: 'top' });
  addArrow(slide, 2.76, 3.74, 3.24, 3.74, C.yellow, 'REST', 10);
  addCard(slide, 0.58, 5.0, 2.12, 1.54, C.nightSurface, '3D4650', 0.14);
  addText(slide, 'EXTERNAL IDP', 0.82, 5.24, 1.5, 0.18, { fontFace: FONT.mono, fontSize: 10, bold: true, color: C.yellow });
  addPill(slide, 'Naver', 0.78, 5.7, 0.56, '2DB400', C.white);
  addPill(slide, 'Kakao', 1.38, 5.7, 0.56, 'F5D533', C.ink);
  addPill(slide, 'Google', 1.98, 5.7, 0.58, '6E9DBD', C.white);
  addText(slide, 'OAuth → APP', 0.82, 6.17, 1.3, 0.16, { fontFace: FONT.mono, fontSize: 10, color: 'B8C2C8' });
  addCard(slide, 3.38, 1.99, 9.35, 4.56, C.night, '35424B', 0.14);
  addText(slide, 'GOOGLE CLOUD', 3.72, 2.3, 3.2, 0.34, { fontFace: FONT.display, fontSize: 21, bold: true, color: C.white });
  addText(slide, 'Cloud Run · Cloud SQL · Cloud Storage · AI services', 3.75, 2.76, 5.3, 0.2, {
    fontFace: FONT.mono, fontSize: 10, color: 'B7C1C7',
  });
  slide.addShape(pptx.ShapeType.line, {
    x: 3.24, y: 3.04, w: 0, h: 0.7, line: { color: C.yellow, width: 1.5 },
  });
  slide.addShape(pptx.ShapeType.line, {
    x: 3.24, y: 3.04, w: 3.41, h: 0, line: { color: C.yellow, width: 1.5 },
  });
  slide.addShape(pptx.ShapeType.line, {
    x: 6.65, y: 3.04, w: 0, h: 0.21,
    line: { color: C.yellow, width: 1.5, endArrowType: 'triangle' },
  });
  addText(slide, 'REST / API', 4.42, 2.86, 1.15, 0.16, { fontFace: FONT.mono, fontSize: 10, color: C.yellow, align: 'center' });
  addNode(slide, 'Cloud SQL', 'PostgreSQL · places · trips', 3.72, 3.25, 2.65, C.blue, true, { height: 0.86, detailFontSize: 10 });
  addNode(slide, 'Spring Backend', 'auth · API · policy', 6.65, 3.25, 2.65, C.coral, true, { height: 0.86, detailFontSize: 10 });
  addNode(slide, 'GCS', 'original · thumbnail · signed URL', 9.58, 3.25, 2.65, C.olive, true, { height: 0.86, detailFontSize: 10 });
  slide.addShape(pptx.ShapeType.line, {
    x: 6.42, y: 3.62, w: 0.16, h: 0,
    line: { color: C.yellow, width: 1.6, beginArrowType: 'triangle' },
  });
  addText(slide, 'data', 5.98, 3.16, 0.65, 0.16, { fontFace: FONT.mono, fontSize: 10, color: C.yellow, align: 'center' });
  addArrow(slide, 9.35, 3.62, 9.5, 3.62, C.yellow);
  addText(slide, 'media', 9.12, 3.16, 0.75, 0.16, { fontFace: FONT.mono, fontSize: 10, color: C.yellow, align: 'center' });
  addArrow(slide, 7.98, 4.0, 7.98, 4.22, C.yellow, 'calls', 10);
  addCard(slide, 3.72, 4.28, 8.51, 0.24, C.nightSurface, C.nightSurface, 0.04);
  addText(slide, 'BACKEND MODULE CALLS', 7.0, 4.31, 1.95, 0.14, { fontFace: FONT.mono, fontSize: 9.5, color: 'B7C1C7', align: 'center' });
  addNode(slide, 'PLANNER', 'time · route · constraints', 3.72, 4.67, 1.95, C.yellow, true, { height: 0.86, detailFontSize: 10, labelFontSize: 11.5 });
  addNode(slide, 'QWEN AI', 'Vertex AI · embedding', 5.92, 4.67, 1.95, C.coral, true, { height: 0.86, detailFontSize: 10, labelFontSize: 11.5 });
  addNode(slide, 'RECOMMEND', '30 → 10 reranking', 8.12, 4.67, 1.95, C.yellow, true, { height: 0.86, detailFontSize: 10, labelFontSize: 11.5 });
  addNode(slide, 'H3 LOG', 'cell · photo · visit', 10.32, 4.67, 1.91, C.blue, true, { height: 0.86, detailFontSize: 10, labelFontSize: 11.5 });
  addArrow(slide, 4.7, 4.52, 4.7, 4.65, C.yellow, 'route', 10);
  addArrow(slide, 6.9, 4.52, 6.9, 4.65, C.yellow, 'embed', 10);
  addArrow(slide, 9.1, 4.52, 9.1, 4.65, C.yellow, 'rank', 10);
  addArrow(slide, 11.28, 4.52, 11.28, 4.65, C.yellow, 'record', 10);
  addText(slide, 'provider keys backend-only  /  place_id · provenance · cell_id', 3.75, 6.13, 8.2, 0.2, {
    fontFace: FONT.mono, fontSize: 10, color: 'B7C1C7',
  });
  addPage(slide, 16, true);
  addNotes(slide, '앱은 Spring Backend를 통해 Google Cloud의 데이터, 스토리지, Planner, Qwen/Vertex AI, Recommendation 모듈과 연결됩니다.');
}

function demoFlow() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addTopline(slide, '16 / Demo', '시연은 한 번의 여행을 따라갑니다', '영상은 후편집으로 삽입하고, 발표에서는 흐름의 의미를 끊김 없이 보여줍니다.');
  const items = [
    ['1', '성향', '6축 점수', C.coral, C.paleCoral],
    ['2', '발견', '30 → 10 추천', C.yellow, C.paleYellow],
    ['3', '계획', '일정 · 동선', C.blue, C.paleBlue],
    ['4', '변경', '자연어 요청', C.olive, C.paleOlive],
    ['5', '기록', '사진 · H3 cell', C.coral, C.paleCoral],
  ];
  items.forEach(([number, title, detail, accent, fill], index) => {
    const x = 0.72 + index * 2.45;
    addCard(slide, x, 2.18, 1.92, 2.2, fill, C.white, 0.15);
    slide.addShape(pptx.ShapeType.ellipse, { x: x + 0.68, y: 2.52, w: 0.56, h: 0.56, fill: { color: accent }, line: { color: accent } });
    addText(slide, number, x + 0.68, 2.68, 0.56, 0.2, { fontSize: 12, bold: true, color: C.ink, align: 'center' });
    addText(slide, title, x + 0.24, 3.45, 1.45, 0.25, { fontSize: 15, bold: true, align: 'center' });
    addText(slide, detail, x + 0.18, 3.9, 1.58, 0.22, { fontFace: FONT.mono, fontSize: 7.8, color: C.muted, align: 'center' });
    if (index < 4) addArrow(slide, x + 1.98, 3.02, x + 2.35, 3.02, C.ink);
  });
  addCard(slide, 1.0, 5.22, 11.2, 0.82, C.paleBlue, C.white, 0.13);
  addText(slide, '“비가 오니까 다음 장소를 실내로 바꿔줘”  →  일정 재구성  →  LOG', 1.25, 5.49, 10.7, 0.23, {
    fontSize: 14, bold: true, color: C.ink, align: 'center',
  });
  addPage(slide, 17);
  addNotes(slide, '성향을 분석하고 장소를 추천받아 일정을 만들고, 여행 중에는 자연어로 수정하며, 마지막에는 H3 기록으로 남깁니다.');
}

function closing() {
  const slide = pptx.addSlide();
  slide.background = { color: C.cream };
  addText(slide, 'STOG', 0.76, 1.08, 5.5, 0.8, { fontFace: FONT.display, fontSize: 44, bold: true, color: C.ink });
  addText(slide, '나를 따라 그려지는\n맞춤형 여행 지도', 0.8, 2.17, 6.0, 0.96, {
    fontSize: 27, bold: true, color: C.ink, valign: 'top',
  });
  addText(slide, '기록이 쌓일수록, 다음 여행은 조금 더 나에게 가까워집니다.', 0.82, 4.24, 7.4, 0.3, {
    fontSize: 15, color: C.muted,
  });
  addPill(slide, '전북 관광자원  ×  나의 취향  ×  실제 기록', 0.82, 5.07, 3.62, C.paleYellow, C.ink);
  slide.addShape(pptx.ShapeType.line, { x: 0.82, y: 6.27, w: 11.68, h: 0, line: { color: C.yellow, width: 2.4, endArrowType: 'triangle' } });
  [0.82, 3.15, 5.48, 7.81, 10.14, 12.42].forEach((x) => slide.addShape(pptx.ShapeType.ellipse, {
    x: x - 0.08, y: 6.18, w: 0.18, h: 0.18, fill: { color: C.yellow }, line: { color: C.yellow },
  }));
  addText(slide, '여행은 끝나도, 추억까지 끝나는 건 아니니까요.', 0.82, 6.38, 8.5, 0.32, {
    fontFace: FONT.display, fontSize: 17, italic: true, color: C.coral,
  });
  addText(slide, 'STOBEE  /  THANK YOU', 10.0, 6.7, 2.5, 0.18, {
    fontFace: FONT.mono, fontSize: 8, color: C.muted, align: 'right', charSpacing: 1,
  });
  addPage(slide, 18);
  addNotes(slide, '기록이 쌓일수록 STOG는 사용자를 더 잘 이해합니다. 감사합니다.');
}

module.exports = { solutionFlow, architecture, demoFlow, closing };
