import { describe, expect, it } from 'vitest';
import type { TeamTrendView, TrendPointView } from '../api/types';
import {
  OTHER_COLOR,
  assignTeamColors,
  foldTeams,
  toFrictionSeries,
  toThroughputSeries,
} from './chartData';

function point(weekStart: string, overrides: Partial<TrendPointView> = {}): TrendPointView {
  return {
    weekStart,
    itemsResolved: 10,
    avgCycleSec: 100,
    p85CycleSec: 150,
    flowEfficiencyPct: 50,
    blockedPct: 10,
    reviewWaitPct: 10,
    frictionScore: 50,
    ...overrides,
  };
}

describe('assignTeamColors', () => {
  it('assigns palette colors in fixed order by teamName ascending, regardless of input order', () => {
    const teams = [
      { teamId: 't3', teamName: 'Charlie' },
      { teamId: 't1', teamName: 'Alpha' },
      { teamId: 't2', teamName: 'Bravo' },
    ];
    const colors = assignTeamColors(teams, ['#111', '#222', '#333']);

    expect(colors.get('t1')).toBe('#111'); // Alpha — 1st alphabetically
    expect(colors.get('t2')).toBe('#222'); // Bravo — 2nd
    expect(colors.get('t3')).toBe('#333'); // Charlie — 3rd
  });

  it('never re-assigns a color once fixed — same call, same order, always the same map', () => {
    const teams = [
      { teamId: 'a', teamName: 'Alpha' },
      { teamId: 'b', teamName: 'Bravo' },
    ];
    const first = assignTeamColors(teams, ['#aaa', '#bbb']);
    const second = assignTeamColors([...teams].reverse(), ['#aaa', '#bbb']);
    expect(second.get('a')).toBe(first.get('a'));
    expect(second.get('b')).toBe(first.get('b'));
  });

  it('falls back overflow teams (beyond the palette length) to the shared Other color', () => {
    const teams = [
      { teamId: 't1', teamName: 'Alpha' },
      { teamId: 't2', teamName: 'Bravo' },
      { teamId: 't3', teamName: 'Charlie' },
      { teamId: 't4', teamName: 'Delta' },
      { teamId: 't5', teamName: 'Echo' },
    ];
    const colors = assignTeamColors(teams, ['#111', '#222', '#333', '#444']);
    expect(colors.get('t4')).toBe('#444');
    expect(colors.get('t5')).toBe(OTHER_COLOR);
  });
});

describe('foldTeams', () => {
  it('keeps all teams unfolded when at or below the max', () => {
    const teams: TeamTrendView[] = [
      { teamId: 'a', teamName: 'Alpha', points: [point('2026-01-05')] },
      { teamId: 'b', teamName: 'Bravo', points: [point('2026-01-05')] },
    ];
    const { kept, other } = foldTeams(teams, 4);
    expect(kept).toHaveLength(2);
    expect(other).toBeNull();
  });

  it('keeps the top N by latest frictionScore and folds the rest with exact weighted averages', () => {
    // 5 teams, max 3 → top 3 by latest frictionScore kept, bottom 2 folded into "Other".
    const teams: TeamTrendView[] = [
      { teamId: 'zeta', teamName: 'Zeta', points: [point('2026-01-05', { frictionScore: 95 })] },
      {
        teamId: 'yankee',
        teamName: 'Yankee',
        points: [point('2026-01-05', { frictionScore: 85 })],
      },
      { teamId: 'xray', teamName: 'Xray', points: [point('2026-01-05', { frictionScore: 75 })] },
      {
        teamId: 'whiskey',
        teamName: 'Whiskey',
        points: [
          point('2026-01-05', {
            frictionScore: 50,
            itemsResolved: 10,
            avgCycleSec: 100,
            p85CycleSec: 150,
            flowEfficiencyPct: 40,
            blockedPct: 20,
            reviewWaitPct: 10,
          }),
        ],
      },
      {
        teamId: 'victor',
        teamName: 'Victor',
        points: [
          point('2026-01-05', {
            frictionScore: 70,
            itemsResolved: 30,
            avgCycleSec: 200,
            p85CycleSec: 250,
            flowEfficiencyPct: 60,
            blockedPct: 10,
            reviewWaitPct: 5,
          }),
        ],
      },
    ];

    const { kept, other } = foldTeams(teams, 3);

    // Kept = top 3 by latest frictionScore (Zeta 95, Yankee 85, Xray 75), sorted by teamName asc so
    // color assignment stays stable (Xray, Yankee, Zeta).
    expect(kept.map((t) => t.teamName)).toEqual(['Xray', 'Yankee', 'Zeta']);

    expect(other).not.toBeNull();
    expect(other?.teamId).toBe('other');
    const folded = other?.points[0];
    expect(folded?.weekStart).toBe('2026-01-05');
    expect(folded?.itemsResolved).toBe(40); // 10 + 30
    // weighted by itemsResolved: (50*10 + 70*30) / 40 = 65
    expect(folded?.frictionScore).toBe(65);
    // (100*10 + 200*30) / 40 = 175
    expect(folded?.avgCycleSec).toBe(175);
    // (150*10 + 250*30) / 40 = 225
    expect(folded?.p85CycleSec).toBe(225);
    // (40*10 + 60*30) / 40 = 55
    expect(folded?.flowEfficiencyPct).toBe(55);
    // (20*10 + 10*30) / 40 = 12.5
    expect(folded?.blockedPct).toBe(12.5);
    // (10*10 + 5*30) / 40 = 6.25
    expect(folded?.reviewWaitPct).toBe(6.25);
  });

  it('returns a null Other when every folded team has zero data points', () => {
    const teams: TeamTrendView[] = [
      { teamId: 'a', teamName: 'Alpha', points: [point('2026-01-05')] },
      { teamId: 'b', teamName: 'Bravo', points: [point('2026-01-05')] },
      { teamId: 'c', teamName: 'Charlie', points: [] },
    ];
    const { other } = foldTeams(teams, 2);
    expect(other).toBeNull();
  });
});

describe('week-union alignment', () => {
  it('aligns every team onto the union of weekStarts, filling gaps with null', () => {
    const teams: TeamTrendView[] = [
      {
        teamId: 'a',
        teamName: 'Alpha',
        points: [
          point('2026-01-05', { frictionScore: 10 }),
          point('2026-01-12', { frictionScore: 20 }),
        ],
      },
      {
        teamId: 'b',
        teamName: 'Bravo',
        points: [
          point('2026-01-12', { frictionScore: 30 }),
          point('2026-01-19', { frictionScore: 40 }),
        ],
      },
    ];
    const colors = new Map([
      ['a', '#111'],
      ['b', '#222'],
    ]);

    const { weeks, series } = toFrictionSeries(teams, colors);

    expect(weeks).toEqual(['2026-01-05', '2026-01-12', '2026-01-19']);
    expect(series).toEqual([
      { name: 'Alpha', color: '#111', data: [10, 20, null] },
      { name: 'Bravo', color: '#222', data: [null, 30, 40] },
    ]);
  });

  it('falls back to the Other color when a team is not in the color map', () => {
    const teams: TeamTrendView[] = [
      {
        teamId: 'other',
        teamName: 'Other',
        points: [point('2026-01-05', { itemsResolved: 7 })],
      },
    ];
    const { series } = toThroughputSeries(teams, new Map());
    expect(series[0].color).toBe(OTHER_COLOR);
    expect(series[0].data).toEqual([7]);
  });
});
