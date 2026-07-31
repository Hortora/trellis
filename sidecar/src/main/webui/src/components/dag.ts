import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { createRef, ref } from 'lit/directives/ref.js';
import { init, use, type ECharts } from 'echarts/core';
import { GraphChart } from 'echarts/charts';
import { TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

use([CanvasRenderer, GraphChart, TooltipComponent]);

interface DagNode {
  key: string;
  label?: string;
  layer: number;
  index: number;
  closed: boolean;
  onCriticalPath: boolean;
  inCycle: boolean;
  external: boolean;
}

interface DagEdge {
  source: string;
  target: string;
}

const COLORS = {
  closed: '#555',
  criticalPath: '#3b82f6',
  unblocked: '#4ade80',
  blocked: '#888',
  external: '#666',
  cycle: '#ef4444',
};

@customElement('trellis-dag')
export class TrellisDag extends LitElement {
  @property({ attribute: false }) nodes: DagNode[] = [];
  @property({ attribute: false }) edges: DagEdge[] = [];
  @property({ type: Number }) layerSpacing = 120;
  @property({ type: Number }) indexSpacing = 80;

  private _chartRef = createRef<HTMLDivElement>();
  private _chart: ECharts | undefined;
  private _resizeObserver: ResizeObserver | undefined;

  static override styles = css`
    :host { display: block; width: 100%; min-height: 400px; }
    .chart-container { width: 100%; height: 100%; min-height: 400px; }
  `;

  override render() {
    return html`<div ${ref(this._chartRef)} class="chart-container"></div>`;
  }

  override updated() {
    const container = this._chartRef.value;
    if (!container || this.nodes.length === 0) return;

    if (!this._chart) {
      this._chart = init(container, 'dark');
      this._resizeObserver = new ResizeObserver(() => this._chart?.resize());
      this._resizeObserver.observe(container);
    }

    const maxLayer = Math.max(0, ...this.nodes.filter(n => n.layer >= 0).map(n => n.layer));
    const cycleY = (maxLayer + 2) * this.indexSpacing;
    const unblockedKeys = this._computeUnblocked();

    const data = this.nodes.map(n => {
      const x = n.inCycle
        ? n.index * this.indexSpacing + 40
        : n.layer * this.layerSpacing + 40;
      const y = n.inCycle
        ? cycleY
        : n.index * this.indexSpacing + 40;

      let color = COLORS.blocked;
      let borderColor = 'transparent';
      let borderWidth = 0;

      if (n.closed) {
        color = COLORS.closed;
      } else if (n.inCycle) {
        color = COLORS.cycle;
        borderColor = '#dc2626';
        borderWidth = 2;
      } else if (n.onCriticalPath) {
        color = COLORS.criticalPath;
      } else if (unblockedKeys.has(n.key)) {
        color = COLORS.unblocked;
      }

      if (n.external) {
        borderColor = '#888';
        borderWidth = 2;
        color = COLORS.external;
      }

      const label = n.label ?? n.key.replace(/.*#/, '#');

      return {
        name: n.key,
        x,
        y,
        symbolSize: n.external ? 20 : 30,
        label: { show: true, formatter: label, fontSize: 11, color: '#eee' },
        itemStyle: { color, borderColor, borderWidth },
      };
    });

    const links = this.edges.map(e => ({
      source: e.source,
      target: e.target,
    }));

    this._chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item' as const,
        formatter: (params: any) => params.data?.name ?? '',
      },
      series: [{
        type: 'graph',
        layout: 'none',
        data,
        links,
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 8],
        lineStyle: { color: '#555', width: 1.5, curveness: 0.1 },
      }],
    }, true);
  }

  private _computeUnblocked(): Set<string> {
    const closedKeys = new Set(this.nodes.filter(n => n.closed).map(n => n.key));
    const nodeKeys = new Set(this.nodes.map(n => n.key));
    const unblocked = new Set<string>();

    for (const node of this.nodes) {
      if (node.closed) continue;
      const blockers = this.edges
        .filter(e => e.target === node.key)
        .map(e => e.source);
      const allResolved = blockers.every(b => closedKeys.has(b) || !nodeKeys.has(b));
      if (allResolved) unblocked.add(node.key);
    }
    return unblocked;
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._resizeObserver?.disconnect();
    this._resizeObserver = undefined;
    this._chart?.dispose();
    this._chart = undefined;
  }
}
