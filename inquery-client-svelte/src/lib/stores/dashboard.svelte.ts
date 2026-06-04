/**
 * Dashboard types
 */

export interface IGridItem {
  id: string;
  type: "chart" | "header" | "divider" | "text" | "tabs";
  x: number;
  y: number;
  width: number;
  height: number;
  chartId?: number;
  title?: string;
  content?: string;
  chartSchema?: any;
}

export interface IDashboardLayout {
  id?: number;
  name: string;
  items: IGridItem[];
}
