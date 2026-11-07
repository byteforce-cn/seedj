/**
 * 校验侧栏与 docs/guide 下的页面双向一致：
 * - 侧栏里的每个 /guide/... 都必须有对应文件；
 * - 每个 guide 页面（index 除外）都必须出现在侧栏里。
 *
 * 侧栏来源是 docs/.vitepress/config.mts 的 `link: "/guide/..."` 字面量。
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const root = new URL("../../", import.meta.url).pathname;
const docsDir = join(root, "docs");
const guideDir = join(docsDir, "guide");
const configPath = join(docsDir, ".vitepress", "config.mts");

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      out.push(...walk(full));
    } else if (entry.endsWith(".md")) {
      out.push(full);
    }
  }
  return out;
}

const linkToFile = (link) =>
  link.endsWith("/")
    ? join(docsDir, `${link.replace(/^\//, "")}index.md`)
    : join(docsDir, `${link.replace(/^\//, "")}.md`);

const config = readFileSync(configPath, "utf8");
const links = [...config.matchAll(/link:\s*["'](\/guide\/[^"']*)["']/g)].map(
  (m) => m[1],
);

const sidebarFiles = new Set(links.map(linkToFile));
const actualFiles = new Set(walk(guideDir));

const missingFromSidebar = [...actualFiles].filter(
  (file) => !sidebarFiles.has(file) && !file.endsWith("/guide/index.md"),
);
const danglingLinks = [...sidebarFiles].filter((file) => !actualFiles.has(file));

if (missingFromSidebar.length || danglingLinks.length) {
  for (const file of missingFromSidebar) {
    console.error(`未挂上侧栏: ${relative(root, file)}`);
  }
  for (const file of danglingLinks) {
    console.error(`侧栏指向不存在的页面: ${relative(root, file)}`);
  }
  process.exit(1);
}

console.log(`check-nav: OK (${actualFiles.size} pages, ${links.length} sidebar links)`);
