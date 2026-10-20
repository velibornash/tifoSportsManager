#!/usr/bin/env python3
"""
Decision-vs-alternatives forensic read of real simulated matches.

Every decision the engine made is paired with the seven options it scored, the
reason each vetoed option gives, and the full board at that tick — all twenty-two
players and the ball — reconstructed from the tick snapshot.

One question is asked, and only one:

    with everyone standing where they were standing, was the chosen action the
    right one of the options that were actually on the table?

No output statistics. Shots and goals are not the subject.
"""
import json, glob, re, collections, math, sys, bisect

# DECISION <name>(<ROLE>) -> <TYPE> score=<n> | <TYPE>=<n> ... | ball(r,c)
HEAD = re.compile(
    r'^\[(?P<min>\d+):(?P<sec>\d\d)\|DEC\] DECISION (?P<name>.+?)\((?P<role>\w+)\) -> (?P<type>\w+)'
    r' score=\s*(?P<score>-?[\d.]+)(?P<rest>.*)$')
ALT = re.compile(r'\| (?P<type>\w+)=\s*(?P<score>-?[\d.]+)')
# The engine appends its own prose reason after the number, e.g.
#   | SHOT=  -10000.0  SHOT: freq gate
BODY = r'(?P<body>[^|]*)'
REASON = {
    t: re.compile(r'(?:\| |-> )' + t + r'[= ]\s*-?[\d.]+\s+' + BODY)
    for t in ('PASS', 'DRIBBLE', 'SHOT', 'CLEAR', 'THRU', 'CROSS', 'CENTER')
}

VETO = -9000.0                     # CleanDecisionEngine.UNAVAILABLE

WHY = [
    ('freq gate', 'the frequency roll refused it'),
    ('not in zone', 'outside the shooting zone'),
    ('lane jammed', 'the shot lane was blocked'),
    ('not in defensive third', 'not in his own defensive third'),
    ('not in attacking half', 'not in the attacking half'),
    ('not in final third', 'not in the final third'),
    ('not wide enough', 'not wide enough to cross from'),
    ('crossing position', 'not in a crossing position'),
    ('nobody in the box', 'no team-mate in the box'),
    ('nobody behind the line', 'no team-mate behind the last defender'),
    ('not perceived', 'his playmaking did not see it'),
    ('no receiver', 'no team-mate available at all'),
    ('has a pass available', 'a pass was available, so no dribble'),
    ('shot gate blocked', 'the shot gate was spent'),
]


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def why_of(reason):
    for frag, human in WHY:
        if frag in reason:
            return human
    return reason.strip() or 'no reason given'


# EXEC PASS by <name>(<ROLE>) at (r,c) -> (r,c) target <name>(<ROLE>)(r,c) score=  <n> [<reason>]
EXE = re.compile(
    r'^\[(?P<min>\d+):(?P<sec>\d\d)\|EXE\] EXEC (?P<type>\w+) by (?P<name>.+?)\((?P<role>\w+)\)'
    r' .*? score=\s*(?P<score>-?[\d.]+) \[(?P<reason>.*)\]$')


def load(path):
    try:
        return json.load(open(path))
    except Exception:
        return None


def real_matches():
    """Replays produced by the game, not by the headless probes."""
    out = []
    for f in sorted(glob.glob('replay-data/*.json')):
        d = load(f)
        if not d:
            continue
        h = d.get('homeTeamName') or ''
        if 'Engine' in h or 'Home FC' in h or 'Away United' in h or not h:
            continue
        out.append((f, d))
    return out


def boards(d):
    """tick -> ((ballRow, ballCol), {playerId: (row, col, role, team)}), with sorted ticks."""
    idx = {}
    for s in d.get('snapshots') or []:
        if not s:
            continue
        ball = s.get('ballPosition') or {}
        ps = {}
        for p in (s.get('players') or []):
            pos = p.get('position') or {}
            if 'row' not in pos:
                continue
            ps[p['id']] = (pos['row'], pos['column'], p.get('role') or '', p.get('team') or '')
        idx[s['tick']] = ((ball.get('row'), ball.get('column')), ps)
    return idx, sorted(idx)


def board_at(idx, ticks, tick):
    i = bisect.bisect_right(ticks, tick) - 1
    if i < 0:
        return None, None
    return idx[ticks[i]], ticks[i]


# ------------------------------------------------------------------ geometry

def goal_line(team):
    return 8.0 if team == 'HOME' else 1.0


def towards_goal(pos, team):
    return pos[0] - 4.5 if team == 'HOME' else 4.5 - pos[0]


def in_zone(pos, team):
    return pos[0] >= 6.0 if team == 'HOME' else pos[0] <= 3.0


def nearest_opp(pos, team, ps, ignore):
    best, bd = None, 1e9
    for pid, p in ps.items():
        if pid == ignore or p[3] == team:
            continue
        d = dist(p, pos)
        if d < bd:
            bd, best = d, p
    return best, bd


def nearest_mate(pos, team, ps, ignore):
    best, bd = None, 1e9
    for pid, p in ps.items():
        if pid == ignore or p[3] != team:
            continue
        d = dist(p, pos)
        if d < bd:
            bd, best = d, p
    return best, bd


# ------------------------------------------------------------------- reading

def read_decision(tick, name, role, chosen, score, rest, snap, reasons=None):
    """The football judgement, given the board."""
    if snap is None:
        return []
    ball, ps = snap
    if ball[0] is None or not ps:
        return []
    cid = min(ps, key=lambda k: dist(ps[k], ball))
    me = ps[cid]                 # (row, col, role, team)
    mypos, myrole, myteam = me[0:2], me[2], me[3]

    opts = {chosen: float(score)}
    for t, v in ALT.findall(rest):
        opts[t] = float(v)
    reasons = dict(reasons or {})

    out = []

    def avail(t):
        return opts.get(t, VETO) > VETO

    # --- 1. one-on-one with the keeper: football says shoot, every time ---
    if in_zone(mypos, myteam) and abs(mypos[0] - goal_line(myteam)) <= 2.5:
        nop, nd = nearest_opp(mypos, myteam, ps, cid)
        if nd > 1.2 and nop is not None and nop[2].startswith('GK'):
            if chosen != 'SHOT':
                out.append((
                    'ONE_ON-1 WITH THE KEEPER, HE DID NOT SHOOT',
                    'chose %s' % chosen,
                    'SHOT was %s — %s. He chose %s, whose reason was: %s'
                    % ('available' if avail('SHOT') else 'vetoed',
                       why_of(reasons.get('SHOT', '')) if not avail('SHOT') else 'scored %.1f' % opts['SHOT'],
                       chosen, why_of(reasons.get(chosen, '')))))

    # --- 2. a team-mate free in the box ---
    if towards_goal(mypos, myteam) > 0.8:
        free = [p for p in ps.values()
                if p[3] == myteam and p[2] != me[2]
                and (p[0] >= 6.5 if myteam == 'HOME' else p[0] <= 2.5)
                and nearest_opp(p, myteam, ps, None)[1] > 1.0]
        if free:
            if not any(avail(t) for t in ('CROSS', 'CENTER', 'THRU')):
                out.append((
                    'FREE MAN IN THE BOX, AND NO DELIVERY EXISTED AT ALL',
                    '%d free team-mate(s) in the box' % len(free),
                    ' | '.join('%s %s' % (t, why_of(reasons.get(t, ''))) for t in ('CROSS', 'CENTER', 'THRU'))
                    + ' | he chose %s' % chosen))

    # --- 3. a through ball he could play and did not ---
    if avail('THRU') and chosen != 'THRU':
        out.append((
            'THROUGH BALL WAS ON THE TABLE AND HE DID NOT PLAY IT',
            'THRU scored %.1f, chose %s' % (opts['THRU'], chosen),
            'the reason THRU was viable: %s. What he chose instead: %s'
            % (why_of(reasons.get('THRU', '')), why_of(reasons.get(chosen, '')))))

    # --- 4. a shot he could take and did not ---
    if avail('SHOT') and chosen not in ('SHOT', 'THRU'):
        out.append((
            'SHOT WAS AVAILABLE AND HE CHOSE SOMETHING ELSE',
            'SHOT %.1f vs %s' % (opts['SHOT'], chosen),
            why_of(reasons.get(chosen, ''))))

    # --- 5. dribbling away from the goal he is attacking ---
    if chosen == 'DRIBBLE':
        step = 3.0 if myteam == 'HOME' else -3.0
        lo, hi = (1.0, 7.5) if myteam == 'HOME' else (1.5, 8.0)
        to = max(lo, min(hi, mypos[0] + step))
        back = (myteam == 'HOME' and to <= mypos[0] + 0.05) or (myteam == 'AWAY' and to >= mypos[0] - 0.05)
        if back:
            out.append((
                'HE DRIBBLED AWAY FROM THE GOAL HE IS ATTACKING',
                'row %.1f -> %.1f' % (mypos[0], to),
                'he attacks row %.0f. DRIBBLE reason: %s' % (goal_line(myteam), why_of(reasons.get('DRIBBLE', '')))))

    # --- 6. every option was useless: a forced, unfootball choice ---
    viable = [v for v in opts.values() if v >= 0]
    if not viable:
        out.append((
            'NOTHING ON THE TABLE WAS EVEN NEUTRAL',
            'best available was %s at %s' % (chosen, why_of(reasons.get(chosen, ''))),
            ' | '.join('%s: %s' % (t, why_of(reasons[t])) for t in ('SHOT', 'CLEAR', 'THRU', 'CROSS', 'CENTER')
                       if t in reasons) or 'nothing was scored'))
    return out


def board_text(snap, cid, team):
    (_, _), ps = snap
    lines = []
    for r in [16, 14, 12, 10, 8, 6, 4, 2, 0]:
        row = r * 0.5
        s = '     '
        for c in range(29):
            col = 1.0 + c * 3.0 / 28.0
            ch = '.'
            if dist((row, col), ps[cid][0:2]) < 0.26:
                ch = 'C'
            else:
                for p in ps.values():
                    if dist(p[0:2], (row, col)) < 0.26:
                        ch = 'H' if p[3] == team else 'A'
                        break
            s += ch
        lines.append('%s %4.1f' % (s, row))
    return '\n'.join(lines)


def main():
    per = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    matches = real_matches()
    print("real matches on disk: %d\n" % len(matches))

    findings = collections.Counter()
    reasons_tally = collections.Counter()
    veto = collections.defaultdict(collections.Counter)
    examples = collections.defaultdict(list)
    total = 0

    for path, d in matches:
        idx, ticks = boards(d)

        # The reason the engine gives for the action it CHOSE is on the EXE line, keyed by the same
        # minute and the same actor. The reasons for the options it did NOT choose are written
        # nowhere in a stored replay — they live on a DecisionOption that is thrown away, which is
        # worth knowing: a stored match cannot explain what it declined to do.
        chosen_reason = {}
        for l in d.get('logs') or []:
            e = EXE.match(l)
            if e:
                chosen_reason[(int(e.group('min')), int(e.group('sec')), e.group('name'))] = e.group('reason')

        for line in d.get('logs') or []:
            m = HEAD.match(line)
            if not m:
                continue
            total += 1
            rest = m.group('rest')
            opts = {m.group('type'): float(m.group('score'))}
            reasons = {}
            for t, v in ALT.findall(rest):
                opts[t] = float(v)
            reasons[m.group('type')] = chosen_reason.get(
                (int(m.group('min')), int(m.group('sec')), m.group('name')), '')
            reasons_tally[(m.group('type'), reasons[m.group('type')] or '(no reason recorded)')] += 1

            for t, v in opts.items():
                if v <= VETO:
                    veto[t]['reason not recorded in the replay'] += 1

            tick = int(m.group('min')) * 40 + round(int(m.group('sec')) * 40 / 60)
            snap, st = board_at(idx, ticks, tick)
            for f in read_decision(tick, m.group('name'), m.group('role'),
                                   m.group('type'), m.group('score'), rest, snap, reasons):
                key = f[0]
                findings[key] += 1
                if len(examples[key]) < per:
                    ball = snap[0] if snap else (0, 0)
                    cid = min(snap[1], key=lambda k: dist(snap[1][k], ball)) if snap and snap[1] else None
                    examples[key].append((path, line, f[1], f[2],
                                          board_text(snap, cid, snap[1][cid][3]) if cid else ''))

    print("decisions read: %s\n" % f"{total:,}")
    print('=' * 78)
    print("WHAT THE ENGINE DID, AND THE REASON IT GAVE ITSELF")
    print('=' * 78)
    for (typ, reason), n in reasons_tally.most_common(30):
        print('  %-8s %-46s %s' % (typ, reason[:46], f"{n:,}"))

    print('\n  NOTE: the reasons for the options it did NOT choose are written nowhere in a')
    print('        stored replay. They exist on a DecisionOption that is discarded, so a')
    print('        saved match cannot explain what it declined to do.')
    print('\n' + '=' * 78)
    print('THE DECISIONS THAT WERE NOT FOOTBALL  —  judged against the board at that tick')
    print('=' * 78)
    for k, n in findings.most_common():
        print('\n%s\n    %s times\n' % (k, f"{n:,}"))
        for path, line, why, detail, pitch in examples[k]:
            print('  --- %s  %s' % (path.split('/')[-1], line.split('|DEC]')[0] + '|DEC]'))
            for part in line.split('  '):
                if part.strip():
                    print('      %s' % part.strip()[:150])
            print('      >> %s' % why)
            print('      >> %s' % detail)
            if pitch:
                print(pitch)
            print()


if __name__ == '__main__':
    main()