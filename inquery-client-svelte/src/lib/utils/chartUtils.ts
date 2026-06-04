/**
 * Chart utility functions for generating ECharts options from query results.
 * Aligned with React implementation for consistent chart rendering.
 */

export type ChartType = "BAR" | "LINE" | "PIE" | "SCATTER" | "TABLE" | "CARD";

// Modern default chart palette - vibrant yet harmonious
const THEME_COLORS = [
  "#6366f1",
  "#06b6d4",
  "#f59e0b",
  "#ef4444",
  "#10b981",
  "#8b5cf6",
  "#ec4899",
  "#14b8a6",
  "#f97316",
  "#3b82f6",
  "#84cc16",
  "#e879f9",
];

// ─── Theme Detection ───

export function getTheme(): "dark" | "light" {
  if (typeof document === "undefined") return "dark";
  const el = document.documentElement;
  if (el.classList.contains("dark")) return "dark";
  const theme = el.getAttribute("data-theme") || el.getAttribute("theme");
  return theme === "dark" ? "dark" : "light";
}

export function getThemeColors() {
  const isDark = getTheme() === "dark";
  return {
    text: isDark ? "#e0e0e0" : "#333333",
    textSecondary: isDark ? "#a0a0a0" : "#666666",
    background: isDark ? "#1e1e1e" : "#ffffff",
    gridLine: isDark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.1)",
    splitLine: isDark ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.08)",
  };
}

interface ResultData {
  headerList: Array<{ name: string; dataType?: string }>;
  dataList: unknown[][];
}

function isNumericColumn(
  header: { dataType?: string },
  dataList: unknown[][],
  colIdx: number,
): boolean {
  if (header.dataType) {
    const t = header.dataType.toUpperCase();
    return [
      "INT",
      "INTEGER",
      "BIGINT",
      "FLOAT",
      "DOUBLE",
      "DECIMAL",
      "NUMBER",
      "NUMERIC",
      "REAL",
      "SMALLINT",
      "TINYINT",
    ].some((nt) => t.includes(nt));
  }
  for (let i = 0; i < Math.min(10, dataList.length); i++) {
    const val = dataList[i]?.[colIdx];
    if (val !== null && val !== undefined && val !== "") {
      if (typeof val === "number") return true;
      if (typeof val === "string" && !isNaN(Number(val))) return true;
      return false;
    }
  }
  return false;
}

function isDateLikeColumn(
  header: { name: string; dataType?: string },
  data: unknown[][],
  colIdx: number,
): boolean {
  const name = (header.name || "").toLowerCase();
  if (
    ["date", "time", "month", "year", "day", "week", "quarter", "period"].some(
      (k) => name.includes(k),
    )
  ) {
    return true;
  }
  if (header.dataType) {
    const dt = header.dataType.toUpperCase();
    if (["DATE", "DATETIME", "TIMESTAMP", "TIME"].some((t) => dt.includes(t))) {
      return true;
    }
  }
  const datePattern = /^\d{4}[-/]\d{2}([-/]\d{2})?/;
  let matchCount = 0;
  const sampleSize = Math.min(5, data.length);
  for (let i = 0; i < sampleSize; i++) {
    const val = String(data[i]?.[colIdx] ?? "").trim();
    if (val && datePattern.test(val)) matchCount++;
  }
  return sampleSize > 0 && matchCount >= Math.ceil(sampleSize * 0.5);
}

function sortDataByDateAsc(data: unknown[][], colIdx: number): unknown[][] {
  return [...data].sort((a, b) => {
    const aVal = String(a[colIdx] ?? "");
    const bVal = String(b[colIdx] ?? "");
    const aTime = new Date(aVal).getTime();
    const bTime = new Date(bVal).getTime();
    if (!isNaN(aTime) && !isNaN(bTime)) return aTime - bTime;
    return aVal.localeCompare(bVal);
  });
}

/**
 * Detect if a column is a dimension (categorical) based on data characteristics
 */
function isDimensionColumn(values: unknown[]): boolean {
  const uniqueValues = new Set(
    values.filter((v) => v !== null && v !== undefined),
  );
  const uniqueCount = uniqueValues.size;
  const isString = values.some(
    (v) => typeof v === "string" && isNaN(Number(v)),
  );
  return (
    uniqueCount > 1 &&
    uniqueCount <= 20 &&
    isString &&
    uniqueCount < values.length * 0.5
  );
}

export function findAxes(result: ResultData): {
  xIdx: number;
  yIndices: number[];
} {
  const headersFull = result.headerList;
  let xIdx = 0;
  const yIndices: number[] = [];

  for (let i = 0; i < headersFull.length; i++) {
    const h = headersFull[i];
    const name = (typeof h === "string" ? h : h.name || "").toUpperCase();
    if (name === "ROW NUMBER" || name === "#") continue;

    if (isNumericColumn(h, result.dataList, i)) {
      yIndices.push(i);
    } else if (yIndices.length === 0) {
      xIdx = i;
    }
  }

  if (yIndices.length === 0 && headersFull.length >= 2) {
    xIdx = 0;
    yIndices.push(1);
  } else if (yIndices.length === 0) {
    xIdx = 0;
  }

  if (yIndices.includes(xIdx)) {
    yIndices.splice(yIndices.indexOf(xIdx), 1);
  }

  return { xIdx, yIndices };
}

function getHeaderName(h: { name: string } | string): string {
  return typeof h === "string" ? h : h.name || "";
}

/**
 * Format number with max 2 decimal places and thousand separators
 */
function formatNumber(value: unknown): string {
  if (value === null || value === undefined) return "-";
  const num = Number(value);
  if (isNaN(num)) return String(value);
  if (Number.isInteger(num)) return num.toLocaleString();
  return num.toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}

/**
 * Infer a ChartConfig from result data and chart type, using the same auto-detection
 * logic that generateChartOption / createDefaultLineChart / createDefaultBarChart use.
 * This ensures the chart settings modal is pre-populated with the actual axes/options
 * the chart is displaying.
 *
 * Key: matches the "dimension pivot" logic in createDefaultLineChart/createDefaultBarChart:
 *   - If 3+ columns and middle column (index 1) is categorical → pivot mode
 *     col 0 = X axis, col 1 = dimension/series, col 2 = Y value
 */
export function inferChartConfig(
  result: ResultData,
  chartType: ChartType,
): ChartConfig {
  if (!result?.headerList?.length || !result?.dataList?.length) return {};

  // Normalize headers (same as generateChartOption)
  const normalizedHeaders: Array<{ name: string; dataType?: string }> =
    result.headerList.map((h) =>
      typeof h === "string"
        ? { name: h }
        : { name: h.name || String(h), dataType: h.dataType },
    );

  // Filter out Row Number (same as generateChartOption)
  const rowNumIdx = normalizedHeaders.findIndex((h) => {
    const n = h.name.toUpperCase();
    return n === "ROW NUMBER" || n === "#";
  });
  const headers = normalizedHeaders.filter((_, i) => i !== rowNumIdx);
  const data = result.dataList.map((row) =>
    rowNumIdx >= 0 ? (row as unknown[]).filter((_, i) => i !== rowNumIdx) : row,
  );

  // Defaults that match the chart rendering logic (all use `!== false` → default true)
  const config: ChartConfig = {
    showValue: true,
    showAxis: true,
    showGridLine: true,
    showLegend: true,
  };

  // Match the pivot detection logic used by createDefaultLineChart / createDefaultBarChart
  if ((chartType === "LINE" || chartType === "BAR") && headers.length >= 3) {
    const middleColValues = data.map((row) => row[1]);
    if (isDimensionColumn(middleColValues)) {
      config.xAxis = getHeaderName(headers[0]);
      config.dimension = getHeaderName(headers[1]);
      config.yAxes = [getHeaderName(headers[2])];
      return config;
    }
  }

  // Non-pivot: col 0 = X, detect Y columns
  config.xAxis = getHeaderName(headers[0]);

  const normalizedResult = { headerList: headers, dataList: data };
  const { yIndices } = findAxes(normalizedResult);
  const yNames = yIndices.map((i) => getHeaderName(headers[i]));

  if (chartType === "PIE" || chartType === "SCATTER") {
    config.yAxes = yNames.length > 0 ? [yNames[0]] : [];
  } else {
    config.yAxes = yNames;
  }

  return config;
}

/**
 * Build initial ChartConfig from backend LLM recommendations,
 * falling back to inferChartConfig for any fields not provided by backend.
 */
export function buildInitialChartConfig(
  result: ResultData,
  chartType: ChartType,
  backendRecommendation?: {
    chartXAxis?: string;
    chartYAxis?: string;
    chartDimension?: string;
    chartDimensions?: string[];
    chartXAxisFormat?: string;
    chartYAxisFormat?: string;
    chartLineVariant?: string;
    chartPieVariant?: string;
    chartBarOrientation?: string;
    chartOrder?: string;
  },
): ChartConfig {
  const inferred = inferChartConfig(result, chartType);
  if (!backendRecommendation) return inferred;

  const config: ChartConfig = { ...inferred };

  if (backendRecommendation.chartXAxis) config.xAxis = backendRecommendation.chartXAxis;
  if (backendRecommendation.chartYAxis) config.yAxes = [backendRecommendation.chartYAxis];
  // Prefer dimensions array; fall back to single dimension
  if (backendRecommendation.chartDimensions && backendRecommendation.chartDimensions.length > 0) {
    config.dimensions = backendRecommendation.chartDimensions;
    config.dimension = backendRecommendation.chartDimensions[0];
  } else if (backendRecommendation.chartDimension) {
    config.dimension = backendRecommendation.chartDimension;
    config.dimensions = [backendRecommendation.chartDimension];
  }
  if (backendRecommendation.chartXAxisFormat) config.xAxisFormat = backendRecommendation.chartXAxisFormat as XAxisFormat;
  if (backendRecommendation.chartYAxisFormat) config.yAxisFormat = backendRecommendation.chartYAxisFormat as MetricFormat;
  if (backendRecommendation.chartLineVariant) config.lineVariant = backendRecommendation.chartLineVariant as ChartConfig["lineVariant"];
  if (backendRecommendation.chartPieVariant) config.pieVariant = backendRecommendation.chartPieVariant as ChartConfig["pieVariant"];
  if (backendRecommendation.chartBarOrientation) config.barOrientation = backendRecommendation.chartBarOrientation as ChartConfig["barOrientation"];
  if (backendRecommendation.chartOrder) config.order = backendRecommendation.chartOrder as ChartConfig["order"];

  return config;
}

// ─── Default chart generation (no config) ───

export function generateChartOption(
  result: ResultData,
  chartType: ChartType,
): Record<string, unknown> | null {
  if (!result?.headerList?.length || !result?.dataList?.length) return null;
  if (chartType === "TABLE" || chartType === "CARD") return null;

  // Normalize headerList: ensure every header is { name: string, dataType?: string }
  const normalizedHeaders: Array<{ name: string; dataType?: string }> =
    result.headerList.map((h) =>
      typeof h === "string"
        ? { name: h }
        : { name: h.name || String(h), dataType: h.dataType },
    );

  const rowNumIdx = normalizedHeaders.findIndex((h) => {
    const n = h.name.toUpperCase();
    return n === "ROW NUMBER" || n === "#";
  });

  const headers = normalizedHeaders.filter((_, i) => i !== rowNumIdx);
  let data = result.dataList.map((row) => {
    return rowNumIdx >= 0
      ? (row as unknown[]).filter((_, i) => i !== rowNumIdx)
      : row;
  });

  if (headers.length >= 2 && isDateLikeColumn(headers[0], data, 0)) {
    data = sortDataByDateAsc(data, 0);
  }

  const colors = getThemeColors();

  switch (chartType) {
    case "LINE":
      return createDefaultLineChart(headers, data, colors);
    case "BAR":
      return createDefaultBarChart(headers, data, colors);
    case "PIE":
      return createDefaultPieChart(headers, data, colors);
    case "SCATTER":
      return createDefaultScatterChart(headers, data, colors);
    default:
      return createDefaultBarChart(headers, data, colors);
  }
}

function createDefaultLineChart(
  headers: ResultData["headerList"],
  data: unknown[][],
  colors: ReturnType<typeof getThemeColors>,
): Record<string, unknown> {
  // Detect dimension pivot (3+ columns with categorical middle column)
  if (headers.length >= 3) {
    const middleColValues = data.map((row) => row[1]);
    if (isDimensionColumn(middleColValues)) {
      return createPivotedChart("line", headers, data, 1, colors);
    }
  }

  const xAxisData = [...new Set(data.map((row) => String(row[0] ?? "")))];
  const series = headers.slice(1).map((header, index) => ({
    name: header.name,
    type: "line" as const,
    data: data.map((row) => {
      const value = row[index + 1];
      return value === null || value === undefined ? null : Number(value);
    }),
    smooth: true,
  }));

  return {
    backgroundColor: "transparent",
    color: THEME_COLORS,
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "cross" },
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
    },
    legend: {
      data: headers.slice(1).map((h) => h.name),
      bottom: 0,
      textStyle: { color: colors.text },
    },
    grid: { left: "1%", right: "4%", bottom: "15%", containLabel: true },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: xAxisData,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: { color: colors.textSecondary },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    yAxis: {
      type: "value",
      min: 0,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: { color: colors.textSecondary },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    series,
  };
}

function createDefaultBarChart(
  headers: ResultData["headerList"],
  data: unknown[][],
  colors: ReturnType<typeof getThemeColors>,
): Record<string, unknown> {
  // Single row with multiple numeric columns → Transposed bar
  if (data.length === 1 && headers.length >= 2) {
    const allNumeric = headers.every((_, idx) => !isNaN(Number(data[0][idx])));
    if (allNumeric) {
      return createTransposedBarChart(headers, data[0], colors);
    }
  }

  // Detect dimension pivot
  if (headers.length >= 3) {
    const middleColValues = data.map((row) => row[1]);
    if (isDimensionColumn(middleColValues)) {
      return createPivotedChart("bar", headers, data, 1, colors);
    }
  }

  const categories = [...new Set(data.map((row) => String(row[0] ?? "")))];
  const series = headers.slice(1).map((header, index) => ({
    name: header.name,
    type: "bar" as const,
    data: data.map((row) => {
      const value = row[index + 1];
      return value === null || value === undefined ? 0 : Number(value);
    }),
    emphasis: { focus: "series" },
  }));

  return {
    backgroundColor: "transparent",
    color: THEME_COLORS,
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
      valueFormatter: (value: unknown) => formatNumber(value),
    },
    legend: {
      data: headers.slice(1).map((h) => h.name),
      bottom: 0,
      textStyle: { color: colors.text },
    },
    grid: { left: "1%", right: "4%", bottom: "15%", containLabel: true },
    xAxis: {
      type: "category",
      data: categories,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: {
        interval: 0,
        rotate: categories.length > 10 ? 45 : 0,
        color: colors.textSecondary,
      },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    yAxis: {
      type: "value",
      min: 0,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: {
        color: colors.textSecondary,
        formatter: (value: number) => formatNumber(value),
      },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    series,
  };
}

function createTransposedBarChart(
  headers: ResultData["headerList"],
  rowData: unknown[],
  colors: ReturnType<typeof getThemeColors>,
): Record<string, unknown> {
  const categories = headers.map((h) => h.name);
  const values = rowData.map((v) =>
    v === null || v === undefined ? 0 : Number(v),
  );

  return {
    backgroundColor: "transparent",
    color: THEME_COLORS,
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
      valueFormatter: (value: unknown) => formatNumber(value),
    },
    grid: { left: "1%", right: "4%", bottom: "10%", top: "10%", containLabel: true },
    xAxis: {
      type: "category",
      data: categories,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: {
        interval: 0,
        rotate: categories.length > 5 ? 30 : 0,
        color: colors.textSecondary,
        fontSize: 11,
      },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    yAxis: {
      type: "value",
      min: 0,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: {
        color: colors.textSecondary,
        formatter: (value: number) => formatNumber(value),
      },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    series: [
      {
        name: "Value",
        type: "bar",
        data: values,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        emphasis: { focus: "series" },
        label: {
          show: true,
          position: "top",
          formatter: (params: any) => formatNumber(params.value),
          color: colors.text,
          fontSize: 11,
        },
      },
    ],
  };
}

function createPivotedChart(
  chartType: "line" | "bar",
  headers: ResultData["headerList"],
  data: unknown[][],
  dimensionColIndex: number,
  colors: ReturnType<typeof getThemeColors>,
): Record<string, unknown> {
  const xAxisValues = [...new Set(data.map((row) => String(row[0] ?? "")))];
  const dimensionValues = [
    ...new Set(data.map((row) => String(row[dimensionColIndex] ?? ""))),
  ];
  const valueColIndex = headers.length - 1;

  const dataMap: Record<string, Record<string, number>> = {};
  data.forEach((row) => {
    const xValue = String(row[0] ?? "");
    const dimValue = String(row[dimensionColIndex] ?? "");
    const value = Number(row[valueColIndex] ?? 0);
    if (!dataMap[xValue]) dataMap[xValue] = {};
    dataMap[xValue][dimValue] = value;
  });

  const series = dimensionValues.map((dimValue) => ({
    name: dimValue,
    type: chartType,
    data: xAxisValues.map(
      (xValue) =>
        dataMap[xValue]?.[dimValue] ?? (chartType === "bar" ? 0 : null),
    ),
    smooth: chartType === "line" ? true : undefined,
    emphasis: { focus: "series" },
  }));

  return {
    backgroundColor: "transparent",
    color: THEME_COLORS,
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: chartType === "line" ? "cross" : "shadow" },
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
      ...(chartType === "bar"
        ? { valueFormatter: (value: unknown) => formatNumber(value) }
        : {}),
    },
    legend: {
      data: dimensionValues,
      bottom: 0,
      textStyle: { color: colors.text },
    },
    grid: { left: "1%", right: "4%", bottom: "15%", containLabel: true },
    xAxis: {
      type: "category",
      boundaryGap: chartType === "bar",
      data: xAxisValues,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: { color: colors.textSecondary },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    yAxis: {
      type: "value",
      min: 0,
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: {
        color: colors.textSecondary,
        ...(chartType === "bar"
          ? { formatter: (value: number) => formatNumber(value) }
          : {}),
      },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    series,
  };
}

function createDefaultPieChart(
  headers: ResultData["headerList"],
  data: unknown[][],
  colors: ReturnType<typeof getThemeColors>,
): Record<string, unknown> {
  // Group by dimension and sum values (matches React)
  const aggregatedMap = new Map<string, number>();
  data.forEach((row) => {
    const name = String(row[0] ?? "Unknown");
    const value = Number(row[1]) || 0;
    aggregatedMap.set(name, (aggregatedMap.get(name) || 0) + value);
  });

  const pieData = Array.from(aggregatedMap.entries())
    .map(([name, value]) => ({ name, value }))
    .filter((d) => d.value > 0)
    .sort((a, b) => b.value - a.value);

  if (pieData.length === 0) {
    return {
      backgroundColor: "transparent",
      title: {
        text: "No valid data",
        left: "center",
        top: "center",
        textStyle: { color: colors.text },
      },
    };
  }

  return {
    backgroundColor: "transparent",
    color: THEME_COLORS,
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "item",
      formatter: "{b}: {c} ({d}%)",
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
    },
    legend: {
      orient: "horizontal",
      bottom: 0,
      data: pieData.map((d) => d.name),
      textStyle: { color: colors.text },
    },
    series: [
      {
        name: headers[1]?.name || "Value",
        type: "pie",
        radius: ["30%", "60%"],
        center: ["50%", "45%"],
        data: pieData,
        label: { show: true, color: colors.text, formatter: "{b}: {d}%" },
        labelLine: { show: true },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: "rgba(0, 0, 0, 0.5)",
          },
        },
      },
    ],
  };
}

function createDefaultScatterChart(
  headers: ResultData["headerList"],
  data: unknown[][],
  colors: ReturnType<typeof getThemeColors>,
): Record<string, unknown> {
  const scatterData = data.map((row) => [
    Number(row[0] ?? 0),
    Number(row[1] ?? 0),
  ]);

  return {
    backgroundColor: "transparent",
    color: THEME_COLORS,
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "item",
      formatter: (params: any) => {
        const value = params?.value;
        if (!value || !Array.isArray(value)) return "";
        return `${headers[0]?.name || "X"}: ${value[0] ?? "-"}<br/>${headers[1]?.name || "Y"}: ${value[1] ?? "-"}`;
      },
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
    },
    grid: { left: "1%", right: "4%", bottom: "7%", containLabel: true },
    xAxis: {
      type: "value",
      name: headers[0]?.name,
      nameTextStyle: { color: colors.text },
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: { color: colors.textSecondary },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    yAxis: {
      type: "value",
      name: headers[1]?.name,
      nameTextStyle: { color: colors.text },
      axisLine: { lineStyle: { color: colors.gridLine } },
      axisLabel: { color: colors.textSecondary },
      splitLine: { lineStyle: { color: colors.splitLine } },
    },
    series: [{ type: "scatter", data: scatterData, symbolSize: 8 }],
  };
}

// ─── Config-based chart generation ───

export interface ChartConfig {
  xAxis?: string;
  /** @deprecated Use yAxes instead. Kept for backward compatibility. */
  yAxis?: string;
  yAxes?: string[];
  dimension?: string;
  dimensions?: string[];
  stack?: boolean;
  showLegend?: boolean;
  showValue?: boolean;
  showAxis?: boolean;
  showGridLine?: boolean;
  themeColor?: string;
  xAxisFormat?: XAxisFormat;
  yAxisFormat?: MetricFormat;
  barOrientation?: "vertical" | "horizontal";
  lineVariant?: "line" | "area" | "smooth" | "step";
  pieVariant?: "pie" | "ring" | "rose";
  metrics?: string[];
  metricValueSize?: string;
  subheader?: string;
  subheaderSize?: string;
  order?: "x_asc" | "x_desc" | "y_asc" | "y_desc";
}

export type MetricFormat =
  | "original"
  | "comma"
  | "decimal1"
  | "decimal2"
  | "percent"
  | "percent0"
  | "percent1"
  | "percent2"
  | "k"
  | "currency"
  | "compact";

export type XAxisFormat =
  | "original"
  | "date_iso"
  | "date_us"
  | "date_eu"
  | "date_short"
  | "date_month_year"
  | "date_year"
  | "date_month_day"
  | "date_quarter"
  | "date_time"
  | "number_comma"
  | "number_compact";

export function formatXAxisValue(value: unknown, format?: XAxisFormat): string {
  if (value === null || value === undefined) return "-";
  const str = String(value);
  if (!format || format === "original") return str;

  if (format.startsWith("number_")) {
    const num = Number(value);
    if (isNaN(num)) return str;
    if (format === "number_comma") return num.toLocaleString();
    if (format === "number_compact") {
      if (Math.abs(num) >= 1e9) return (num / 1e9).toFixed(1) + "B";
      if (Math.abs(num) >= 1e6) return (num / 1e6).toFixed(1) + "M";
      if (Math.abs(num) >= 1e3) return (num / 1e3).toFixed(1) + "K";
      return num.toString();
    }
    return str;
  }

  // Parse YYYYMMDD format (e.g. "20260316")
  let date: Date;
  if (/^\d{8}$/.test(str)) {
    const yy = parseInt(str.slice(0, 4), 10);
    const mm = parseInt(str.slice(4, 6), 10) - 1;
    const dd = parseInt(str.slice(6, 8), 10);
    date = new Date(yy, mm, dd);
  } else {
    date = new Date(str);
  }
  if (isNaN(date.getTime())) return str;

  const y = date.getFullYear();
  const m = date.getMonth();
  const d = date.getDate();
  const pad = (n: number) => String(n).padStart(2, "0");
  const monthNames = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
  ];

  switch (format) {
    case "date_iso":
      return `${y}-${pad(m + 1)}-${pad(d)}`;
    case "date_us":
      return `${pad(m + 1)}/${pad(d)}/${y}`;
    case "date_eu":
      return `${pad(d)}/${pad(m + 1)}/${y}`;
    case "date_short":
      return `${monthNames[m]} ${pad(d)}`;
    case "date_month_year":
      return `${monthNames[m]} ${y}`;
    case "date_year":
      return `${y}`;
    case "date_month_day":
      return `${pad(m + 1)}/${pad(d)}`;
    case "date_quarter":
      return `Q${Math.floor(m / 3) + 1} ${y}`;
    case "date_time":
      return `${pad(m + 1)}/${pad(d)} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    default:
      return str;
  }
}

const THEME_COLOR_PALETTES: Record<string, string[]> = {
  // ── Default (mixed vibrant) ──
  default: THEME_COLORS,

  // ── Multi-color palettes ──
  vivid: [
    "#1FA8C9",
    "#454E7C",
    "#5AC189",
    "#FF7F44",
    "#666666",
    "#E04355",
    "#FCC700",
    "#A868B7",
    "#3CCCCB",
    "#A38F79",
  ],
  tropical: [
    "#6BD3B3",
    "#FCC550",
    "#408184",
    "#66CBE2",
    "#EE5960",
    "#484E5A",
    "#FF874E",
    "#03748E",
    "#C9BBAB",
    "#B17BAA",
  ],
  classic: [
    "#5470C6",
    "#91CC75",
    "#FAC858",
    "#EE6666",
    "#73C0DE",
    "#3BA272",
    "#FC8452",
    "#9A60B4",
    "#EA7CCC",
  ],
  bold: [
    "#3366cc",
    "#dc3912",
    "#ff9900",
    "#109618",
    "#990099",
    "#0099c6",
    "#dd4477",
    "#66aa00",
    "#b82e2e",
    "#316395",
  ],
  teal: [
    "#29696B",
    "#5BCACE",
    "#F4B02A",
    "#F1826A",
    "#792EB2",
    "#C96EC6",
    "#921E50",
    "#B27700",
    "#9C3498",
    "#E4679D",
  ],
  rainbow: [
    "#e6194b",
    "#3cb44b",
    "#ffe119",
    "#4363d8",
    "#f58231",
    "#911eb4",
    "#42d4f4",
    "#f032e6",
    "#bfef45",
    "#fabebe",
  ],
  ocean: [
    "#0077B6",
    "#00B4D8",
    "#48CAE4",
    "#90E0EF",
    "#023E8A",
    "#0096C7",
    "#ADE8F4",
    "#03045E",
    "#CAF0F8",
    "#0077B6",
  ],
  sunset: [
    "#FF6B6B",
    "#FFA07A",
    "#FFD93D",
    "#6BCB77",
    "#4D96FF",
    "#9B59B6",
    "#FF8C94",
    "#F9ED69",
    "#08D9D6",
    "#FF2E63",
  ],
  forest: [
    "#2D6A4F",
    "#40916C",
    "#52B788",
    "#74C69D",
    "#95D5B2",
    "#1B4332",
    "#B7E4C7",
    "#D8F3DC",
    "#081C15",
    "#143601",
  ],
  berry: [
    "#E63946",
    "#A8DADC",
    "#457B9D",
    "#1D3557",
    "#F1FAEE",
    "#E76F51",
    "#2A9D8F",
    "#264653",
    "#F4A261",
    "#E9C46A",
  ],
  neon: [
    "#00F5FF",
    "#FF006E",
    "#8338EC",
    "#FFBE0B",
    "#FB5607",
    "#3A86FF",
    "#FF006E",
    "#06D6A0",
    "#118AB2",
    "#EF476F",
  ],
  pastel: [
    "#FFB3BA",
    "#BAFFC9",
    "#BAE1FF",
    "#FFFFBA",
    "#E8BAFF",
    "#FFD4BA",
    "#BAF2FF",
    "#FFBAE8",
    "#D4FFBA",
    "#BAC8FF",
  ],
  earth: [
    "#8B4513",
    "#DEB887",
    "#D2691E",
    "#CD853F",
    "#F4A460",
    "#A0522D",
    "#BC8F8F",
    "#F5DEB3",
    "#C4A882",
    "#8B7355",
  ],
  nordic: [
    "#5E81AC",
    "#81A1C1",
    "#88C0D0",
    "#8FBCBB",
    "#BF616A",
    "#D08770",
    "#EBCB8B",
    "#A3BE8C",
    "#B48EAD",
    "#4C566A",
  ],
  retro: [
    "#264653",
    "#2A9D8F",
    "#E9C46A",
    "#F4A261",
    "#E76F51",
    "#606C38",
    "#283618",
    "#DDA15E",
    "#BC6C25",
    "#FEFAE0",
  ],
  candy: [
    "#FF69B4",
    "#FF1493",
    "#DB7093",
    "#FF6EB4",
    "#FFB6C1",
    "#C71585",
    "#FF00FF",
    "#BA55D3",
    "#DDA0DD",
    "#EE82EE",
  ],

  // ── Single-hue palettes ──
  blue: ["#3b82f6", "#60a5fa", "#2563eb", "#93c5fd", "#1d4ed8", "#bfdbfe"],
  indigo: ["#6366f1", "#818cf8", "#4f46e5", "#a5b4fc", "#4338ca", "#c7d2fe"],
  cyan: ["#06b6d4", "#22d3ee", "#0891b2", "#67e8f9", "#0e7490", "#a5f3fc"],
  emerald: ["#10b981", "#34d399", "#059669", "#6ee7b7", "#047857", "#a7f3d0"],
  violet: ["#8b5cf6", "#a78bfa", "#7c3aed", "#c4b5fd", "#6d28d9", "#ddd6fe"],
  amber: ["#f59e0b", "#fbbf24", "#d97706", "#fcd34d", "#b45309", "#fde68a"],
  rose: ["#f43f5e", "#fb7185", "#e11d48", "#fda4af", "#be123c", "#fecdd3"],
  slate: ["#475569", "#64748b", "#334155", "#94a3b8", "#1e293b", "#cbd5e1"],

  // Legacy aliases
  green: ["#10b981", "#34d399", "#059669", "#6ee7b7", "#047857", "#a7f3d0"],
  purple: ["#8b5cf6", "#a78bfa", "#7c3aed", "#c4b5fd", "#6d28d9", "#ddd6fe"],
  orange: ["#f97316", "#fb923c", "#ea580c", "#fdba74", "#c2410c", "#fed7aa"],
  gray: ["#475569", "#64748b", "#334155", "#94a3b8", "#1e293b", "#cbd5e1"],
};

export function formatValue(value: unknown, format?: MetricFormat): string {
  const num = Number(value);
  if (isNaN(num)) return String(value ?? "-");
  switch (format) {
    case "comma":
      return num.toLocaleString();
    case "decimal1":
      return num.toLocaleString(undefined, {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
      });
    case "decimal2":
      return num.toLocaleString(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
    case "percent":
      return num.toLocaleString() + "%";
    case "percent0":
      return Math.round(num).toLocaleString() + "%";
    case "percent1":
      return (
        (num * 100).toLocaleString(undefined, {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        }) + "%"
      );
    case "percent2":
      return (
        (num * 100).toLocaleString(undefined, {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        }) + "%"
      );
    case "k": {
      if (Math.abs(num) >= 1e9) return (num / 1e9).toFixed(1) + "B";
      if (Math.abs(num) >= 1e6) return (num / 1e6).toFixed(1) + "M";
      if (Math.abs(num) >= 1e3) return (num / 1e3).toFixed(1) + "k";
      return num.toString();
    }
    case "currency":
      return (
        "$" +
        num.toLocaleString(undefined, {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        })
      );
    case "compact": {
      if (num >= 1e9) return (num / 1e9).toFixed(1) + "B";
      if (num >= 1e6) return (num / 1e6).toFixed(1) + "M";
      if (num >= 1e3) return (num / 1e3).toFixed(1) + "K";
      return num.toString();
    }
    case "original":
      return String(value);
    default:
      return num.toLocaleString();
  }
}

export function generateChartOptionWithConfig(
  result: ResultData,
  chartType: ChartType,
  config: ChartConfig,
): Record<string, unknown> | null {
  if (!result?.headerList?.length || !result?.dataList?.length) return null;
  if (chartType === "TABLE" || chartType === "CARD") return null;

  // Normalize headerList: ensure every header is { name: string, dataType?: string }
  const allHeaders: Array<{ name: string; dataType?: string }> =
    result.headerList.map((h) =>
      typeof h === "string"
        ? { name: h }
        : { name: h.name || String(h), dataType: h.dataType },
    );
  const paletteColors =
    THEME_COLOR_PALETTES[config.themeColor || "default"] || THEME_COLORS;
  const colors = getThemeColors();
  const yAxisFormat = config.yAxisFormat || "comma";

  // Find x-axis index
  let xIdx = 0;
  if (config.xAxis) {
    const idx = allHeaders.findIndex((h) => getHeaderName(h) === config.xAxis);
    if (idx >= 0) xIdx = idx;
  } else {
    xIdx = findAxes(result).xIdx;
  }

  // Find y-axis indices
  let yIndices: number[] = [];
  if (config.yAxes && config.yAxes.length > 0) {
    yIndices = config.yAxes
      .map((name) => allHeaders.findIndex((h) => getHeaderName(h) === name))
      .filter((i) => i >= 0);
  }
  if (yIndices.length === 0) yIndices = findAxes(result).yIndices;
  if (yIndices.length === 0) return null;

  // Find dimension indices (supports composite dimensions)
  const effectiveDimensions = config.dimensions && config.dimensions.length > 0
    ? config.dimensions
    : config.dimension ? [config.dimension] : [];
  const dimensionIndices = effectiveDimensions
    .map((d) => allHeaders.findIndex((h) => getHeaderName(h) === d))
    .filter((i) => i >= 0);
  const dimensionIdx = dimensionIndices.length > 0 ? dimensionIndices[0] : -1;

  let data = [...result.dataList];

  // Apply ordering
  if (config.order) {
    const sortIdx = config.order.startsWith("x") ? xIdx : yIndices[0];
    const isAsc = config.order.endsWith("asc");
    const isNumSort =
      config.order.startsWith("y") ||
      isNumericColumn(allHeaders[sortIdx], data, sortIdx);
    data.sort((a, b) => {
      const aVal = a[sortIdx];
      const bVal = b[sortIdx];
      if (isNumSort) {
        const diff = (Number(aVal) || 0) - (Number(bVal) || 0);
        return isAsc ? diff : -diff;
      }
      const diff = String(aVal ?? "").localeCompare(String(bVal ?? ""));
      return isAsc ? diff : -diff;
    });
  } else if (isDateLikeColumn(allHeaders[xIdx], data, xIdx)) {
    data = sortDataByDateAsc(data, xIdx);
  }

  const xAxisFormat = config.xAxisFormat;
  const xDataRaw = data.map((row) => String(row[xIdx] ?? ""));
  const xData =
    xAxisFormat && xAxisFormat !== "original"
      ? xDataRaw.map((v) => formatXAxisValue(v, xAxisFormat))
      : xDataRaw;
  const xName = getHeaderName(allHeaders[xIdx]);
  const showLegend = config.showLegend !== false;
  const showAxis = config.showAxis !== false;
  const showGridLine = config.showGridLine !== false;
  const showValue = config.showValue !== false; // Default true (matches React)

  const baseGrid = {
    left: "1%",
    right: "4%",
    bottom: "15%",
    containLabel: true,
  };

  switch (chartType) {
    case "BAR": {
      const isHorizontal = config.barOrientation === "horizontal";

      // Dimension pivot for BAR (supports composite dimensions)
      if (
        dimensionIndices.length > 0 &&
        dimensionIndices.every((di) => di !== xIdx && di !== yIndices[0])
      ) {
        return createConfigPivotedChart(
          "bar",
          allHeaders,
          data,
          xIdx,
          yIndices[0],
          dimensionIndices,
          colors,
          paletteColors,
          config,
          yAxisFormat,
        );
      }

      const categoryAxis: Record<string, unknown> = {
        type: "category",
        data: xData,
        axisLine: { lineStyle: { color: colors.gridLine }, show: showAxis },
        axisLabel: {
          color: colors.textSecondary,
          rotate: !isHorizontal && xData.length > 10 ? 45 : 0,
          show: showAxis,
        },
      };
      const valueAxis: Record<string, unknown> = {
        type: "value",
        min: 0,
        axisLine: { lineStyle: { color: colors.gridLine }, show: showAxis },
        axisLabel: {
          color: colors.textSecondary,
          show: showAxis,
          formatter: (value: number) => formatValue(value, yAxisFormat),
        },
        splitLine: {
          lineStyle: { color: colors.splitLine },
          show: showGridLine,
        },
      };
      return {
        color: paletteColors,
        backgroundColor: "transparent",
        textStyle: { color: colors.text },
        tooltip: {
          trigger: "axis",
          axisPointer: { type: "shadow" },
          backgroundColor: colors.background,
          borderColor: colors.gridLine,
          textStyle: { color: colors.text },
          confine: false,
          appendToBody: true,
          valueFormatter: (value: unknown) => formatValue(value, yAxisFormat),
        },
        legend: {
          show: showLegend && yIndices.length > 1,
          bottom: 0,
          textStyle: { color: colors.text },
        },
        grid: baseGrid,
        xAxis: isHorizontal ? valueAxis : categoryAxis,
        yAxis: isHorizontal ? categoryAxis : valueAxis,
        series: yIndices.map((yi) => ({
          name: getHeaderName(allHeaders[yi]),
          type: "bar",
          data: data.map((row) => Number(row[yi]) || 0),
          stack: config.stack ? "total" : undefined,
          label: {
            show: showValue,
            position: isHorizontal ? "right" : "top",
            color: colors.text,
            fontSize: 11,
            formatter: (params: any) => formatValue(params.value, yAxisFormat),
          },
          emphasis: { focus: "series" },
        })),
      };
    }
    case "LINE": {
      const isSmooth =
        config.lineVariant === "smooth" || config.lineVariant === "area";
      const isStep = config.lineVariant === "step";
      const isArea = config.lineVariant === "area";

      // Dimension pivot for LINE (supports composite dimensions)
      if (
        dimensionIndices.length > 0 &&
        dimensionIndices.every((di) => di !== xIdx && di !== yIndices[0])
      ) {
        return createConfigPivotedChart(
          "line",
          allHeaders,
          data,
          xIdx,
          yIndices[0],
          dimensionIndices,
          colors,
          paletteColors,
          config,
          yAxisFormat,
        );
      }

      return {
        color: paletteColors,
        backgroundColor: "transparent",
        textStyle: { color: colors.text },
        tooltip: {
          trigger: "axis",
          axisPointer: { type: "cross" },
          backgroundColor: colors.background,
          borderColor: colors.gridLine,
          textStyle: { color: colors.text },
          confine: false,
          appendToBody: true,
          valueFormatter: (value: unknown) => formatValue(value, yAxisFormat),
        },
        legend: {
          show: showLegend && yIndices.length > 1,
          data: yIndices.map((yi) => getHeaderName(allHeaders[yi])),
          bottom: 0,
          textStyle: { color: colors.text },
        },
        grid: baseGrid,
        xAxis: {
          type: "category",
          boundaryGap: false,
          data: xData,
          axisLine: { lineStyle: { color: colors.gridLine }, show: showAxis },
          axisLabel: { color: colors.textSecondary, show: showAxis },
          splitLine: { lineStyle: { color: colors.splitLine } },
        },
        yAxis: {
          type: "value",
          min: 0,
          axisLine: { lineStyle: { color: colors.gridLine }, show: showAxis },
          axisLabel: {
            color: colors.textSecondary,
            show: showAxis,
            formatter: (value: number) => formatValue(value, yAxisFormat),
          },
          splitLine: {
            lineStyle: { color: colors.splitLine },
            show: showGridLine,
          },
        },
        series: yIndices.map((yi) => ({
          name: getHeaderName(allHeaders[yi]),
          type: "line",
          data: data.map((row) => {
            const v = row[yi];
            return v === null || v === undefined ? null : Number(v);
          }),
          smooth: isSmooth,
          step: isStep ? ("middle" as const) : undefined,
          areaStyle: isArea ? { opacity: 0.18 } : undefined,
          symbol: showValue ? "circle" : "none",
          symbolSize: showValue ? 6 : 0,
          label: {
            show: showValue,
            color: colors.text,
            formatter: (params: any) => formatValue(params.value, yAxisFormat),
          },
          emphasis: { focus: "series" },
        })),
      };
    }
    case "PIE": {
      const yi = yIndices[0];
      // Group by dimension and sum values (matches React)
      const aggregatedMap = new Map<string, number>();
      data.forEach((row) => {
        const name = String(row[xIdx] ?? "Unknown");
        const value = Number(row[yi]) || 0;
        aggregatedMap.set(name, (aggregatedMap.get(name) || 0) + value);
      });
      const pieData = Array.from(aggregatedMap.entries())
        .map(([name, value]) => ({ name, value }))
        .filter((d) => d.value > 0)
        .sort((a, b) => b.value - a.value);

      if (pieData.length === 0) {
        return {
          backgroundColor: "transparent",
          title: {
            text: "No valid data",
            left: "center",
            top: "center",
            textStyle: { color: colors.text },
          },
        };
      }

      const radius =
        config.pieVariant === "ring"
          ? ["40%", "70%"]
          : config.pieVariant === "rose"
            ? ["20%", "60%"]
            : ["30%", "60%"];

      return {
        color: paletteColors,
        backgroundColor: "transparent",
        textStyle: { color: colors.text },
        tooltip: {
          trigger: "item",
          formatter: (params: any) => {
            const name = params.name || "";
            const value = formatValue(params.value, yAxisFormat);
            const percent = params.percent || 0;
            return `${name}: ${value} (${percent}%)`;
          },
          backgroundColor: colors.background,
          borderColor: colors.gridLine,
          textStyle: { color: colors.text },
          confine: false,
          appendToBody: true,
        },
        legend:
          showLegend === false
            ? undefined
            : {
                orient: "horizontal",
                bottom: 0,
                data: pieData.map((d) => d.name),
                textStyle: { color: colors.text },
              },
        series: [
          {
            name: getHeaderName(allHeaders[yi]) || "Value",
            type: "pie",
            roseType: config.pieVariant === "rose" ? "radius" : undefined,
            radius,
            center: ["50%", "45%"],
            data: pieData,
            label: {
              show: showValue,
              color: colors.text,
              formatter: (params: any) => `${params.name}: ${params.percent}%`,
            },
            labelLine: { show: true },
            emphasis: {
              itemStyle: {
                shadowBlur: 10,
                shadowOffsetX: 0,
                shadowColor: "rgba(0, 0, 0, 0.5)",
              },
            },
          },
        ],
      };
    }
    case "SCATTER": {
      const yi = yIndices[0];
      const scatterXFormat = xAxisFormat || "comma";
      return {
        color: paletteColors,
        backgroundColor: "transparent",
        textStyle: { color: colors.text },
        tooltip: {
          trigger: "item",
          formatter: (params: any) => {
            const value = params?.value;
            if (!value || !Array.isArray(value)) return "";
            const xFormatted = scatterXFormat.startsWith("number_") || scatterXFormat === "original"
              ? formatXAxisValue(value[0], scatterXFormat as XAxisFormat)
              : formatValue(value[0], "comma");
            return `${getHeaderName(allHeaders[xIdx])}: ${xFormatted}<br/>${getHeaderName(allHeaders[yi])}: ${formatValue(value[1], yAxisFormat)}`;
          },
          backgroundColor: colors.background,
          borderColor: colors.gridLine,
          textStyle: { color: colors.text },
          confine: false,
          appendToBody: true,
        },
        grid: { left: "1%", right: "4%", bottom: "7%", containLabel: true },
        xAxis: {
          type: "value",
          name: xName,
          nameTextStyle: { color: colors.text },
          axisLine: { lineStyle: { color: colors.gridLine }, show: showAxis },
          axisLabel: {
            color: colors.textSecondary,
            show: showAxis,
            formatter: (value: number) =>
              xAxisFormat && xAxisFormat.startsWith("number_")
                ? formatXAxisValue(value, xAxisFormat)
                : formatValue(value, "comma"),
          },
          splitLine: {
            lineStyle: { color: colors.splitLine },
            show: showGridLine,
          },
        },
        yAxis: {
          type: "value",
          name: getHeaderName(allHeaders[yi]),
          nameTextStyle: { color: colors.text },
          axisLine: { lineStyle: { color: colors.gridLine }, show: showAxis },
          axisLabel: {
            color: colors.textSecondary,
            show: showAxis,
            formatter: (value: number) => formatValue(value, yAxisFormat),
          },
          splitLine: {
            lineStyle: { color: colors.splitLine },
            show: showGridLine,
          },
        },
        series: [
          {
            type: "scatter",
            data: data.map((row) => [
              Number(row[xIdx]) || 0,
              Number(row[yi]) || 0,
            ]),
            symbolSize: 10,
            emphasis: { focus: "self" },
          },
        ],
      };
    }
    default:
      return null;
  }
}

/**
 * Create pivoted chart with config (supports single or composite dimension grouping)
 */
function createConfigPivotedChart(
  chartType: "line" | "bar",
  allHeaders: ResultData["headerList"],
  data: unknown[][],
  xIdx: number,
  yIdx: number,
  dimensionIndices: number | number[],
  colors: ReturnType<typeof getThemeColors>,
  paletteColors: string[],
  config: ChartConfig,
  yAxisFormat: MetricFormat,
): Record<string, unknown> {
  const dimIdxArr = Array.isArray(dimensionIndices) ? dimensionIndices : [dimensionIndices];

  const xAxisRawValues = [...new Set(data.map((row) => String(row[xIdx] ?? "")))];
  const xAxisFmt = config.xAxisFormat;
  const xAxisValues =
    xAxisFmt && xAxisFmt !== "original"
      ? xAxisRawValues.map((v) => formatXAxisValue(v, xAxisFmt))
      : xAxisRawValues;

  const getCompositeDimValue = (row: unknown[]): string =>
    dimIdxArr.map((di) => String(row[di] ?? "")).join(" · ");

  const dimensionValues = [
    ...new Set(data.map((row) => getCompositeDimValue(row))),
  ];

  const dataMap: Record<string, Record<string, number>> = {};
  data.forEach((row) => {
    const xValue = String(row[xIdx] ?? "");
    const rawKey =
      xAxisFmt && xAxisFmt !== "original"
        ? formatXAxisValue(xValue, xAxisFmt)
        : xValue;
    const dimValue = getCompositeDimValue(row);
    const value = Number(row[yIdx] ?? 0);
    if (!dataMap[rawKey]) dataMap[rawKey] = {};
    dataMap[rawKey][dimValue] = (dataMap[rawKey][dimValue] || 0) + value;
  });

  const showValue = config.showValue !== false;
  const isSmooth =
    chartType === "line" &&
    (config.lineVariant === "smooth" || config.lineVariant === "area");
  const isStep = chartType === "line" && config.lineVariant === "step";
  const isArea = chartType === "line" && config.lineVariant === "area";
  const isHorizontalBar =
    chartType === "bar" && config.barOrientation === "horizontal";

  const series = dimensionValues.map((dimValue) => ({
    name: dimValue,
    type: chartType,
    data: xAxisValues.map(
      (xValue) =>
        dataMap[xValue]?.[dimValue] ?? (chartType === "bar" ? 0 : null),
    ),
    smooth: chartType === "line" ? isSmooth : undefined,
    step: isStep ? ("middle" as const) : undefined,
    areaStyle: isArea ? { opacity: 0.18 } : undefined,
    symbol:
      chartType === "line" && showValue
        ? "circle"
        : chartType === "line"
          ? "none"
          : undefined,
    symbolSize:
      chartType === "line" && showValue
        ? 6
        : chartType === "line"
          ? 0
          : undefined,
    label: {
      show: showValue,
      color: colors.text,
      formatter: (params: any) => formatValue(params.value, yAxisFormat),
    },
    ...(chartType === "bar" && config.stack ? { stack: "total" } : {}),
    emphasis: { focus: "series" },
  }));

  return {
    color: paletteColors,
    backgroundColor: "transparent",
    textStyle: { color: colors.text },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: chartType === "line" ? "cross" : "shadow" },
      backgroundColor: colors.background,
      borderColor: colors.gridLine,
      textStyle: { color: colors.text },
      confine: false,
      appendToBody: true,
      valueFormatter: (value: unknown) => formatValue(value, yAxisFormat),
    },
    legend: {
      data: dimensionValues,
      bottom: 0,
      textStyle: { color: colors.text },
    },
    grid: { left: "1%", right: "4%", bottom: "15%", containLabel: true },
    xAxis: isHorizontalBar
      ? {
          type: "value",
          min: 0,
          axisLine: { lineStyle: { color: colors.gridLine } },
          axisLabel: {
            color: colors.textSecondary,
            formatter: (value: number) => formatValue(value, yAxisFormat),
          },
          splitLine: { lineStyle: { color: colors.splitLine } },
        }
      : {
          type: "category",
          boundaryGap: chartType === "bar",
          data: xAxisValues,
          axisLine: { lineStyle: { color: colors.gridLine } },
          axisLabel: { color: colors.textSecondary },
          splitLine: { lineStyle: { color: colors.splitLine } },
        },
    yAxis: isHorizontalBar
      ? {
          type: "category",
          data: xAxisValues,
          axisLine: { lineStyle: { color: colors.gridLine } },
          axisLabel: { color: colors.textSecondary },
          splitLine: { lineStyle: { color: colors.splitLine } },
        }
      : {
          type: "value",
          min: 0,
          axisLine: { lineStyle: { color: colors.gridLine } },
          axisLabel: {
            color: colors.textSecondary,
            formatter: (value: number) => formatValue(value, yAxisFormat),
          },
          splitLine: { lineStyle: { color: colors.splitLine } },
        },
    series,
  };
}

// ─── Chart type guessing ───

export function guessChartType(result: ResultData): ChartType {
  if (!result?.headerList?.length || !result?.dataList?.length) return "TABLE";

  const { yIndices } = findAxes(result);
  const rowCount = result.dataList.length;

  if (rowCount === 1 && yIndices.length >= 1) return "CARD";
  if (rowCount <= 8 && yIndices.length === 1) return "PIE";

  const xHeader = getHeaderName(result.headerList[0]).toLowerCase();
  if (
    xHeader.includes("date") ||
    xHeader.includes("time") ||
    xHeader.includes("month") ||
    xHeader.includes("year") ||
    xHeader.includes("day")
  ) {
    return "LINE";
  }

  if (yIndices.length >= 1) return "BAR";
  return "TABLE";
}

// ─── CARD / Metric Card Utilities ───

export interface CardMetric {
  name: string;
  raw: any;
  isNumeric: boolean;
}

export function buildCardMetrics(
  resultData: ResultData | undefined,
  selectedMetrics?: string[],
): CardMetric[] {
  if (!resultData) return [];
  const headers = resultData.headerList || [];
  const dataList = resultData.dataList || [];
  if (headers.length === 0 || dataList.length === 0) return [];

  const validHeadersWithIdx = headers
    .map((h, idx) => ({ header: h, originalIdx: idx }))
    .filter((item) => getHeaderName(item.header) !== "Row Number");

  if (validHeadersWithIdx.length === 0) return [];

  const selectedSet =
    Array.isArray(selectedMetrics) && selectedMetrics.length > 0
      ? new Set(selectedMetrics)
      : null;
  const firstRow = dataList[0] || [];

  return validHeadersWithIdx
    .map(({ header, originalIdx }) => {
      const name = getHeaderName(header);
      if (selectedSet && !selectedSet.has(name)) return null;
      const raw = (firstRow as unknown[])[originalIdx];
      const num = Number(raw);
      const isNumeric =
        raw !== null && raw !== undefined && raw !== "" && !Number.isNaN(num);
      return { name, raw, isNumeric } as CardMetric;
    })
    .filter(Boolean) as CardMetric[];
}

export function formatMetricValue(val: any): string {
  if (val === null || val === undefined) return "-";
  const num = Number(val);
  if (Number.isNaN(num)) return String(val);
  return num.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

/**
 * Format a single result-grid cell for display. Drivers (PostgreSQL JDBC etc.)
 * stringify NUMERIC columns at their full precision, so AVG()/SUM() rows show
 * up as "2.6000000000000000" or "1.8181818181818182" — unreadable.
 *
 * Policy (safe defaults, no schema reasoning needed):
 *   - leave non-numeric strings alone (dates, IDs, JSON, plain text)
 *   - leave bigints / very large magnitudes alone (precision matters)
 *   - integers (no '.') stay as-is, just normalized through Number
 *   - decimals with > 4 significant fractional digits get clipped to 2
 *     fractional digits and have trailing zeros trimmed
 */
export function formatCellDisplay(val: unknown): string {
  if (val === null || val === undefined) return "";
  if (typeof val === "number") {
    if (!Number.isFinite(val)) return String(val);
    if (Number.isInteger(val)) return String(val);
    return trimTrailingZeros(val.toFixed(2));
  }
  if (typeof val !== "string") return String(val);
  const s = val.trim();
  if (s === "") return "";
  // Only touch values that look like a plain decimal number.
  if (!/^-?\d+\.\d+$/.test(s)) return val;
  const num = Number(s);
  if (Number.isNaN(num)) return val;
  // Numbers whose magnitude exceeds JS safe-integer territory: keep raw.
  if (Math.abs(num) >= Number.MAX_SAFE_INTEGER) return val;
  const dotIdx = s.indexOf(".");
  const fractionLen = s.length - dotIdx - 1;
  if (fractionLen <= 4) return trimTrailingZeros(s);
  return trimTrailingZeros(num.toFixed(2));
}

function trimTrailingZeros(s: string): string {
  if (!s.includes(".")) return s;
  return s.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
}

// ─── Export helpers ───

export function downloadChartAsPNG(chart: any, filename: string = "chart.png") {
  const url = chart.getDataURL({
    type: "png",
    pixelRatio: 2,
    backgroundColor: getThemeColors().background,
  });
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
}

export function downloadDataAsCSV(
  result: ResultData,
  filename: string = "data.csv",
) {
  if (!result || !result.headerList || !result.dataList) return;
  const headers = result.headerList.map((h) => getHeaderName(h));
  let csv = headers.join(",") + "\n";
  result.dataList.forEach((row) => {
    csv +=
      (row as unknown[])
        .map((cell) => {
          const cellStr = String(cell ?? "");
          return cellStr.includes(",") || cellStr.includes('"')
            ? `"${cellStr.replace(/"/g, '""')}"`
            : cellStr;
        })
        .join(",") + "\n";
  });
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
