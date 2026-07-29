// Thin vanilla-echarts binding: lazy-load the echarts module on first chart mount (dynamic
// import — the echarts vendor chunk is fetched only when a chart actually renders, keeping it out
// of the initial bundle), init on mount, push option updates via setOption, keep the chart sized to
// its container via ResizeObserver, dispose on unmount, and fully re-init when the theme key
// changes (a clean dispose+init avoids any theme-dependent internal state leaking across a
// light/dark switch, rather than trying to patch it in place). While the module loads, the caller's
// container div simply stays empty — no spinner; the pending option is applied as soon as init
// completes, so no update is ever lost to the async gap.
import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import type { ECharts, EChartsCoreOption } from 'echarts';

type EChartsModule = typeof import('echarts');

// One shared module promise: every chart on the page awaits the same fetch, and once resolved the
// import cost is gone for the rest of the session.
let echartsModule: Promise<EChartsModule> | null = null;

function loadECharts(): Promise<EChartsModule> {
  echartsModule ??= import('echarts');
  return echartsModule;
}

export function useECharts<T extends HTMLElement = HTMLDivElement>(
  option: EChartsCoreOption | null,
  themeKey: string,
): RefObject<T> {
  const containerRef = useRef<T>(null);
  const chartRef = useRef<ECharts | null>(null);
  // Latest option, readable from the async init callback without re-running the init effect.
  const optionRef = useRef<EChartsCoreOption | null>(option);
  optionRef.current = option;

  useEffect(() => {
    const el = containerRef.current;
    if (el === null) {
      return;
    }
    let disposed = false;
    let resizeObserver: ResizeObserver | null = null;

    void loadECharts().then((echarts) => {
      if (disposed) {
        return; // unmounted (or theme changed) before the module arrived — nothing to init
      }
      const chart = echarts.init(el);
      chartRef.current = chart;
      if (optionRef.current !== null) {
        chart.setOption(optionRef.current, true);
      }
      resizeObserver = new ResizeObserver(() => chart.resize());
      resizeObserver.observe(el);
    });

    return () => {
      disposed = true;
      resizeObserver?.disconnect();
      chartRef.current?.dispose();
      chartRef.current = null;
    };
  }, [themeKey]);

  useEffect(() => {
    if (option !== null) {
      chartRef.current?.setOption(option, true);
    }
  }, [option]);

  return containerRef;
}

/** Detects the viewer's color scheme and re-renders on change (chart design rule: theme-aware). */
export function usePrefersDark(): boolean {
  const [isDark, setIsDark] = useState<boolean>(
    () =>
      typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches,
  );

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (e: MediaQueryListEvent): void => setIsDark(e.matches);
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  return isDark;
}
