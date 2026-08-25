import urllib.request
import json

BASE = "http://127.0.0.1:18085/api/v1"


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        r = urllib.request.urlopen(req)
        return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:500]


# 1. 导入 cpp 项目
st, p = call("POST", "/projects", {
    "root": "/workspace/legacy-recon/sample-cpp",
    "name": "C++ 银行示例",
    "language": "cpp",
})
print("import:", st, json.dumps(p, ensure_ascii=False)[:200])
pid = p["id"]

# 2. 运行管道（parse + enrich + generate）
st, r = call("POST", f"/projects/{pid}/runs", {"stages": ["parse", "enrich", "generate"]})
print("run:", st, json.dumps(r, ensure_ascii=False)[:400])

# 3. 校验 Class 实体
st, ents = call("GET", f"/projects/{pid}/entities?type=Class")
if isinstance(ents, list):
    names = [e.get("qualifiedName") or e.get("name") for e in ents]
    print("classes:", names)
else:
    print("entities:", st, str(ents)[:300])

# 4. 校验 INHERITS 关系
st, rels = call("GET", f"/projects/{pid}/relations?type=INHERITS")
if isinstance(rels, list):
    print("inherits:", [(x.get("sourceId"), "->", x.get("targetId")) for x in rels])
else:
    print("relations:", st, str(rels)[:300])

# 5. 统计全部关系类型
st, rels = call("GET", f"/projects/{pid}/relations")
if isinstance(rels, list):
    from collections import Counter
    print("relation types:", dict(Counter(x.get("type") for x in rels)))

# 6. 宏实体
st, ents = call("GET", f"/projects/{pid}/entities?type=Macro")
if isinstance(ents, list):
    print("macros:", [e.get("name") for e in ents])

# 7. 生成产物
st, gen = call("GET", f"/projects/{pid}/generate")
print("generate:", st, str(gen)[:300])
