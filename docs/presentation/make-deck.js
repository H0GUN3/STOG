const fs = require('node:fs');
const path = require('node:path');
const { pptx } = require('./theme');
const core = require('./slides-core');
const tech = require('./slides-tech');

const output = path.resolve(__dirname, '..', 'STOG_최종발표_PPT.pptx');

[
  core.cover,
  core.agenda,
  core.specialMoment,
  core.planningReality,
  core.scatteredInfo,
  core.changingPlan,
  core.photoContext,
  core.problemDefinition,
  core.jeonbuk,
  core.jeonbukChallenge,
  core.aiMarket,
  core.existingServices,
  core.bridge,
  core.preferenceEmbedding,
  tech.solutionFlow,
  tech.architecture,
  tech.demoFlow,
  tech.closing,
].forEach((buildSlide) => buildSlide());

fs.mkdirSync(path.dirname(output), { recursive: true });
pptx.writeFile({ fileName: output }).then(() => {
  process.stdout.write(`created ${output}\n`);
}).catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
