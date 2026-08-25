/* legacy-recon 控制台 SPA（无构建依赖，原生 JS） */
"use strict";

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];

const state = {
  projectId: null,
  project: null,
  ws: null,
  entPage: 0,
};

/* ---------------- 工具 ---------------- */

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function toast(msg, kind = "") {
  const box = $("#toast-box");
  const el = document.createElement("div");
  el.className = "toast " + kind;
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 4200);
}

async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const j = await res.json();
      detail = j.detail || j.title || JSON.stringify(j);
    } catch (_) { /* 忽略 */ }
    throw new Error(detail);
  }
  const ct = res.headers.get("content-type") || "";
  return ct.includes("json") ? res.json() : res.text();
}

const apiGet = (p) => api("GET", p);
const apiPost = (p, b) => api("POST", p, b);
const apiPut = (p, b) => api("PUT", p, b);
const apiPatch = (p, b) => api("PATCH", p, b);
const apiDel = (p) => api("DELETE", p);

function shortId(id, maxLen = 26) {
  if (id == null) return "—";
  const s = String(id);
  const t = s.includes("::") ? "::" + s.split("::").pop() : s;
  return t.length > maxLen ? "…" + t.slice(-(maxLen - 1)) : t;
}

function fmtLoc(loc) {
  if (!loc || !loc.file) return "—";
  return `${loc.file}#L${loc.startLine}` + (loc.endLine > loc.startLine ? `-${loc.endLine - 1}` : "");
}

function fmtDur(started, finished) {
  if (!started || !finished) return "—";
  const d = new Date(finished) - new Date(started);
  return d < 1000 ? d + "ms" : (d / 1000).toFixed(1) + "s";
}

/* ---------------- 健康状态 ---------------- */

async function checkHealth() {
  const dot = $("#server-status");
  try {
    const h = await apiGet("/actuator/health");
    dot.classList.toggle("ok", h.status === "UP");
    dot.classList.toggle("bad", h.status !== "UP");
    dot.title = "后端健康：" + h.status;
  } catch (_) {
    dot.classList.add("bad");
    dot.classList.remove("ok");
    dot.title = "后端不可达";
  }
}

/* ---------------- 项目 ---------------- */

async function loadProjects() {
  const ps = await apiGet("/api/v1/projects");
  const ul = $("#project-list");
  ul.innerHTML = "";
  for (const p of ps) {
    const li = document.createElement("li");
    const config = (() => { try { return JSON.parse(p.configJson || "{}"); } catch (_) { return {}; } })();
    const lang = config.language || "java";
    li.innerHTML = `<span class="p-name">${esc(p.name)}<span class="p-lang">${esc(lang)}</span></span>
      <span class="p-meta">${esc(p.rootPath || "")}</span>
      ${config.archived ? '<span class="p-meta">已归档</span>' : ""}`;
    li.onclick = () => selectProject(p.id);
    if (p.id === state.projectId) li.classList.add("active");
    ul.appendChild(li);
  }
}

async function selectProject(id) {
  state.projectId = id;
  state.project = await apiGet(`/api/v1/projects/${id}`);
  $("#project-header").classList.remove("hidden");
  $("#tabs").classList.remove("hidden");
  $("#project-name").textContent = state.project.name;
  $("#project-meta").textContent =
    `${state.project.rootPath} · ${(JSON.parse(state.project.configJson || "{}").language) || "java"}` +
    (state.project.archived ? " · 已归档（只读）" : "");
  // 清空上一项目的残留面板内容
  $("#doc-content").innerHTML = "";
  $("#doc-meta").textContent = "";
  $("#diagram-box").innerHTML = "";
  $("#insight-list").innerHTML = "";
  $$("#project-list li").forEach((li) => li.classList.remove("active"));
  await loadProjects();
  connectWs(id);
  // 切回项目时刷新各 tab 静态数据
  switchTab("run");
}

async function importProject() {
  const f = $("#form-new-project");
  const data = Object.fromEntries(new FormData(f).entries());
  if (!data.root) return toast("请填写项目根目录", "err");
  try {
    const p = await apiPost("/api/v1/projects", data);
    toast(`项目 ${p.id} 已导入，共 ${p.fileCount} 个文件`, "ok");
    f.closest("dialog").close();
    await loadProjects();
    await selectProject(p.id);
  } catch (e) { toast("导入失败：" + e.message, "err"); }
}

async function archiveProject() {
  if (!confirm("归档后项目只读（禁止新 run）？")) return;
  try {
    await apiPost(`/api/v1/projects/${state.projectId}/archive`, {});
    toast("已归档", "ok");
    await selectProject(state.projectId);
  } catch (e) { toast(e.message, "err"); }
}

async function deleteProject() {
  if (!confirm("删除项目（软删除，可恢复）？")) return;
  try {
    await apiDel(`/api/v1/projects/${state.projectId}`);
    toast("已删除", "ok");
    state.projectId = null;
    $("#project-header").classList.add("hidden");
    $("#tabs").classList.add("hidden");
    closeWs();
    await loadProjects();
  } catch (e) { toast(e.message, "err"); }
}

/* ---------------- Tabs ---------------- */

const tabLoaders = {
  run: loadRunTab,
  entities: loadEntities,
  relations: loadRelations,
  insights: loadInsights,
  docs: () => {},
  diagram: () => {},
};

function switchTab(name) {
  $$("#tabs .tab").forEach((t) => t.classList.toggle("active", t.dataset.tab === name));
  $$(".tab-body").forEach((b) => b.classList.add("hidden"));
  $(`#tab-${name}`).classList.remove("hidden");
  (tabLoaders[name] || (() => {}))();
}

/* ---------------- WebSocket ---------------- */

function connectWs(projectId) {
  closeWs();
  const scheme = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${scheme}://${location.host}/api/v1/ws/projects/${projectId}`);
  state.ws = ws;
  ws.onmessage = (ev) => {
    let e; try { e = JSON.parse(ev.data); } catch (_) { e = { raw: ev.data }; }
    const log = $("#ws-log");
    const ts = new Date().toLocaleTimeString();
    log.textContent += `[${ts}] ${e.type || "?"} ${e.stage || ""} ${e.message || ""} ${e.payload ? JSON.stringify(e.payload) : ""}\n`;
    log.scrollTop = log.scrollHeight;
    if (e.type === "run_completed" || e.type === "stage_error") loadRunHistory();
  };
  ws.onclose = () => { /* 静默；网络层重连由用户刷新触发 */ };
}

function closeWs() {
  if (state.ws) { try { state.ws.close(); } catch (_) {} state.ws = null; }
}

/* ---------------- 运行 ---------------- */

function loadRunTab() {
  loadRunHistory();
  $("#ws-log").textContent = `// 已连接事件流（projectId=${state.projectId}）\n`;
}

async function runPipeline() {
  const stages = ["parse", "enrich", "generate"].filter((s) => $(`#st-${s}`).checked);
  const forceFull = $("#st-force").checked;
  if (!stages.length) return toast("至少选择一个阶段", "err");
  const btn = $("#btn-run-go");
  btn.disabled = true;
  $("#run-result").textContent = `运行中：${stages.join(" → ")}${forceFull ? "（全量）" : ""} …`;
  try {
    const r = await apiPost(`/api/v1/projects/${state.projectId}/runs`, { stages, forceFull });
    $("#run-result").textContent =
      `完成：${r.status} · 实体 ${r.entityCount} · 关系 ${r.relationCount} · 问题 ${r.issueCount}` +
      (Object.keys(r.stageErrors || {}).length ? " · 错误 " + JSON.stringify(r.stageErrors) : "");
    toast(`管道 ${r.status}`, r.status === "success" ? "ok" : "err");
  } catch (e) {
    $("#run-result").textContent = "失败：" + e.message;
    toast(e.message, "err");
  } finally {
    btn.disabled = false;
    loadRunHistory();
  }
}

async function loadRunHistory() {
  if (!state.projectId) return;
  const runs = await apiGet(`/api/v1/projects/${state.projectId}/runs?limit=30`);
  const tbody = $("#run-history tbody");
  tbody.innerHTML = "";
  for (const r of runs) {
    let stats = {};
    try { stats = JSON.parse(r.stats_json || "{}"); } catch (_) {}
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td class="mono">${esc(shortId(r.id, 12))}</td>
      <td>${esc(r.trigger || "—")}</td>
      <td><span class="badge ${esc(r.status)}">${esc(r.status)}</span></td>
      <td>${esc(stats.entityCount ?? 0)} / ${esc(stats.relationCount ?? 0)} / ${esc(stats.issueCount ?? 0)}</td>
      <td class="muted">${esc((r.started_at || "").replace("T", " ").slice(0, 19))}</td>
      <td class="muted">${fmtDur(r.started_at, r.finished_at)}</td>`;
    tbody.appendChild(tr);
  }
}

/* ---------------- 实体 ---------------- */

async function loadEntities() {
  if (!state.projectId) return;
  const type = $("#ent-type").value;
  const q = $("#ent-q").value.trim();
  const limit = 50;
  const params = new URLSearchParams({ limit, offset: state.entPage * limit });
  if (type) params.set("type", type);
  if (q) params.set("q", q);
  const ents = await apiGet(`/api/v1/projects/${state.projectId}/entities?${params}`);
  const tbody = $("#entity-table tbody");
  tbody.innerHTML = "";
  for (const e of ents) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><span class="badge">${esc(e.type)}</span></td>
      <td>${esc(e.name)}</td>
      <td class="mono">${esc(e.qualifiedName || "")}</td>
      <td class="mono muted">${fmtLoc(e.location)}</td>
      <td class="mono muted">${esc((e.signature || "").slice(0, 80))}</td>`;
    tr.style.cursor = "pointer";
    tr.title = "ID：" + e.id;
    tbody.appendChild(tr);
  }
  $("#ent-pager").innerHTML =
    `<button class="mini" ${state.entPage === 0 ? "disabled" : ""} id="ent-prev">← 上一页</button>
     <span>第 ${state.entPage + 1} 页（${ents.length} 条 ${ents.length < limit ? "· 已到底" : ""}）</span>
     <button class="mini" ${ents.length < limit ? "disabled" : ""} id="ent-next">下一页 →</button>`;
  $("#ent-prev").onclick = () => { state.entPage = Math.max(0, state.entPage - 1); loadEntities(); };
  $("#ent-next").onclick = () => { state.entPage++; loadEntities(); };
}

/* ---------------- 关系 ---------------- */

async function loadRelations() {
  if (!state.projectId) return;
  const type = $("#rel-type").value;
  const params = new URLSearchParams();
  if (type) params.set("type", type);
  const rels = await apiGet(`/api/v1/projects/${state.projectId}/relations?${params}`);
  const tbody = $("#relation-table tbody");
  tbody.innerHTML = "";
  for (const r of rels) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><span class="badge">${esc(r.type)}</span></td>
      <td class="mono">${esc(shortId(r.sourceId, 40))}</td>
      <td class="mono">${esc(shortId(r.targetId || r.externalTarget || "ext", 40))}</td>
      <td class="mono muted">${fmtLoc(r.location)}</td>`;
    tbody.appendChild(tr);
  }
}

/* ---------------- 洞察 ---------------- */

async function loadInsights() {
  if (!state.projectId) return;
  const status = $("#ins-status").value;
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  const list = await apiGet(`/api/v1/projects/${state.projectId}/insights?${params}`);
  const box = $("#insight-list");
  box.innerHTML = "";
  if (!list.length) {
    box.innerHTML = '<div class="muted">暂无洞察（enrich 阶段运行后由规则引擎/LLM 产出）。</div>';
    return;
  }
  for (const i of list) {
    const card = document.createElement("div");
    card.className = "insight-card";
    let content = i.content || "{}";
    try { content = JSON.stringify(JSON.parse(content), null, 2); } catch (_) {}
    card.innerHTML = `
      <div>
        <div class="i-head">
          <span class="i-kind">${esc(i.kind)}</span>
          <span class="badge ${esc(i.status)}">${esc(i.status)}</span>
          ${i.entityId ? `<span class="mono muted">${esc(shortId(i.entityId, 36))}</span>` : ""}
          <span class="muted">${esc(i.confidence ?? "")}</span>
        </div>
        <pre>${esc(content)}</pre>
      </div>
      <div class="i-actions">
        <button data-act="approve" data-id="${esc(i.id)}" class="mini primary">通过</button>
        <button data-act="reject" data-id="${esc(i.id)}" class="mini danger">驳回</button>
      </div>`;
    card.querySelectorAll("button").forEach((b) =>
      b.onclick = () => auditInsight(b.dataset.id, b.dataset.act));
    box.appendChild(card);
  }
}

async function auditInsight(insightId, action) {
  try {
    const r = await apiPatch(`/api/v1/projects/${state.projectId}/insights/${insightId}`, { action });
    toast(`洞察 ${r.status}`, "ok");
    loadInsights();
  } catch (e) { toast(e.message, "err"); }
}

/* ---------------- 文档 ---------------- */

async function generateDoc() {
  const docType = $("#doc-type").value;
  const btn = $("#btn-doc-gen");
  btn.disabled = true;
  try {
    const r = await apiPost(`/api/v1/projects/${state.projectId}/generate`,
      { docType, format: "md" });
    const fails = r.validationFailures || [];
    $("#doc-meta").innerHTML =
      `exportId：<span class="mono">${esc(r.exportId)}</span> ·
       ${fails.length ? `<span class="badge failed">${fails.length} 个校验失败</span>` : '<span class="badge success">校验通过</span>'}`;
    $("#doc-content").innerHTML = renderMd(r.markdown || "");
    toast("文档已生成", "ok");
  } catch (e) {
    toast("生成失败：" + e.message, "err");
  } finally {
    btn.disabled = false;
  }
}

/* 极简 Markdown 渲染（标题/表格/代码块/引用/列表/链接/行内代码/粗体） */
function renderMd(md) {
  const lines = String(md).split("\n");
  const out = [];
  let inCode = false, inList = false, tableBuf = [], tableOn = false;
  const inline = (s) => esc(s)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  const flushTable = () => {
    if (!tableBuf.length) return;
    let t = '<table class="grid"><tbody>';
    for (let i = 0; i < tableBuf.length; i++) {
      const cells = tableBuf[i].replace(/^\||\|$/g, "").split("|").map((c) => c.trim());
      const tag = i === 0 ? "th" : "td";
      t += "<tr>" + cells.map((c) => `<${tag}>${inline(c)}</${tag}>`).join("") + "</tr>";
    }
    out.push(t + "</tbody></table>");
    tableBuf = [];
    tableOn = false;
  };
  for (const line of lines) {
    if (line.startsWith("```")) {
      if (inCode) { out.push("</code></pre>"); inCode = false; }
      else { flushTable(); out.push("<pre><code>"); inCode = true; }
      continue;
    }
    if (inCode) { out.push(esc(line)); continue; }
    // 表格：暂存表头与数据行，分隔行只作标记
    if (/^\|.+\|$/.test(line)) {
      if (/^\|[\s:|-]+\|$/.test(line)) { tableOn = true; continue; }
      if (tableOn || tableBuf.length === 0) tableBuf.push(line);
      continue;
    }
    flushTable();
    if (inList && !/^[-*] /.test(line)) { out.push("</ul>"); inList = false; }
    if (line.startsWith("# ")) { out.push(`<h1>${inline(line.slice(2))}</h1>`); continue; }
    if (line.startsWith("## ")) { out.push(`<h2>${inline(line.slice(3))}</h2>`); continue; }
    if (line.startsWith("### ")) { out.push(`<h3>${inline(line.slice(4))}</h3>`); continue; }
    if (line.startsWith("> ")) { out.push(`<blockquote>${inline(line.slice(2))}</blockquote>`); continue; }
    if (/^[-*] /.test(line)) {
      if (!inList) { out.push("<ul>"); inList = true; }
      out.push(`<li>${inline(line.slice(2))}</li>`); continue;
    }
    if (line.trim() === "") continue;
    out.push(`<p>${inline(line)}</p>`);
  }
  if (inCode) out.push("</code></pre>");
  flushTable();
  if (inList) out.push("</ul>");
  return out.join("\n");
}

/* ---------------- 图谱 ---------------- */

async function renderDiagram() {
  const kind = $("#diagram-type").value;
  const box = $("#diagram-box");
  box.innerHTML = '<div class="muted">加载中…</div>';
  try {
    const [ents, rels] = await Promise.all([
      apiGet(`/api/v1/projects/${state.projectId}/entities?limit=10000`),
      apiGet(`/api/v1/projects/${state.projectId}/relations`),
    ]);
    if (!ents.length) { box.innerHTML = '<div class="muted">暂无实体（先运行 parse）。</div>'; return; }
    box.innerHTML = "";
    box.appendChild(kind === "call" ? callGraphSvg(ents, rels) : classGraphSvg(ents, rels));
  } catch (e) {
    box.innerHTML = `<div class="muted">渲染失败：${esc(e.message)}</div>`;
  }
}

function svgLayout(ents, rels, edgeFilter) {
  // 简单分层布局：实体按类型分层，关系作为边
  const byId = new Map(ents.map((e) => [e.id, e]));
  const nodes = ents.filter((e) => e.id).slice(0, 80);
  const layers = [[], [], []];
  const nodeIdx = new Map();
  nodes.forEach((e, i) => {
    const l = ["Class", "Interface", "Struct", "Union", "Enum"].includes(e.type) ? 0
      : ["Method", "Function", "Constructor", "Destructor"].includes(e.type) ? 1 : 2;
    layers[l].push(e);
    nodeIdx.set(e.id, i);
  });
  const W = 160, H = 46, GX = 70, GY = 24, LX = 130, M = 90;
  const cols = Math.max(...layers.map((l) => l.length), 1);
  const width = Math.max(760, layers.length * (M + W) + 120);
  const height = Math.max(260, cols * (H + GY) + 100);
  const pos = new Map();
  layers.forEach((layer, li) => {
    layer.forEach((e, ri) => {
      pos.set(e.id, { x: M + li * (M + W), y: M + ri * (H + GY), e });
    });
  });
  const edges = [];
  const seenEdge = new Set();
  for (const r of rels) {
    if (!r.sourceId || !pos.has(r.sourceId)) continue;
    const t = r.targetId && pos.has(r.targetId) ? r.targetId : null;
    if (!t) continue;
    if (edgeFilter && !edgeFilter(r.type)) continue;
    const key = r.sourceId + "|" + r.type + "|" + t;
    if (seenEdge.has(key)) continue;
    seenEdge.add(key);
    edges.push({ ...r, sx: pos.get(r.sourceId), tx: pos.get(t) });
  }
  return { nodes, pos, edges, width, height, W, H, LX };
}

function svgEl(tag, attrs, children = []) {
  const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  children.forEach((c) => el.appendChild(c));
  return el;
}

function drawGraph(layout, labelOf, edgeTypes) {
  const svg = svgEl("svg", { viewBox: `0 0 ${layout.width} ${layout.height}`, xmlns: "http://www.w3.org/2000/svg" });
  const defs = svgEl("defs", {});
  const marker = svgEl("marker", { id: "arr", viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 5, markerHeight: 5, orient: "auto-start-reverse" });
  const marcher = svgEl("path", { d: "M 0 0 L 10 5 L 0 10 z", fill: "#4a5387" });
  marker.appendChild(marcher);
  defs.appendChild(marker);
  svg.appendChild(defs);
  for (const e of layout.edges) {
    const x1 = e.sx.x + layout.W, y1 = e.sx.y + 24, x2 = e.tx.x, y2 = e.tx.y + 24;
    svg.appendChild(svgEl("path", { d: `M ${x1} ${y1} C ${x1 + 55} ${y1}, ${x2 - 55} ${y2}, ${x2} ${y2}`, class: "dg-edge", "marker-end": "url(#arr)" }));
    svg.appendChild(svgEl("text", { x: (x1 + x2) / 2, y: (y1 + y2) / 2 - 5, class: "dg-edge-label", "text-anchor": "middle" }, [document.createTextNode(e.type)]));
  }
  for (const n of layout.nodes) {
    const p = layout.pos.get(n.id);
    if (!p) continue;
    const g = svgEl("g", { class: "dg-node", transform: `translate(${p.x},${p.y})` });
    const isRel = ["Method", "Function", "Constructor", "Destructor"].includes(n.type);
    g.appendChild(svgEl("rect", { width: layout.W, height: layout.H, rx: 8, class: isRel ? "dg-node relation" : "" }));
    const short = labelOf(n);
    g.appendChild(svgEl("text", { x: 10, y: 19 }, [document.createTextNode(n.type)]));
    g.appendChild(svgEl("text", { x: 10, y: 36, "font-weight": "600" }, [document.createTextNode(short.length > 22 ? short.slice(0, 21) + "…" : short)]));
    g.appendChild(svgEl("title", {}, [document.createTextNode(n.qualifiedName || n.id)]));
    svg.appendChild(g);
  }
  return svg;
}

function classGraphSvg(ents, rels) {
  const layout = svgLayout(ents, rels, (t) => ["CONTAINS", "INHERITS", "IMPLEMENTS", "DEPENDS_ON"].includes(t));
  return drawGraph(layout, (n) => n.name);
}

function callGraphSvg(ents, rels) {
  const layout = svgLayout(ents, rels, (t) => t === "CALLS");
  return drawGraph(layout, (n) => n.name || n.qualifiedName);
}

/* ---------------- LLM 配置 ---------------- */

async function openConfig() {
  const cfg = await apiGet("/api/v1/config");
  const f = $("#form-config");
  f.elements.enabled.checked = !!cfg.enabled;
  f.elements.baseUrl.value = cfg.baseUrl || "";
  f.elements.model.value = cfg.model || "";
  f.elements.apiKey.value = cfg.apiKey || "";
  f.elements.dailyTokenBudget.value = cfg.dailyTokenBudget ?? 0;
  f.elements.concurrencyLimit.value = cfg.concurrencyLimit ?? 4;
  f.elements.retryLimit.value = cfg.retryLimit ?? 3;
  f.elements.circuitBreakerFailures.value = cfg.circuitBreakerFailures ?? 10;
  $("#dlg-config").showModal();
}

async function saveConfig() {
  const f = $("#form-config");
  const el = f.elements;
  const body = {
    enabled: el.enabled.checked,
    baseUrl: el.baseUrl.value || null,
    model: el.model.value || null,
    apiKey: el.apiKey.value || null,
    dailyTokenBudget: Number(el.dailyTokenBudget.value || 0),
    concurrencyLimit: Number(el.concurrencyLimit.value || 4),
    retryLimit: Number(el.retryLimit.value || 3),
    circuitBreakerFailures: Number(el.circuitBreakerFailures.value || 10),
  };
  try {
    const r = await apiPut("/api/v1/config", body);
    toast(`LLM 配置已保存（enabled=${r.enabled}）`, "ok");
    f.closest("dialog").close();
  } catch (e) { toast("保存失败：" + e.message, "err"); }
}

/* ---------------- 事件绑定 ---------------- */

function bindEvents() {
  $("#btn-new-project").onclick = () => $("#dlg-new-project").showModal();
  $("#btn-import-confirm").onclick = (ev) => { ev.preventDefault(); importProject(); };
  $("#btn-run").onclick = () => switchTab("run");
  $("#btn-run-go").onclick = runPipeline;
  $("#btn-archive").onclick = archiveProject;
  $("#btn-delete").onclick = deleteProject;
  $("#btn-config").onclick = openConfig;
  $("#btn-config-save").onclick = (ev) => { ev.preventDefault(); saveConfig(); };
  $$("#tabs .tab").forEach((t) => (t.onclick = () => switchTab(t.dataset.tab)));
  $("#btn-ent-search").onclick = () => { state.entPage = 0; loadEntities(); };
  $("#ent-q").onkeydown = (e) => { if (e.key === "Enter") { state.entPage = 0; loadEntities(); } };
  $("#btn-rel-search").onclick = loadRelations;
  $("#btn-ins-search").onclick = loadInsights;
  $("#btn-doc-gen").onclick = generateDoc;
  $("#btn-diagram").onclick = renderDiagram;
}

/* ---------------- 启动 ---------------- */

(async function boot() {
  bindEvents();
  await checkHealth();
  setInterval(checkHealth, 30000);
  try {
    await loadProjects();
    const ps = await apiGet("/api/v1/projects");
    if (ps.length && !state.projectId) await selectProject(ps[0].id);
  } catch (e) {
    toast("无法连接后端：" + e.message, "err");
  }
})();