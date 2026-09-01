// make-icon.cjs — 深蓝鲸鱼 + 金属铠甲启动图标（寓意：带铠甲的 deepseek）。
// 蓝渐变圆角徽章 + 白色鲸鱼轮廓 + 银灰金属铠甲（护甲板/接缝/铆钉）。
// 依赖 sharp。运行：node tools/make-icon.cjs
const fs = require("fs");
const path = require("path");
const sharp = require("C:/Users/<user>/AppData/Roaming/npm/node_modules/@deepseek-ai/dsh/node_modules/sharp");

const ROOT = path.resolve(__dirname, "..");
const FAVICON = path.join(ROOT, "tools", "favicon-official.svg");
const DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

function composeSvg(pathData, size) {
  // 极简装甲鲸：白鲸轮廓完整突出，仅一条流畅金属背甲线 + 头部小护甲片（干净精致，装甲点缀不盖满全身）。
  const armor = `
  <defs>
    <linearGradient id="metal" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffffff"/>
      <stop offset="0.35" stop-color="#dfe6ef"/>
      <stop offset="1" stop-color="#aab4c4"/>
    </linearGradient>
  </defs>
  <g transform="translate(5 5) scale(0.8)">
    <path d="${pathData}" fill="#ffffff"/>
    <!-- 鲸身微光影（体积感） -->
    <path d="${pathData}" fill="#000000" opacity="0.05" transform="translate(0.5 0.7)"/>
    <!-- 极简背甲：一条收在背脊内的短金属护甲条（贴合身体，不过伸） -->
    <path d="M18,17 C23,14 30,13 36,15 C38,16 39,17 38,19 C35,16 29,15 24,15 C21,15 19,16 18,18 Z"
          fill="url(#metal)" stroke="#4d5666" stroke-width="0.5"/>
  </g>`;

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 50 50" width="${size}" height="${size}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#5686FE"/>
      <stop offset="1" stop-color="#4176E6"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="50" height="50" rx="11" fill="url(#bg)"/>
  ${armor}
</svg>`;
}

(async () => {
  const fav = fs.readFileSync(FAVICON, "utf8");
  const m = /<path[^>]*\sd="([^"]+)"/.exec(fav);
  if (!m) { console.error("[make-icon] no path d"); process.exit(1); }
  const d = m[1];
  let ok = true;
  for (const [name, px] of Object.entries(DENSITIES)) {
    const svg = composeSvg(d, px);
    try {
      const buf = await sharp(Buffer.from(svg)).png().toBuffer();
      const out = path.join(ROOT, "app", "src", "main", "res", `mipmap-${name}`, "ic_launcher.png");
      fs.mkdirSync(path.dirname(out), { recursive: true });
      fs.writeFileSync(out, buf);
      console.log(`[make-icon] wrote mipmap-${name}/ic_launcher.png (${px}px)`);
    } catch (e) { console.error(`[make-icon] FAIL ${name}: ${e.message}`); ok = false; }
  }
  try {
    const pv = await sharp(Buffer.from(composeSvg(d, 512))).png().toBuffer();
    fs.writeFileSync(path.join(ROOT, "docs", "screenshots", "whale_icon_preview.png"), pv);
    console.log("[make-icon] wrote preview");
  } catch (e) { console.error("[make-icon] preview FAIL " + e.message); }
  process.exit(ok ? 0 : 1);
})();
