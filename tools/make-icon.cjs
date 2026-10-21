// 把铠甲鲸鱼原图导出为传统图标、自适应前景和主题图标，避免每套资源单独修改。
// 运行：node tools/make-icon.cjs。缺少依赖时自动安装到被 Git 忽略的本地工具目录。
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const ROOT = path.resolve(__dirname, "..");
const RESOURCES = path.join(ROOT, "app", "src", "main", "res");
const SHARP_VERSION = "0.35.5";
const DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const PALETTE = [[0, "#173B89"], [0.55, "#10285F"], [1, "#091A3B"]];

function loadSharp() {
  const cache = path.join(ROOT, ".toolchain", "icon-tools");
  const roots = [cache, ROOT];
  if (process.env.APPDATA) roots.push(path.join(process.env.APPDATA, "npm", "node_modules", "@deepseek-ai", "dsh"));
  for (const root of roots) {
    try {
      const entry = require.resolve("sharp", { paths: [root] });
      const metadata = require(path.join(path.dirname(entry), "..", "package.json"));
      if (metadata.version === SHARP_VERSION) return require(entry);
    } catch { /* 仅探测可复用的固定版本依赖，缺失时统一安装。 */ }
  }
  const npmCandidates = [
    path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npm-cli.js"),
    path.join(process.env.APPDATA || "", "npm", "node_modules", "npm", "bin", "npm-cli.js"),
  ];
  const npm = npmCandidates.find((candidate) => fs.existsSync(candidate));
  if (!npm) throw new Error("未找到 npm，请安装带 npm 的 Node.js 后重新运行图标生成器。");
  execFileSync(process.execPath, [npm, "install", "--prefix", cache, "--no-audit", "--no-fund", "--save-exact", `sharp@${SHARP_VERSION}`], { stdio: "inherit" });
  return require(require.resolve("sharp", { paths: [cache] }));
}

function writeResource(relative, content) {
  const target = path.join(RESOURCES, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, "utf8");
}

async function main() {
  const sharp = loadSharp();
  const source = path.join(__dirname, "launcher-armored-whale.png");
  if (!(await sharp(source).metadata()).hasAlpha) throw new Error("铠甲鲸鱼原图必须保留透明背景。");
  // 108单位画布中，鲸鱼最长边为56单位。传统图标裁取中央72单位，主体比例与自适应图标一致。
  const masterSize = 1296;
  const artwork = await sharp(source).trim({ threshold: 8 }).resize(672, 672, { fit: "inside" }).png().toBuffer();
  const artworkInfo = await sharp(artwork).metadata();
  const placement = { left: Math.round((masterSize - artworkInfo.width) / 2), top: Math.round((masterSize - artworkInfo.height) / 2) };
  const transparent = { create: { width: masterSize, height: masterSize, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } };
  const foreground = await sharp(transparent).composite([{ input: artwork, ...placement }]).png().toBuffer();
  const foregroundTarget = path.join(RESOURCES, "drawable-nodpi", "ic_launcher_armored_foreground.png");
  fs.mkdirSync(path.dirname(foregroundTarget), { recursive: true });
  await sharp(foreground).resize(432, 432).png().toFile(foregroundTarget);

  // Android主题图标只使用透明度。把金属明暗转换为透明度，保留甲片分区，不能只剩普通鲸鱼剪影。
  const { data, info } = await sharp(artwork).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  for (let index = 0; index < data.length; index += 4) {
    const luminance = (0.2126 * data[index] + 0.7152 * data[index + 1] + 0.0722 * data[index + 2]) / 255;
    data[index + 3] = Math.round(data[index + 3] * (0.18 + 0.82 * luminance));
    data[index] = data[index + 1] = data[index + 2] = 255;
  }
  const monochrome = await sharp(data, { raw: info }).png().toBuffer();
  const monochromeCanvas = await sharp(transparent).composite([{ input: monochrome, ...placement }]).png().toBuffer();
  await sharp(monochromeCanvas).resize(432, 432).png().toFile(path.join(RESOURCES, "drawable-nodpi", "ic_launcher_monochrome.png"));

  const header = '<?xml version="1.0" encoding="utf-8"?>\n';
  writeResource("drawable/ic_launcher_background.xml", `${header}<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="16" android:startY="12" android:endX="94" android:endY="100">
${PALETTE.map(([offset, color]) => `                <item android:offset="${offset}" android:color="${color}" />`).join("\n")}
            </gradient>
        </aapt:attr>
    </path>
</vector>
`);
  for (const version of [26, 33]) {
    writeResource(`mipmap-anydpi-v${version}/ic_launcher.xml`, `${header}<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_armored_foreground" />
${version >= 33 ? '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n' : ""}</adaptive-icon>
`);
  }

  const background = `<svg xmlns="http://www.w3.org/2000/svg" width="864" height="864" viewBox="18 18 72 72"><defs><linearGradient id="background" gradientUnits="userSpaceOnUse" x1="16" y1="12" x2="94" y2="100">${PALETTE.map(([offset, color]) => `<stop offset="${offset}" stop-color="${color}"/>`).join("")}</linearGradient></defs><rect x="18" y="18" width="72" height="72" rx="16" fill="url(#background)"/></svg>`;
  const visibleForeground = await sharp(foreground).extract({ left: 216, top: 216, width: 864, height: 864 }).png().toBuffer();
  const legacy = await sharp(Buffer.from(background)).composite([{ input: visibleForeground }]).png().toBuffer();
  for (const [density, size] of Object.entries(DENSITIES)) {
    await sharp(legacy).resize(size, size).png().toFile(path.join(RESOURCES, `mipmap-${density}`, "ic_launcher.png"));
    console.log(`已生成 ${density}：${size}×${size}`);
  }
  await sharp(legacy).resize(512, 512).png().toFile(path.join(ROOT, "docs", "icon-preview.png"));
  console.log("已生成铠甲鲸鱼、自适应图标、主题图标和 docs/icon-preview.png。");
}

main().catch((error) => {
  console.error(`图标生成失败：${error.message}`);
  process.exitCode = 1;
});
