# -*- coding: utf-8 -*-
"""Scan built-in tool declarations for wire-budget violations (desc>300, param desc>160)."""
import re, glob, os

LIMIT_DESC = 300
LIMIT_PARAM = 160

def trimindent(raw):
    lines = raw.split('\n')
    inds = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    m = min(inds) if inds else 0
    return '\n'.join(l[m:] if l.strip() else '' for l in lines).strip()

QUOTED = '"((?:[^"\\\\]|\\\\.)*)"'

def scan_file(path):
    src = open(path, encoding='utf-8').read()
    out = []
    for m in re.finditer(r'name = "([a-z_0-9]+)"', src):
        name = m.group(1)
        tail = src[m.end(): m.end() + 9000]
        dlen, dkind = None, None
        tm = re.search(r'description = """([\s\S]*?)"""', tail)
        sm = re.search(r'description = ' + QUOTED, tail)
        bm = re.search(r'description = buildString \{([\s\S]*?)\n(\s*)\}', tail)
        if tm and (not sm or tm.start() <= sm.start()):
            t = trimindent(tm.group(1))
            if '.replace' in tail[tm.end():tm.end() + 60]:
                t = t.replace('\n', ' ')
            dlen, dkind = len(t), 'triple'
        elif sm:
            t = sm.group(1).replace('\\n', ' ').replace('\\"', '"')
            dlen, dkind = len(t), 'single'
        elif bm:
            texts = re.findall(r'append\(\s*' + QUOTED, bm.group(1))
            if texts:
                t = ''.join(x.replace('\\n', ' ') for x in texts)
                dlen, dkind = len(t) + 20 * len(texts), 'buildString'
        if dlen is not None and dlen > LIMIT_DESC:
            pidx = tail.find('parameters')
            seg = tail[:pidx] if pidx > 0 else tail[:4000]
            has_sp = 'systemPrompt' in seg
            out.append(('DESC', name, dlen, dkind, 'sysPrompt' if has_sp else 'NO-sysPrompt', os.path.basename(path)))
        # 参数描述：单段字面量与 "+" 拼接字面量都要计（拼接形态曾被漏检）
        for pm in re.finditer(r'put\(\s*"description",\s*(?:\n\s*)?' + QUOTED, tail):
            start = pm.start()
            # 向后收集连续的 " + \n "literal" 拼接段
            rest = tail[pm.end():pm.end() + 1200]
            joined = pm.group(1)
            chain = re.match(r'(\s*\+\s*\n?\s*' + QUOTED + r')+', rest)
            if chain:
                for extra in re.findall(QUOTED, chain.group(0)):
                    joined += extra
            t = joined.replace('\\n', ' ')
            if len(t) > LIMIT_PARAM:
                out.append(('PARAM', name, len(t), 'param', t[:90], os.path.basename(path)))
    return out

files = sorted(glob.glob('app/src/main/java/me/rerere/rikkahub/data/ai/tools/**/*.kt', recursive=True)
               + glob.glob('knowledge/src/main/java/me/rerere/knowledge/tool/*.kt'))
allv = []
for f in files:
    allv.extend(scan_file(f))

allv.sort(key=lambda x: (x[0], -x[2]))
print("=== violations: %d ===" % len(allv))
for v in allv:
    if v[0] == 'DESC':
        print("[DESC>%d] %-32s %5d %-12s %-14s %s" % (LIMIT_DESC, v[1], v[2], v[3], v[4], v[5]))
    else:
        print("[PARAM>%d] %-30s %5d %-30s | %s" % (LIMIT_PARAM, v[1], v[2], v[5], v[4][:80]))
