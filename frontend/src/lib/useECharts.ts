// Thin vanilla-echarts binding: init on mount, push option updates via setOption, keep the chart
// sized to its container via ResizeObserver, dispose on unmount, and fully re-init when the theme
// key changes (a clean dispose+init avoids any theme-dependent internal state leaking across a
// light/dark switch, rather than trying to patch it in place).
import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import * as echarts from 'echarts';

export function useECharts<T extends HTMLElement = HTMLDivElement>(
  option: echarts.EChartsCoreOption | null,
  themeKey: string,
): RefObject<T> {
  const containerRef = useRef<T>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    const el = containerRef.current;
    if (el === null) {
      return;
    }
    const chart = echarts.init(el);
    chartRef.current = chart;

    const resizeObserver = new ResizeObserver(() => chart.resize());
    resizeObserver.observe(el);

    return () => {
      resizeObserver.disconnect();
      chart.dispose();
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
