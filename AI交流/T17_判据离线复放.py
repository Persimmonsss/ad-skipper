#!/usr/bin/env python3
# 离线复放：把真机抓到的分区签名喂给 v2.5.7 收紧后的判据，
# 对照 v2.5.5 当时的判定，看收紧会不会漏掉真广告、会不会放进误判。
import re
import sys
import collections

LINE = re.compile(
    r'^(?P<date>\S+) (?P<time>[\d:.]+) D/AdSkipService\((?P<pid>\d+)\): 探针Z '
    r'上左=(?P<ul>\d+) 上右=(?P<ur>\d+) 底中=(?P<bm>\d+) 底条=(?P<bb>\d+) '
    r'可见=(?P<vis>\d+) 横屏=(?P<land>true|false) '
    r'(?:播放器控件=(?P<pui>true|false) )?'
    r'(?:判广告|结构判定)=(?P<old>[01]) 文案\[(?P<texts>.*)\]$'
)

records = []
for path in sys.argv[1:]:
    with open(path, encoding='utf-8', errors='replace') as f:
        for raw in f:
            m = LINE.match(raw.strip())
            if m:
                records.append((path, m.groupdict()))

def judge(g):
    ul, ur, bb = int(g['ul']), int(g['ur']), int(g['bb'])
    land = g['land'] == 'true'
    texts = g['texts']
    player_ui = '全屏观看' in texts
    hint = '上滑继续' in texts
    ad_like = ul >= 1 and ur >= 1 and bb >= 1
    vertical_ad = (not land) and hint and (not player_ui) and ur >= 3 and bb >= 3
    return (land and ad_like) or vertical_ad, player_ui, hint

by_pid = collections.defaultdict(list)
for path, g in records:
    by_pid[g['pid']].append((g, path))

print('解析到 %d 条分区签名，按进程分组：' % len(records))
for pid, items in sorted(by_pid.items(), key=lambda kv: -len(kv[1])):
    print('  pid=%s  %d 条' % (pid, len(items)))

diff_flip_on = []
diff_flip_off = []
kept_ad = 0
total_ad_old = 0

# pid=13276 是 v2.4.6 探针包，它打印的「结构判定」只是占位、恒为 0，不是真判据，
# 单独一组看；pid=17539 才是 v2.5.5（判广告= 是当时真正生效的判据）。
BUILD = {'13276': 'v2.4.6 探针包（占位字段）', '17539': 'v2.5.5', '18959': 'v2.5.5'}

for _, g in records:
    old = g['old'] == '1'
    new, pui, hint = judge(g)
    if BUILD.get(g['pid']) == 'v2.5.5':
        if old:
            total_ad_old += 1
            if new:
                kept_ad += 1
        if old and not new:
            diff_flip_off.append((g, pui, hint))
    if new and not old:
        diff_flip_on.append((g, pui, hint))

print()
print('v2.5.5 真实判据判广告的行：%d，其中 v2.5.7 仍然判广告：%d（改判为否 %d）'
      % (total_ad_old, kept_ad, total_ad_old - kept_ad))
print('v2.5.7 判广告但旧字段为 0 的行：%d（含 v2.4.6 探针包的占位 0）' % len(diff_flip_on))

for label, rows in (('新判据多判', diff_flip_on), ('新判据改判为否', diff_flip_off)):
    if not rows:
        continue
    print()
    print('=== %s（%d 行）===' % (label, len(rows)))
    for g, pui, hint in rows[:12]:
        print('%s pid=%s 上左=%s 上右=%s 底条=%s 可见=%s 横屏=%s 播放器控件=%s 提示条=%s 旧=%s 文案[%s]'
              % (g['time'], g['pid'], g['ul'], g['ur'], g['bb'], g['vis'],
                 g['land'], pui, hint, g['old'], g['texts'][:70]))
