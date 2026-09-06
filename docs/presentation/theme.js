const pptxgen = require('pptxgenjs');

const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'STOBEE';
pptx.subject = 'STOG 최종 발표';
  pptx.title = 'STOG — 나를 따라 그려지는 맞춤형 여행 지도';
pptx.company = 'STOBEE';
pptx.lang = 'ko-KR';
pptx.theme = {
  headFontFace: 'Aptos Display',
  bodyFontFace: 'Malgun Gothic',
  lang: 'ko-KR',
};

const C = Object.freeze({
  cream: 'F7F8F5',
  paper: 'FFFFFF',
  ink: '13191D',
  muted: '64717B',
  yellow: 'F5B82E',
  blue: '739FBB',
  olive: 'A7B789',
  coral: 'D88164',
  paleYellow: 'FFF1C7',
  paleBlue: 'E7F0F5',
  paleOlive: 'EDF2E6',
  paleCoral: 'FBE7DF',
  night: '0F1418',
  nightSurface: '1B252D',
  line: 'DCE3E6',
  white: 'FFFFFF',
  black: '000000',
});

const FONT = Object.freeze({
  display: 'Aptos Display',
  body: 'Malgun Gothic',
  mono: 'Cascadia Mono',
});

const SW = 13.333;
const SH = 7.5;

function addNotes(slide, notes) {
  if (typeof slide.addNotes === 'function') slide.addNotes(notes);
}

function addPage(slide, number, dark = false) {
  slide.addText(`STOG  /  ${String(number).padStart(2, '0')}`, {
    x: 0.58, y: 7.08, w: 2.0, h: 0.16, fontFace: FONT.mono,
    fontSize: 7.5, color: dark ? C.muted : C.muted, margin: 0,
    breakLine: false, fit: 'shrink',
  });
}

function addTopline(slide, eyebrow, title, subtitle, dark = false) {
  const ink = dark ? C.white : C.ink;
  slide.addText(eyebrow.toUpperCase(), {
    x: 0.58, y: 0.42, w: 3.2, h: 0.17, fontFace: FONT.mono,
    fontSize: 8, bold: true, color: C.yellow, charSpacing: 1.2, margin: 0,
  });
  slide.addText(title, {
    x: 0.58, y: 0.72, w: 8.8, h: 0.64, fontFace: FONT.display,
    fontSize: 26, bold: true, color: ink, margin: 0, fit: 'shrink',
  });
  if (subtitle) {
    slide.addText(subtitle, {
      x: 0.6, y: 1.47, w: 8.9, h: 0.28, fontFace: FONT.body,
      fontSize: 11.5, color: dark ? 'B7C0C7' : C.muted, margin: 0,
      fit: 'shrink',
    });
  }
}

function addRule(slide, x, y, w, color = C.line, transparency = 0) {
  slide.addShape(pptx.ShapeType.line, {
    x, y, w, h: 0, line: { color, transparency, width: 0.8 },
  });
}

function addText(slide, text, x, y, w, h, options = {}) {
  slide.addText(text, {
    x, y, w, h, fontFace: options.fontFace || FONT.body,
    fontSize: options.fontSize || 12, color: options.color || C.ink,
    bold: options.bold || false, italic: options.italic || false,
    margin: options.margin === undefined ? 0.04 : options.margin,
    breakLine: false, fit: options.fit || 'shrink',
    valign: options.valign || 'mid', align: options.align || 'left',
    paraSpaceAfterPt: options.paraSpaceAfterPt || 0,
    charSpacing: options.charSpacing || 0,
  });
}

function addPill(slide, label, x, y, w, fill, color = C.ink) {
  slide.addShape(pptx.ShapeType.roundRect, {
    x, y, w, h: 0.34, rectRadius: 0.08,
    fill: { color: fill }, line: { color: fill },
  });
  addText(slide, label, x, y + 0.01, w, 0.28, {
    fontSize: 9, bold: true, color, align: 'center',
  });
}

function addCard(slide, x, y, w, h, fill = C.paper, line = C.line, radius = 0.12) {
  slide.addShape(pptx.ShapeType.roundRect, {
    x, y, w, h, rectRadius: radius,
    fill: { color: fill }, line: { color: line, width: 0.7 },
  });
}

function addNode(slide, label, detail, x, y, w, accent, dark = false, options = {}) {
  const nodeHeight = options.height || 0.75;
  const labelFontSize = options.labelFontSize || 12;
  const detailFontSize = options.detailFontSize || 8.3;
  const fill = dark ? C.nightSurface : C.paper;
  const ink = dark ? C.white : C.ink;
  addCard(slide, x, y, w, nodeHeight, fill, dark ? '37434D' : C.line);
  slide.addShape(pptx.ShapeType.ellipse, {
    x: x + 0.16, y: y + 0.17, w: 0.38, h: 0.38,
    fill: { color: accent }, line: { color: accent },
  });
  const icon = label.includes('Backend') ? 'API'
    : label.includes('SQL') ? 'DB'
      : label === 'GCS' ? 'GCS'
        : label.includes('QWEN') ? 'AI'
          : label.includes('RECOMMEND') ? 'R'
            : label.includes('PLANNER') ? 'P'
              : label.includes('Google') ? 'G'
                : label.includes('Tour') ? 'T'
                  : label.includes('STOG') ? 'S'
                    : label === '추천' ? 'R'
                      : label === '일정' ? 'P'
                        : label === '기록' ? 'L'
                          : label.slice(0, 1).toUpperCase();
  addText(slide, icon, x + 0.16, y + 0.25, 0.38, 0.14, {
    fontFace: FONT.mono, fontSize: icon.length > 2 ? 5.2 : 7.5, bold: true,
    color: accent === C.yellow ? C.ink : C.white, align: 'center',
  });
  addText(slide, label, x + 0.65, y + 0.12, w - 0.78, 0.24, {
    fontSize: labelFontSize, bold: true, color: ink,
  });
  addText(slide, detail, x + 0.65, y + 0.4, w - 0.78, nodeHeight - 0.46, {
    fontSize: detailFontSize, color: dark ? 'AAB5BD' : C.muted,
  });
}

function addArrow(slide, x1, y1, x2, y2, color = C.yellow, label = '', labelFontSize = 7.2) {
  slide.addShape(pptx.ShapeType.line, {
    x: x1, y: y1, w: x2 - x1, h: y2 - y1,
    line: { color, width: 1.6, endArrowType: 'triangle' },
  });
  if (label) addText(slide, label, (x1 + x2) / 2 - 0.55, (y1 + y2) / 2 - 0.16, 1.1, 0.22, {
    fontFace: FONT.mono, fontSize: labelFontSize, color, align: 'center',
  });
}

module.exports = { pptx, C, FONT, SW, SH, addNotes, addPage, addTopline, addRule, addText, addPill, addCard, addNode, addArrow };
