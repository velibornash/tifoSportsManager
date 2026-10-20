#!/usr/bin/env python3
"""
One question, settled numerically.

CleanDecisionEngine vetoes a shot with "SHOT: lane jammed" when

    calculateGoalOpenness(...) < 0.1
    calculateGoalOpenness = min over ALL opponents of (distance from that opponent to the
                            line shooter->goalCentre) / 2.0

so "lane jammed" fires when somebody is within 0.2 cells (2.8 m) of the shot line.

The goalkeeper stands on that line by definition — his whole job is to stand between
the ball and the middle of his goal. This measures how often the keeper alone is
enough to veto the shot, and how the openness value is distributed.
"""
import json, glob, re, math, bisect, collections

HEAD = re.compile(
    r'^\[(?P<min>\d+):(?P<sec>\d\d)\|DEC\] DECISION (?P<name>.+?)\((?P<role>\w+)\) -> (?P<type>\w+)'
    r' score=\s*(?P<score>-?[\d.]+)(?P<rest>.*)$')
ALT = re.compile(r'\| (?P<type>\w+)=\s*(?P<score>-?[\d.]+)')
VETO = -9000.0


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def point_to_segment(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    l2 = dx * dx + dy * dy
    if l2 < 1e-9:
        return dist(p, a)
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2))
    return dist(p, (a[0] + t * dx, a[1] + t * dy))


def boards(d):
    idx = {}
    for s in d.get('snapshots') or []:
        if not s:
            continue
        ball = s.get('ballPosition') or {}
        ps = {}
        for p in (s.get('players') or []):
            pos = p.get('position') or {}
            if 'row' in pos:
                ps[p['id']] = (pos['row'], pos['column'], p.get('role') or '', p.get('team') or '')
        idx[s['tick']] = ((ball.get('row'), ball.get('column')), ps)
    return idx, sorted(idx)


def openness_with(pos, team, ps, skip_keeper):
    """calculateGoalOpenness, optionally ignoring the goalkeeper."""
    goal_row = 8.0 if team == 'HOME' else 1.0
    goal = (goal_row, 3.5)
    m = 1e9
    for p in ps.values():
        if p[3] == team:
            continue
        if p[2].startswith('GK'):
            continue                       # the keeper is skipped for a separate reason: out of play
        if skip_keeper:
            continue
        m = min(m, point_to_segment(p[0:2], pos, goal))
    return min(1.0, m / 2.0)


def openness_full(pos, team, ps):
    goal_row = 8.0 if team == 'HOME' else 1.0
    goal = (goal_row, 3.5)
    m = 1e9
    for p in ps.values():
        if p[3] == team:
            continue
        m = min(m, point_to_segment(p[0:2], pos, goal))
    return min(1.0, m / 2.0), m


def main():
    files = []
    for f in sorted(glob.glob('replay-data/*.json')):
        try:
            d = json.load(open(f))
        except Exception:
            continue
        h = d.get('homeTeamName') or ''
        if 'Engine' in h or 'Home FC' in h or 'Away United' in h or not h:
            continue
        files.append((f, d))

    in_zone = 0
    shot_vetoed = 0
    keeper_would_have_allowed = 0     # outfield-only openness clears the bar
    keeper_was_the_closest = 0
    dist_when_vetoed = []
    open_when_vetoed = []
    open_when_allowed = []
    closest_was_gk = 0

    for path, d in files:
        idx, ticks = boards(d)
        for line in d.get('logs') or []:
            m = HEAD.match(line)
            if not m:
                continue
            opts = {m.group('type'): float(m.group('score'))}
            for t, v in ALT.findall(m.group('rest')):
                opts[t] = float(v)
            if 'SHOT' not in opts:
                continue
            tick = int(m.group('min')) * 40 + round(int(m.group('sec')) * 40 / 60)
            i = bisect.bisect_right(ticks, tick) - 1
            if i < 0:
                continue
            snap = idx[ticks[i]]
            ball, ps = snap
            if ball[0] is None or not ps:
                continue
            cid = min(ps, key=lambda k: dist(ps[k], ball))
            me = ps[cid]
            mypos, myteam = me[0:2], me[3]

            zone = mypos[0] >= 6.0 if myteam == 'HOME' else mypos[0] <= 3.0
            if not zone:
                continue
            in_zone += 1
            shot = opts['SHOT']
            o_full, closest = openness_full(mypos, myteam, ps)
            o_no_gk = openness_with(mypos, myteam, ps, True)

            # which player is closest to the shot line?
            goal_row = 8.0 if myteam == 'HOME' else 1.0
            best = None
            for pid, p in ps.items():
                if p[3] == myteam:
                    continue
                d = point_to_segment(p[0:2], mypos, (goal_row, 3.5))
                if best is None or d < best[1]:
                    best = (p, d)
            if best and best[0][2].startswith('GK'):
                closest_was_gk += 1

            if shot <= VETO:
                shot_vetoed += 1
                open_when_vetoed.append(o_full)
                dist_when_vetoed.append(closest)
                # would the shot have been allowed if the keeper were not on the line?
                if o_no_gk >= 0.1:
                    keeper_would_have_allowed += 1
            else:
                open_when_allowed.append(o_full)

    def stat(name, xs):
        if not xs:
            return
        xs2 = sorted(xs)
        print('  %-34s n=%-6d min %.3f  median %.3f  max %.3f'
              % (name, len(xs2), xs2[0], xs2[len(xs2) // 2], xs2[-1]))

    print('matches: %d' % len(files))
    print()
    print('DECISIONS WITH THE CARRIER IN THE SHOOTING ZONE   %s' % f'{in_zone:,}')
    print()
    print('  of those, SHOT was vetoed                     %s  (%.1f%%)'
          % (f'{shot_vetoed:,}', 100 * shot_vetoed / max(1, in_zone)))
    print('  of those, the CLOSEST player to the shot line')
    print('      was the goalkeeper                        %s  (%.1f%%)'
          % (f'{closest_was_gk:,}', 100 * closest_was_gk / max(1, in_zone)))
    print()
    print('  THE DECISIVE NUMBER')
    print('  shots vetoed as "lane jammed" where the shot')
    print('  WOULD have cleared the 0.1 openness bar if')
    print('  the goalkeeper had not been on the line       %s  (%.1f%% of all vetoes)'
          % (f'{keeper_would_have_allowed:,}',
             100 * keeper_would_have_allowed / max(1, shot_vetoed)))
    print()
    stat('openness when the shot WAS allowed', open_when_allowed)
    stat('openness when the shot was VETOED', open_when_vetoed)
    stat('closest body to the line (cells)', dist_when_vetoed)
    print()
    print('  a keeper standing on his line is by definition within 0.35 cells of the')
    print('  ball->goal-centre line, and the veto threshold is 0.2 cells.')


if __name__ == '__main__':
    main()